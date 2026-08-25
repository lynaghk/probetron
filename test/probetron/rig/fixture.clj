(ns probetron.rig.fixture
  "Fixture rig process that the ownership tests start, contend with, and terminate.

   It owns a target lock inside a temporary directory, starts one long-running
   helper that itself leaves a grandchild behind, and then waits for a signal."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [probetron.rig.runner :as runner]))

(declare hold! helper-command await-file! record-reset! pid-of alive?)

(defn -main
  "Own a temporary target until a signal arrives.

   The first argument is the directory that carries the lock, the metadata, and
   the recorded pids, and --reset-on-exit asks for the optional reset."
  [directory & flags]
  (let [operation {:operation :connect
                   :channel :usb
                   :usb-wait-seconds 10
                   :rtt nil
                   :reset-on-exit? (boolean (some #{"--reset-on-exit"} flags))}
        runtime (runner/runtime {:paths {:lock (str (fs/path directory "target.lock"))
                                         :active (str (fs/path directory "active.edn"))}
                                 :reset-target! (fn [_runtime] (record-reset! directory))
                                 :perform (fn [_operation session] (hold! directory session))})]
    (System/exit (runner/execute! operation runtime))))

(defn hold!
  "Start the owned helper, announce ownership, and wait for cleanup."
  [directory session]
  ((:start-helper! session) (helper-command directory) {:out :inherit :err :inherit})
  (await-file! (fs/path directory "helper.pid"))
  (await-file! (fs/path directory "grandchild.pid"))
  (println "holding")
  (flush)
  @(promise))

(defn helper-command
  "Return a helper that records its own pid and the pid of a grandchild it leaves running."
  [directory]
  ["/bin/sh" "-c" (str "echo $$ > " directory "/helper.pid; "
                       "sleep 300 & echo $! > " directory "/grandchild.pid; "
                       "wait")])

(defn await-file!
  "Wait until a helper has recorded one pid."
  [path]
  (loop [attempts 500]
    (when (and (pos? attempts) (not (pos? (or (when (fs/exists? path) (fs/size path)) 0))))
      (Thread/sleep 10)
      (recur (dec attempts)))))

(defn record-reset!
  "Record whether the owned children were already gone when the reset ran."
  [directory]
  (spit (fs/file (fs/path directory "reset.log"))
        (str "reset"
             " helper=" (if (alive? (pid-of directory "helper.pid")) "alive" "gone")
             " grandchild=" (if (alive? (pid-of directory "grandchild.pid")) "alive" "gone")
             "\n")
        :append true))

(defn pid-of
  "Return the pid that a helper recorded."
  [directory name]
  (let [file (fs/file (fs/path directory name))]
    (when (fs/exists? file) (parse-long (str/trim (slurp file))))))

(defn alive?
  "Tell whether a pid still runs. A zombie waits for its parent, so it counts as gone."
  [pid]
  (let [status (fs/path "/proc" (str pid) "status")]
    (boolean (and pid
                  (fs/exists? status)
                  (not (re-find #"(?m)^State:\s+Z" (String. (fs/read-all-bytes status))))))))
