(ns probetron.rig.dap-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [probetron.rig.session-fixture :as fixture]
            [probetron.rig.session-test :as session-test]
            [probetron.stand-in :as stand-in]
            [probetron.operation :as op])
  (:import (java.net InetAddress Socket)
           (java.util.concurrent TimeUnit)))

(declare visit-dap! await-sessions! active-owner)

(def debug-operation
  {:operation :debug :reset-on-exit? false})

(def dap-session
  "The options of a debug session, whose first owned helper is the DAP server."
  {:helper "dap"})

(deftest the-dap-server-listens-on-pi-loopback-alone-in-multi-session-mode
  (session-test/with-running-session! debug-operation dap-session
    (fn [{:keys [calls directory]}]
      (let [{:keys [argv opts]} (session-test/helper-call @calls "dap")]
        (is (= [(session-test/probe-rs directory) "dap-server" "--port" "50000" "--ip" "127.0.0.1"]
               argv))
        (testing "the server stays in multi-session mode, which probe-rs takes as its default"
          (is (not-any? #{"--single-session" "--vscode"} argv)))
        (testing "chip, speed, ELF, SVD, and source stay in the DAP client request"
          (is (not-any? #{"--chip" "--speed" "--protocol" "--probe"} argv))
          (is (= 1 (count @calls)) "the server is the one owned child of the session"))
        (testing "probe-rs diagnostics reach rig standard output and standard error alone"
          (is (= {:out :inherit :err :inherit} opts)))))))

(deftest the-dap-session-names-the-discovered-swd-bus-and-no-other-spi-bus
  (let [{:keys [out calls]} (session-test/with-running-session! debug-operation dap-session
                              (fn [_] nil))
        argv (:argv (session-test/helper-call calls "dap"))]
    (is (re-find #"(?m)^probe: 0:0:\S+/spidev_swd0 swd$" out)
        "the session names the probe selector that a DAP client request repeats")
    (is (not-any? #(str/includes? % "spidev") argv)
        "and the server argv exposes no SPI bus at all")))

(deftest a-rig-without-the-swd-alias-names-it-and-the-repair
  (let [{:keys [exit err calls]} (session-test/run-session! debug-operation
                                                            {:remove ["spidev_swd0"]})]
    (is (= op/exit-unavailable exit))
    (is (str/includes? err "spidev_swd"))
    (is (str/includes? err "udev"))
    (is (empty? calls) "a rig without its SWD bus starts no server")))

(deftest a-dap-client-that-disconnects-leaves-the-session-holding-the-target
  (session-test/with-running-session! debug-operation dap-session
    (fn [{:keys [directory session]}]
      (let [server (fixture/await-pid! directory "dap")
            port (parse-long (fixture/await-file! directory "dap.port"))]
        (visit-dap! port)
        (await-sessions! directory 1)
        (is (stand-in/alive? server) "the DAP server outlives the client that left")
        (is (= :pending (deref session 100 :pending)))
        (is (= :debug (:command (active-owner directory)))
            "and the outer debug command still owns the target lock")
        (testing "an IDE connects again to the same server and the same target"
          (visit-dap! port)
          (await-sessions! directory 2)
          (is (stand-in/alive? server))
          (is (= :pending (deref session 100 :pending))))))))

(deftest a-debug-session-preserves-the-target-unless-reset-on-exit-asks-for-one
  (testing "the default exit leaves the DUT as it found it"
    (let [{:keys [status reset?]} (session-test/with-running-session! debug-operation dap-session
                                    (fn [_] nil))]
      (is (= op/exit-ok status))
      (is (false? reset?))))
  (testing "--reset-on-exit resets once, after the whole DAP process group stopped"
    (let [{:keys [reset-log]} (session-test/with-running-session!
                                (assoc debug-operation :reset-on-exit? true) dap-session
                                (fn [_] nil))]
      (is (= 1 (count (str/split-lines (str/trim reset-log)))))
      (is (str/includes? reset-log "dap=gone"))
      (is (str/includes? reset-log "dap-child=gone")))))

(deftest a-handled-signal-removes-the-dap-process-group-and-releases-the-lock
  (let [directory (session-test/temporary-directory)]
    (try
      (let [rig (session-test/start-fixture! directory "debug")
            pids (into {} (map (fn [name] [name (fixture/await-pid! directory name)]))
                       (:debug fixture/helper-names))]
        (is (every? some? (vals pids)) "the server runs before the signal arrives")
        (stand-in/signal! "-TERM" (.pid (:proc rig)))
        (is (.waitFor ^Process (:proc rig) 15000 TimeUnit/MILLISECONDS)
            "a handled signal must end the fixture rig")
        (is (not (str/includes? (:err @rig) "DAP service"))
            "a handled signal must not accuse the server it reaped")
        (doseq [[name pid] pids]
          (is (not (stand-in/alive? pid)) (str "cleanup must reap " name)))
        (is (not (fs/exists? (fs/path directory "active.edn")))
            "cleanup must drop the active metadata")
        (is (not (fs/exists? (fs/path directory "reset.log")))
            "a handled signal preserves the target by default"))
      (finally (fixture/stop-all! directory) (fs/delete-tree directory)))))

(defn visit-dap!
  "Connect to the rig DAP service as one client would, and then leave."
  [port]
  (with-open [socket (Socket. (InetAddress/getByName "127.0.0.1") (int port))]
    (.write (.getOutputStream socket) (.getBytes "{}" "UTF-8"))
    (.flush (.getOutputStream socket))))

(defn await-sessions!
  "Wait until the DAP server has served the expected number of clients."
  [directory expected]
  (loop [attempts 500]
    (let [text (or (fixture/await-file! directory "dap.sessions") "")
          served (count (remove str/blank? (str/split-lines text)))]
      (if (or (<= expected served) (zero? attempts))
        (is (<= expected served) (str "the DAP server must serve " expected " clients"))
        (do (Thread/sleep 10)
            (recur (dec attempts)))))))

(defn active-owner
  "Return the record that names the owner of the target lock."
  [directory]
  (let [file (fs/path directory "active.edn")]
    (when (fs/exists? file) (edn/read-string (slurp (fs/file file))))))
