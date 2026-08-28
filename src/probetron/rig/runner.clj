(ns probetron.rig.runner
  "Imperative shell that owns the target hardware for the length of one operation.

   It takes the nonblocking target lock, records the active command, runs the
   operation, and reaps every process group the operation started before it
   gives the lock back.
   The runtime map carries the appliance paths and the filesystem and process
   functions, so a test substitutes temporary paths, a fixture child, and a
   recorded reset while production always takes the fixed absolute paths."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [probetron.rig.hardware :as hardware]
            [probetron.rig.lifecycle :as lifecycle]
            [probetron.operation :as op]
            [probetron.version :as version])
  (:import (java.io InputStream IOException OutputStream)
           (java.util.concurrent TimeUnit)))

(declare default-runtime default-paths default-executables default-filesystem default-hardware
         own-target! report-status! probe-lock acquire-lock! handshake! release-lock!
         register-cleanup! watch-stdin! log-event! report-log!
         session start-helper! clean-up! stop-groups! signal-group! await-exit! run-reset!
         write-active! read-owner! delete-active! write-atomically! glob-paths report-busy!
         fail! warn!)

(def terminate-grace-ms
  "How long an owned process group has to answer SIGTERM before SIGKILL follows."
  2000)

(def reap-grace-ms
  "How long the shell waits for a signalled process to disappear."
  1000)

(defn execute!
  "Carry out one validated rig operation and return its exit status.

   The runtime supplies :perform, which receives the operation and a session
   handle, and :reset-target!, which --reset-on-exit calls once."
  [operation {:keys [paths filesystem] :as runtime}]
  (let [directory (str (fs/parent (:lock paths)))]
    (cond
      (not ((:directory? filesystem) directory))
      (fail! (str "missing runtime directory " directory
                  ": the rig image creates it at boot, so reboot the rig or create it first"))

      (= :none (lifecycle/lock-mode operation))
      (case (:operation operation)
        :log (report-log! operation runtime)
        (report-status! operation runtime))

      :else
      (own-target! operation runtime))))

(defn own-target!
  "Own the target for one operation and clean up everything that operation started."
  [operation {:keys [paths pid perform] :as runtime}]
  (let [{:keys [lock holder exit]} (acquire-lock! runtime)]
    (case lock
      :busy (report-busy! runtime)
      :unavailable (fail! (str "cannot take the target lock " (:lock paths)
                               " (flock exit " exit "): check the rig installation"))
      :taken
      (let [state (atom {:holder holder
                         :groups []
                         :operation (:operation operation)
                         :channel (:channel operation)
                         :reset-on-exit? (true? (:reset-on-exit? operation))
                         :cleaned? false})]
        (try
          (write-active! runtime (lifecycle/active-metadata operation (pid) (java.time.Instant/now)))
          (log-event! runtime :session-start
                      {:operation (:operation operation) :channel (:channel operation)})
          (register-cleanup! state runtime)
          (perform operation (session state runtime))
          (catch Exception exception
            (warn! (or (ex-message exception) (str exception)))
            op/exit-failure)
          (finally (clean-up! state runtime)))))))

