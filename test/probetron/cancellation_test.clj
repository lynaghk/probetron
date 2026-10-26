(ns probetron.cancellation-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.test :refer [deftest is testing]]
            [probetron.rig.runner :as runner]
            [probetron.rig.session-fixture :as fixture]
            [probetron.rig.target-test :as target-test]
            [probetron.stand-in :as stand-in])
  (:import (java.io DataOutputStream OutputStream)
           (java.util.concurrent TimeUnit)))

(declare with-appliance! start! await-gone! assert-released!)

(deftest canceling-the-client-reaps-the-remote-flash-or-erase
  (doseq [command ["flash" "erase"]
          signal  ["-TERM" "-KILL"]]
    (testing (str command " after client " signal)
      (with-appliance! command
        (fn [directory]
          (let [client (start! "client" directory command)
                helper (fixture/await-pid! directory "rtt")
                child  (fixture/await-pid! directory "rtt-child")
                rig    (stand-in/await-pid! (fs/path directory "rig.pid"))]
            (try
              (is (some? helper) "the operation starts before cancellation")
              (is (some? child))
              (is (stand-in/alive? rig))
              (stand-in/signal! signal (.pid (:proc client)))
              (is (.waitFor ^Process (:proc client) 15000 TimeUnit/MILLISECONDS))
              (is (await-gone! rig) "EOF must end the remote command even after a hard client kill")
              (is (not (stand-in/alive? helper)))
              (is (not (stand-in/alive? child)) "the whole remote process group must be gone")
              (is (not (fs/exists? (fs/path directory "reset.log")))
                  "an interrupted download must not pulse RUN")
              (assert-released! directory)
              (finally
                (process/destroy-tree client)
                (when rig (stand-in/signal! "-KILL" rig))))))))))

(deftest disconnecting-before-the-helper-starts-leaves-no-orphan
  (doseq [command ["flash" "erase"]]
    (with-appliance! command
      (fn [directory]
        (let [rig (start! "rig" directory command)
              out (DataOutputStream. ^OutputStream (:in rig))]
          (try
            (when (= "flash" command)
              (let [bytes (target-test/elf)]
                (.writeInt out (alength ^bytes bytes))
                (.write out ^bytes bytes)))
            (.close out)
            (is (.waitFor ^Process (:proc rig) 15000 TimeUnit/MILLISECONDS))
            (doseq [name ["rtt" "rtt-child"]]
              (is (not (stand-in/alive? (fixture/recorded-pid directory name)))))
            (is (not (fs/exists? (fs/path directory "reset.log"))))
            (assert-released! directory)
            (finally (process/destroy-tree rig))))))))

(deftest a-truncated-flash-frame-never-opens-the-hardware
  (with-appliance! "flash"
    (fn [directory]
      (let [rig (start! "rig" directory "flash")
            out (DataOutputStream. ^OutputStream (:in rig))]
        (try
          (.writeInt out 128)
          (.write out (byte-array 16))
          (.close out)
          (is (.waitFor ^Process (:proc rig) 15000 TimeUnit/MILLISECONDS))
          (is (= 1 (:exit @rig)))
          (is (not (fs/exists? (fs/path directory "rtt.pid"))))
          (assert-released! directory)
          (finally (process/destroy-tree rig)))))))

(defn with-appliance!
  [command body]
  (let [directory (str (fs/create-temp-dir {:prefix "probetron-cancel"}))]
    (try
      (fs/write-bytes (fs/path directory "image.elf") (target-test/elf))
      (let [ssh (fs/path directory "ssh")]
        (spit (fs/file ssh)
              (str "#!/bin/sh\nexec bb -m probetron.cancellation-fixture rig " directory " " command "\n"))
        (fs/set-posix-file-permissions ssh "rwx------"))
      (body directory)
      (finally
        (fixture/stop-all! directory)
        (fs/delete-tree directory)))))

(defn start!
  [side directory command]
  (process/process ["bb" "-m" "probetron.cancellation-fixture" side directory command]
                   {:in :stream :out :string :err :string :throw false}))

(defn await-gone!
  [pid]
  (loop [attempts 1500]
    (if (and (stand-in/alive? pid) (pos? attempts))
      (do (Thread/sleep 10) (recur (dec attempts)))
      (not (stand-in/alive? pid)))))

(defn assert-released!
  [directory]
  (is (not (fs/exists? (fs/path directory "active.edn"))))
  (is (empty? (fs/list-dir (fs/path directory "uploads"))))
  (let [runtime (fixture/appliance! directory (atom []) {})]
    (is (= 0 (runner/execute! {:operation :reset}
                              (assoc runtime :perform (fn [_ _] 0))))
        "another operation can acquire the target lock")))
