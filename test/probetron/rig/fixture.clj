(ns probetron.rig.fixture
  "Fixture rig process that the ownership tests start, contend with, and terminate.

   It owns a target lock inside a temporary directory, starts one long-running
   helper that itself leaves a grandchild behind, and then waits for a signal."
  (:require [babashka.fs :as fs]
            [probetron.rig.runner :as runner]
            [probetron.stand-in :as stand-in]))

(declare hold! helper-command record-reset! pid-of)

(defn -main
  "Own a temporary target until a signal arrives.

   The first argument is the directory that carries the lock, the metadata, and
   the recorded pids, and --reset-on-exit asks for the optional reset."
  [directory & flags]
  (let [operation {:operation        :connect
                   :channel          :usb
                   :usb-wait-seconds 10
                   :rtt              nil
                   :reset-on-exit?   (boolean (some #{"--reset-on-exit"} flags))}
        runtime   (runner/runtime {:paths         {:lock   (str (fs/path directory "target.lock"))
                                                   :active (str (fs/path directory "active.edn"))}
                                   :reset-target! (fn [_runtime] (record-reset! directory))
                                   :perform       (fn [_operation session] (hold! directory session))})]
    (System/exit (runner/execute! operation runtime))))

(defn hold!
  "Start the owned helper, announce ownership, and wait for cleanup."
  [directory session]
  ((:start-helper! session) (helper-command directory) {:out :inherit :err :inherit})
  (stand-in/await-text! (fs/path directory "helper.pid"))
  (stand-in/await-text! (fs/path directory "grandchild.pid"))
  (println "holding")
  (flush)
  @(promise))

(defn helper-command
  "Return a helper that records its own pid and the pid of a grandchild it leaves running."
  [directory]
  ["/bin/sh" "-c" (str "echo $$ > " directory "/helper.pid; "
                       "sleep 300 & echo $! > " directory "/grandchild.pid; "
                       "wait")])

(defn record-reset!
  "Record whether the owned children were already gone when the reset ran."
  [directory]
  (spit (fs/file (fs/path directory "reset.log"))
        (str "reset"
             " helper=" (if (stand-in/alive? (pid-of directory "helper.pid")) "alive" "gone")
             " grandchild=" (if (stand-in/alive? (pid-of directory "grandchild.pid")) "alive" "gone")
             "\n")
        :append true))

(defn pid-of
  "Return the pid that a helper recorded."
  [directory name]
  (stand-in/pid-in (fs/path directory name)))
