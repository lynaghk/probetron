(ns probetron.rig.runner-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [probetron.rig.fixture :as fixture]
            [probetron.rig.runner :as runner]
            [probetron.stand-in :as stand-in]
            [probetron.operation :as op]
            [probetron.version :as version])
  (:import (java.io StringWriter)
           (java.util.concurrent TimeUnit)))

(declare temporary-directory paths rig-runtime start-rig! stop-rig! terminate! signal!
         await-exit! lock-state owner-record run-status! alive? recorded-pid elapsed-ms)

(deftest a-short-operation-owns-the-target-and-gives-it-back
  (let [directory (temporary-directory)
        seen (atom nil)]
    (try
      (let [exit (runner/execute! {:operation :reset}
                                  (rig-runtime directory
                                                {:perform (fn [_operation _session]
                                                            (reset! seen (owner-record directory))
                                                            op/exit-ok)}))]
        (is (= op/exit-ok exit))
        (testing "metadata appears only while the operation owns the lock"
          (is (= :reset (:command @seen)))
          (is (pos-int? (:pid @seen)))
          (is (re-matches #"\d{4}-\d{2}-\d{2}T.*Z" (:started-at @seen)))
          (is (nil? (owner-record directory))))
        (is (= :free (lock-state directory))))
      (finally (fs/delete-tree directory)))))

(deftest a-second-operation-fails-immediately-with-the-busy-status
  (let [directory (temporary-directory)
        rig (start-rig! directory)]
    (try
      (let [errors (StringWriter.)
            started (System/nanoTime)
            exit (binding [*err* errors] (runner/execute! {:operation :reset} (rig-runtime directory {})))]
        (is (= op/exit-busy exit))
        (is (< (elapsed-ms started) 3000) "a conflict must not wait for the lock")
        (testing "the busy answer names the operation that owns the target"
          (is (str/includes? (str errors) "busy"))
          (is (str/includes? (str errors) "connect"))
          (is (str/includes? (str errors) (str (.pid (:proc rig)))))))
      (finally (stop-rig! rig directory) (fs/delete-tree directory)))))

(deftest active-metadata-names-the-owner
  (let [directory (temporary-directory)
        rig (start-rig! directory)]
    (try
      (let [owner (owner-record directory)]
        (is (= :connect (:command owner)))
        (is (= (.pid (:proc rig)) (:pid owner)))
        (is (re-matches #"\d{4}-\d{2}-\d{2}T.*Z" (:started-at owner))))
      (testing "status reports the owner without opening the hardware"
        (let [text (run-status! directory :text)]
          (is (str/includes? text "lock: held"))
          (is (str/includes? text "active command: connect"))
          (is (str/includes? text (str (.pid (:proc rig))))))
        (is (= :held (lock-state directory))))
      (finally (stop-rig! rig directory) (fs/delete-tree directory)))))

(deftest a-handled-termination-reaps-owned-children-and-releases-the-lock
  (let [directory (temporary-directory)
        rig (start-rig! directory)
        helper (recorded-pid directory "helper.pid")
        grandchild (recorded-pid directory "grandchild.pid")]
    (try
      (is (alive? helper))
      (is (alive? grandchild))
      (terminate! rig)
      (await-exit! rig)
      (is (not (alive? helper)) "cleanup must reap the owned helper")
      (is (not (alive? grandchild)) "cleanup must reap the whole owned process group")
      (is (nil? (owner-record directory)) "cleanup must drop the active metadata")
      (is (= :free (lock-state directory)))
      (is (not (fs/exists? (fs/path directory "reset.log")))
          "the default cleanup path leaves the target alone")
      (finally (stop-rig! rig directory) (fs/delete-tree directory)))))

(deftest reset-on-exit-runs-after-child-cleanup
  (let [directory (temporary-directory)
        rig (start-rig! directory "--reset-on-exit")]
    (try
      (terminate! rig)
      (await-exit! rig)
      (let [record (slurp (fs/file (fs/path directory "reset.log")))]
        (is (str/includes? record "helper=gone"))
        (is (str/includes? record "grandchild=gone")))
      (is (= :free (lock-state directory)) "the reset runs before the lock goes back")
      (finally (stop-rig! rig directory) (fs/delete-tree directory)))))

(deftest a-closed-client-standard-input-releases-the-lock
  ;; The tether: a session holds the client's standard input open and reads it,
  ;; so closing that input — as a departing client does, however it departs —
  ;; ends the session and frees the lock with no signal.
  (let [directory (temporary-directory)
        out (java.io.PipedOutputStream.)
        in (java.io.PipedInputStream. out)]
    (try
      (let [session (future
                      (runner/execute!
                       {:operation :connect}
                       (rig-runtime directory
                                    {:stdin (fn [] in)
                                     :perform (fn [_operation session]
                                                ((:watch-client! session))
                                                (let [helper ((:start-helper! session)
                                                              ["/bin/sh" "-c" "sleep 300"]
                                                              {:out :inherit :err :inherit})]
                                                  (.waitFor ^Process (:proc helper))
                                                  op/exit-ok))})))]
        (Thread/sleep 300)
        (is (= :held (lock-state directory)) "the session holds the target while its client lives")
        (.close out)
        (is (= op/exit-ok (deref session 5000 :timeout))
            "closing the client input unblocks the session")
        (is (= :free (lock-state directory))
            "a departed client frees the lock even when no signal arrives")
        (is (nil? (owner-record directory)) "and drops the active metadata with it"))
      (finally (fs/delete-tree directory)))))

(deftest stale-metadata-does-not-report-a-false-owner
  (let [directory (temporary-directory)]
    (try
      (spit (fs/file (:active (paths directory)))
            (pr-str {:command :connect :pid 999999 :started-at "2026-01-01T00:00:00Z"}))
      (let [text (run-status! directory :text)]
        (is (str/includes? text (str "probetron: " version/probetron-version))
            "status names the build that answers, as info does")
        (is (str/includes? text "lock: free"))
        (is (not (str/includes? text "connect"))))
      (is (not (fs/exists? (:active (paths directory))))
          "a free lock drops the metadata of an operation that no longer runs")
      (finally (fs/delete-tree directory)))))

(deftest a-missing-runtime-directory-names-itself
  (let [root (temporary-directory)
        directory (str (fs/path root "absent"))
        errors (StringWriter.)]
    (try
      (let [exit (binding [*err* errors]
                   (runner/execute! {:operation :reset} (rig-runtime directory {})))]
        (is (= op/exit-unavailable exit))
        (is (str/includes? (str errors) directory)))
      (finally (fs/delete-tree root)))))

(defn temporary-directory
  "Return a fresh directory that carries one lock, its metadata, and recorded pids."
  []
  (str (fs/create-temp-dir {:prefix "probetron-rig"})))

(defn paths
  "Return the ownership paths inside a temporary directory."
  [directory]
  {:lock (str (fs/path directory "target.lock"))
   :active (str (fs/path directory "active.edn"))})

(defn rig-runtime
  "Return a runtime that owns a temporary target instead of the appliance one."
  [directory overrides]
  (runner/runtime (merge {:paths (paths directory)
                          :perform (fn [_operation _session]
                                     (is false "a refused operation must never run")
                                     op/exit-failure)}
                         overrides)))

(defn start-rig!
  "Start the fixture rig and wait until it owns the target."
  [directory & flags]
  (let [rig (process/process (into ["bb" "-m" "probetron.rig.fixture" (str directory)] flags)
                              {:out :stream :err :inherit})]
    (is (= "holding" (.readLine (io/reader (:out rig))))
        "the fixture rig must announce that it owns the target")
    rig))

(defn terminate!
  "Send SIGTERM to the fixture rig, as sshd does when a client disconnects."
  [rig]
  (signal! "-TERM" (.pid (:proc rig))))

(defn signal!
  "Send one signal to one process."
  [signal pid]
  (process/shell {:continue true :out :string :err :string} "/bin/kill" signal (str pid)))

(defn await-exit!
  "Wait for the fixture rig to finish its handled shutdown."
  [rig]
  (is (.waitFor (:proc rig) 15000 TimeUnit/MILLISECONDS)
      "a handled signal must end the fixture rig"))

(defn stop-rig!
  "Make sure no fixture process survives a test, even one that never cleaned up."
  [rig directory]
  (terminate! rig)
  (when-not (.waitFor (:proc rig) 5000 TimeUnit/MILLISECONDS)
    (signal! "-KILL" (.pid (:proc rig)))
    (.waitFor (:proc rig) 5000 TimeUnit/MILLISECONDS))
  (doseq [name ["helper.pid" "grandchild.pid"]]
    (when-let [pid (recorded-pid directory name)]
      (signal! "-KILL" pid))))

(defn run-status!
  "Run the status operation against a temporary target and return what it wrote."
  [directory format]
  (let [out (StringWriter.)]
    (is (= op/exit-ok (binding [*out* out]
                        (runner/execute! {:operation :status :format format} (rig-runtime directory {})))))
    (str out)))

(defn lock-state
  "Return the lock state that the status operation reports."
  [directory]
  (:lock (edn/read-string (run-status! directory :edn))))

(defn owner-record
  "Return the active metadata of a temporary target, or nil when there is none."
  [directory]
  (let [file (fs/file (:active (paths directory)))]
    (when (fs/exists? file) (edn/read-string (slurp file)))))

(defn recorded-pid
  "Return the pid that one fixture helper recorded."
  [directory name]
  (fixture/pid-of directory name))

(defn alive?
  "Tell whether a pid still runs."
  [pid]
  (stand-in/alive? pid))

(defn elapsed-ms
  "Return how many milliseconds passed since a nanosecond mark."
  [started]
  (quot (- (System/nanoTime) started) 1000000))
