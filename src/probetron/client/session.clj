(ns probetron.client.session
  "Imperative shell of the short client operations.

   It refreshes the cached rig key, builds one SSH invocation around the
   validated operation, streams an ELF upload to the rig over standard input,
   and gives back exactly the status the rig returned.
   The runtime map carries the environment, the key fetcher, the filesystem,
   and the process functions, so a test substitutes a temporary cache home, a
   local HTTP fixture, and a fake SSH executable for the real ones."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [probetron.client.command :as command]
            [probetron.client.key :as rig-key]
            [probetron.client.report :as report]
            [probetron.operation :as op]
            [probetron.version :as version])
  (:import (java.net InetAddress ServerSocket)))

(declare invoke! report-transport-failure! report-information! rig-information upload environment
         default-runtime default-executables default-filesystem write-key!
         reserve-port! make-link-directory! fail! warn!)

(def operations
  "The client operations that finish inside one short remote command."
  #{:info :status :flash :erase :reset})

(def transport-failure
  "The status that SSH itself returns when it never reached the rig."
  255)

(defn execute!
  "Carry out one short client operation and return its exit status."
  [operation runtime]
  (let [{:keys [path error]} (rig-key/refresh! (:host operation) runtime)]
    (cond
      error (fail! error)
      (= :info (:operation operation)) (report-information! operation path runtime)
      :else (:exit (invoke! operation path runtime {:out :inherit :err :inherit})))))

(defn invoke!
  "Run one rig operation over SSH and return what the process answered.

   The remote command is one quoted token list, and an ELF upload travels on
   standard input, so no client path and no second shell token ever reach the
   rig."
  [operation key runtime streams]
  (let [{:keys [executables run!]} runtime
        argv (command/ssh-argv {:ssh (:ssh executables) :key key :host (:host operation)}
                               (command/remote-command operation))
        result (run! argv (merge {:in (upload operation)} streams))]
    (report-transport-failure! (:host operation) (:exit result))
    result))

(defn report-transport-failure!
  "Explain an SSH invocation that never reached the rig and return its status.

   Only SSH itself answers 255, so a rig that refuses an operation keeps its
   own status and its own diagnostic."
  [host exit]
  (when (= transport-failure exit)
    (warn! (str "the SSH connection to " host " failed"
                ": check the address, the wired LAN, and that the rig has booted")))
  exit)

(defn report-information!
  "Report the client versions, the cached key, and the report of the rig.

   The rig answers in the format the client asked for, so text stays readable
   and EDN keeps stable keys on both sides."
  [operation key runtime]
  (let [{:keys [exit out]} (invoke! operation key runtime {:out :string :err :inherit})
        rig (rig-information out (:format operation))]
    (if rig
      (do (println (report/render (report/information {:probetron version/probetron-version
                                                       :babashka (System/getProperty "babashka.version")
                                                       :key key
                                                       :rig rig})
                                  (:format operation)))
          exit)
      (do (print out)
          (flush)
          (warn! (str "the rig at " (:host operation) " returned no readable information"))
          (if (zero? exit) op/exit-failure exit)))))

(defn rig-information
  "Return what the rig reported about itself, or nil when it reported nothing readable."
  [text format]
  (when-not (str/blank? text)
    (case format
      :edn (let [value (try (edn/read-string text) (catch Exception _ nil))]
             (when (map? value) value))
      :text (str/trim text))))

(defn upload
  "Return the standard input of one remote command.

   Only a flash and an RTT session send bytes, and every other operation closes
   standard input at once, so the rig never waits for a client that has
   nothing to say."
  [operation]
  (if-let [path (op/stdin-elf operation)]
    (fs/file path)
    ""))

(defn runtime
  "Return the client runtime.

   Production takes the real environment, HTTP fetcher, filesystem, and SSH
   executable, and every override replaces one entry so that a test owns a
   temporary client instead."
  ([] (runtime {}))
  ([overrides]
   (-> (merge default-runtime {:env (environment)} overrides)
       (assoc :executables (merge default-executables (:executables overrides)))
       (assoc :filesystem (merge default-filesystem (:filesystem overrides))))))

(def default-executables
  "The client programs that Probetron runs, resolved through PATH.

   Only SSH is required: a client without socat keeps every session and loses
   the pseudo-terminal presentation alone."
  {:ssh "ssh" :socat "socat"})

(def default-filesystem
  "The real filesystem behind the key cache."
  {:exists? (fn [path] (fs/exists? path))
   :read-bytes (fn [path] (fs/read-all-bytes path))
   :permissions (fn [path] (fs/posix->str (fs/posix-file-permissions path)))
   :make-directory! (fn [path] (fs/create-dirs path {:posix-file-permissions "rwx------"}))
   :write-key! (fn [path bytes] (write-key! path bytes))})

(def default-runtime
  "The production wiring of the key endpoint, the filesystem, and subprocesses."
  {:executables default-executables
   :filesystem default-filesystem
   :fetch! rig-key/http-fetch!
   :which (fn [program] (some-> (fs/which program) str))
   :reserve-port! (fn [] (reserve-port!))
   :make-link-directory! (fn [base] (make-link-directory! base))
   :delete-link-directory! (fn [path] (fs/delete-tree path))
   :run! (fn [argv opts] @(process/process argv (merge {:throw false} opts)))
   :spawn! (fn [argv opts] (process/process argv opts))})

(defn reserve-port!
  "Return a free ephemeral port on the client loopback.

   The reservation closes before SSH binds the same port, which is why a client
   that needs an exact port passes --local-port instead."
  []
  (with-open [socket (ServerSocket. 0 1 (InetAddress/getByName command/client-loopback))]
    (.getLocalPort socket)))

(defn make-link-directory!
  "Create the private volatile directory that carries one pseudo-terminal link."
  [base]
  (str (fs/create-temp-dir (cond-> {:prefix "probetron-pty" :posix-file-permissions "rwx------"}
                             base (assoc :path base)))))

(defn write-key!
  "Write one private key through a temporary neighbour that only the client may read."
  [path bytes]
  (let [temporary (str path ".new")]
    (fs/write-bytes temporary bytes)
    (fs/set-posix-file-permissions temporary rig-key/safe-mode)
    (fs/move temporary path {:replace-existing true :atomic-move true})))

(defn environment
  "Return the process environment as a plain map."
  []
  (into {} (map (fn [entry] [(key entry) (val entry)])) (System/getenv)))

(defn fail!
  "Report why the client refuses to run an operation and return the failure status."
  [message]
  (warn! message)
  op/exit-failure)

(defn warn!
  "Write one client diagnostic to standard error."
  [message]
  (binding [*out* *err*] (println (str "probetron: " message))))