(defn register-cleanup!
  "Register the cleanup that a lost session runs.

   sshd sends SIGHUP when the client disconnects, and a shutdown hook also
   answers SIGINT and SIGTERM, so registration happens before the operation
   starts any helper."
  [state runtime]
  (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable #(clean-up! state runtime))))

(defn watch-stdin!
  "Release the target the instant the client's held-open standard input closes.

   Every long session holds the client's standard input open for as long as the
   client lives: a plain session sends nothing on it, and one with an upload
   frames the upload so the rest of the stream stays open (see
   `target/receive-session-elf!`). A read here therefore blocks until end of
   input, and end of input means the client is gone — a clean exit, a crash, or
   a hard kill that orphaned its SSH forward but closed the pipe the JVM held.
   Whatever the cause, the rig runs the same cleanup a signal would.

   The session starts this after it has read any upload, so the read draws only
   the tether that follows it. It runs on a daemon thread, so a session that
   ended another way never keeps the rig from exiting."
  [state {:keys [stdin] :as runtime}]
  (let [in ^InputStream (stdin)
        watch (fn []
                (try (loop [] (when (<= 0 (.read in)) (recur)))
                     (catch IOException _ nil))
                (clean-up! state runtime))
        thread (doto (Thread. ^Runnable watch "probetron-stdin-tether")
                 (.setDaemon true))]
    (.start thread)))

(defn session
  "Return the handle that one operation uses to start owned helpers.

   Cleanup is already registered when an operation receives it, so no helper
   can outlive the rig, and :stopping? tells a waiting operation that the
   child it watched died in that cleanup rather than on its own."
  [state runtime]
  {:runtime runtime
   :start-helper! (fn [argv opts] (start-helper! state runtime argv opts))
   ;; A session starts the client tether once it has read any upload, so the
   ;; tether reads only the standard input that outlives the upload.
   :watch-client! (fn [] (watch-stdin! state runtime))
   :stopping? (fn [] (true? (:cleaned? @state)))})

(defn start-helper!
  "Start one long-running helper as its own process group and own it until cleanup."
  [state {:keys [executables spawn!]} argv opts]
  (let [helper (spawn! (lifecycle/group-command executables argv) opts)]
    (swap! state update :groups conj {:pgid (.pid (:proc helper)) :process helper})
    helper))

(defn clean-up!
  "Reap the owned process groups, run the optional reset, drop the metadata, and release the lock.

   It runs once, whether the operation finished, the client disconnected, or a
   handled signal arrived, and the default path leaves the target alone."
  [state runtime]
  (let [mine (promise)
        [before] (swap-vals! state
                             (fn [s] (if (:cleaned? s)
                                       s
                                       (assoc s :cleaned? true :cleanup-done mine))))]
    (if (:cleaned? before)
      ;; Another caller owns the cleanup; wait for it to finish before returning,
      ;; so the lock is really back by the time any caller leaves.
      (some-> (:cleanup-done before) deref)
      (try
        (stop-groups! (:groups before) runtime)
        (when (:reset-on-exit? before) (run-reset! runtime))
        (delete-active! runtime)
        (release-lock! (:holder before))
        (log-event! runtime :session-end
                    {:operation (:operation before) :channel (:channel before)})
        (finally (deliver mine true))))))

(defn stop-groups!
  "Terminate and then forcibly reap only the process groups this operation owns.

   SIGKILL follows SIGTERM even when the helper itself has gone, because a
   listener or a bridge it left in the same group can still hold the target."
  [groups runtime]
  (doseq [{:keys [pgid process]} groups]
    (signal-group! runtime :TERM pgid)
    (await-exit! process terminate-grace-ms)
    (signal-group! runtime :KILL pgid)
    (await-exit! process reap-grace-ms)))

(defn signal-group!
  "Send one signal to one owned process group and ignore a group that already ended."
  [{:keys [executables spawn!]} signal pgid]
  @(spawn! (lifecycle/group-signal-command executables signal pgid)
           {:out :string :err :string :throw false}))

(defn await-exit!
  "Wait for one owned process and tell whether it ended."
  [helper timeout-ms]
  (.waitFor ^Process (:proc helper) timeout-ms TimeUnit/MILLISECONDS))

(defn run-reset!
  "Run the best-effort reset that --reset-on-exit asks for.

   Cleanup calls it after every owned child is gone and before the lock goes back."
  [{:keys [reset-target!] :as runtime}]
  (try
    (reset-target! runtime)
    (catch Exception exception
      (warn! (str "the reset on exit failed: " (or (ex-message exception) (str exception)))))))

(defn log-event!
  "Append one timestamped event to the append-only DUT record, best-effort.

   The record is a serialized history of every session the rig owned, so a DUT
   re-enumeration in the kernel log lines up with the flash, reset, or connect
   that caused it. It never fails the operation it records: a log the rig cannot
   write is a lost line, not a lost session."
  [{:keys [paths]} event fields]
  (try
    (let [file (fs/file (:dut-log paths))
          entry (into {:at (java.util.Date.) :event event}
                      (remove (comp nil? val) fields))]
      (fs/create-dirs (fs/parent file))
      (spit file (str (pr-str entry) "\n") :append true))
    (catch Exception _ nil)))

(defn report-log!
  "Print the DUT record the rig keeps, or say it has none yet.

   The record is what every owned session and the channel holder appended, so an
   operator reads the history of one DUT — the sessions the rig ran and the USB
   link that came and went under them — without an interactive login."
  [_operation {:keys [paths filesystem]}]
  (if-let [text (not-empty ((:read-file filesystem) (:dut-log paths)))]
    (print text)
    (println "no DUT events recorded yet"))
  (flush)
  op/exit-ok)

(defn acquire-lock!
  "Take the target lock without waiting.

   Return {:lock :taken :holder process}, {:lock :busy}, or {:lock :unavailable :exit status}."
  [{:keys [executables paths spawn!]}]
  (let [holder (spawn! (lifecycle/lock-holder-command executables (:lock paths))
                       {:in :stream :out :stream :err :inherit :throw false})]
    (if (handshake! holder)
      {:lock :taken :holder holder}
      (let [exit (:exit @holder)]
        (if (= op/exit-busy exit)
          {:lock :busy}
          {:lock :unavailable :exit exit})))))

(defn handshake!
  "Tell whether the holder took the lock, by echoing one byte through it.

   A holder that never took the lock has already gone, so the echo reads end of
   file instead of the byte."
  [holder]
  (try
    (doto ^OutputStream (:in holder) (.write 0) (.flush))
    (catch IOException _ nil))
  (not (neg? (.read ^InputStream (:out holder)))))

(defn release-lock!
  "Give the target lock back by letting its holder end.

   Closing the standard input of the holder ends it, and so does the death of
   the rig, which is why no lock survives a lost session."
  [holder]
  (when holder
    (try (.close ^OutputStream (:in holder)) (catch IOException _ nil))
    (await-exit! holder reap-grace-ms)))

(defn report-status!
  "Report the state of the target lock and its owner without opening the hardware."
  [{:keys [format]} {:keys [paths] :as runtime}]
  (let [lock (probe-lock runtime)
        held? (= :held lock)]
    (if (= :unavailable lock)
      (fail! (str "cannot read the target lock " (:lock paths) ": check the rig installation"))
      (let [owner (when held? (read-owner! runtime))]
        (when-not held? (delete-active! runtime))
        (println (lifecycle/render-status
                  (lifecycle/status-report (version/stamp-line (version/describe)) held? owner)
                  format))
        op/exit-ok))))

(defn probe-lock
  "Return :free, :held, or :unavailable for the target lock."
  [{:keys [executables paths spawn!]}]
  (let [exit (:exit @(spawn! (lifecycle/lock-probe-command executables (:lock paths))
                             {:out :string :err :inherit :throw false}))]
    (condp = exit
      op/exit-ok :free
      op/exit-busy :held
      :unavailable)))

(defn report-busy!
  "Name the operation that already owns the target and return the busy status."
  [runtime]
  (warn! (lifecycle/busy-message (read-owner! runtime)))
  op/exit-busy)

(defn write-active!
  "Record the active command, its root process, and its start time.

   Only the owner of the lock writes this file, and it appears whole or not at all."
  [{:keys [paths filesystem]} metadata]
  ((:write-file! filesystem) (:active paths) (str (pr-str metadata) "\n")))

(defn read-owner!
  "Return the recorded owner of the target, or nil when the record is missing or malformed."
  [{:keys [paths filesystem]}]
  (let [text ((:read-file filesystem) (:active paths))]
    (when text
      (lifecycle/owner (try (edn/read-string text) (catch Exception _ nil))))))

(defn delete-active!
  "Drop the active-command record."
  [{:keys [paths filesystem]}]
  ((:delete-file! filesystem) (:active paths)))

(defn runtime
  "Return the rig runtime.

   Production takes the fixed appliance paths, and every override replaces one
   entry so that a test owns a temporary target instead."
  ([] (runtime {}))
  ([overrides]
   (-> (merge default-runtime overrides)
       (assoc :paths (merge default-paths (:paths overrides)))
       (assoc :executables (merge default-executables (:executables overrides)))
       (assoc :filesystem (merge default-filesystem (:filesystem overrides)))
       (assoc :hardware (merge default-hardware (:hardware overrides))))))

(def default-paths
  "Every fixed rig path: the volatile state of one operation and the identity of the image."
  {:lock "/run/probetron/target.lock"
   :active "/run/probetron/active.edn"
   :dut-log "/run/probetron/dut.log"
   :uploads hardware/upload-directory
   :os-release "/etc/os-release"
   :hostname "/etc/hostname"
   :machine-id "/etc/machine-id"})

(def default-executables
  "Every appliance executable that the shell runs, by absolute path."
  {:flock "/usr/bin/flock"
   :setsid "/usr/bin/setsid"
   :kill "/bin/kill"
   :cat "/bin/cat"
   :noop "/bin/true"
   :script hardware/script-executable
   :probe-rs hardware/probe-rs-executable
   :gpioset hardware/gpioset-executable
   :socat hardware/socat-executable})

(def default-hardware
  "The fixed target slot that every hardware command addresses."
  hardware/defaults)

(def default-filesystem
  "The real filesystem behind the runtime."
  {:directory? fs/directory?
   :exists? fs/exists?
   :readable? fs/readable?
   :glob #'glob-paths
   :read-file (fn [path] (when (fs/exists? path) (slurp (fs/file path))))
   :write-file! #'write-atomically!
   :delete-file! fs/delete-if-exists})

(def default-runtime
  "The production wiring of the current process and subprocesses."
  {:paths default-paths
   :executables default-executables
   :filesystem default-filesystem
   :hardware default-hardware
   :pid (fn [] (.pid (java.lang.ProcessHandle/current)))
   :stdin (fn [] System/in)
   :spawn! process/process
   :run! (fn [argv opts]
           @(process/process argv (merge {:out :inherit :err :inherit :throw false} opts)))})

(defn glob-paths
  "Return every existing path that one absolute glob pattern names."
  [pattern]
  (let [path (fs/path pattern)]
    (mapv str (fs/glob (fs/parent path) (str (fs/file-name path))))))

(defn write-atomically!
  "Write a file through a temporary neighbour, so a reader never sees half a record."
  [path text]
  (let [temporary (fs/path (str path ".new"))]
    (spit (fs/file temporary) text)
    (fs/move temporary path {:replace-existing true :atomic-move true})))

(defn fail!
  "Report a missing or unusable rig resource and return the unavailable status."
  [message]
  (warn! message)
  op/exit-unavailable)

(defn warn!
  "Write one rig diagnostic to standard error."
  [message]
  (binding [*out* *err*] (println (str lifecycle/program-name ": " message))))
