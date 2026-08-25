(ns probetron.client.dap-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [probetron.client.tunnel-fixture :as fixture]
            [probetron.client.tunnel-test :as tunnel-test]
            [probetron.stand-in :as stand-in]
            [probetron.operation :as op])
  (:import (java.util.concurrent TimeUnit)))

(def debug-session
  {:operation :debug :host fixture/host :local-port nil :reset-on-exit? false})

(deftest the-debug-session-forwards-the-rig-dap-server-to-one-client-loopback-port
  (tunnel-test/with-session! (assoc debug-session :local-port 45678) {}
    (fn [{:keys [client out calls]}]
      (is (= (concat [(fixture/program client "ssh") "-i" (fixture/key-path client) "-T"]
                     tunnel-test/ssh-options
                     ["-L" "127.0.0.1:45678:127.0.0.1:50000"
                      "-o" "ExitOnForwardFailure=yes"
                      "probetron@pi.lab"
                      "sudo -n /usr/local/sbin/probetron-rig debug"])
             (fixture/recorded-argv client "ssh"))
          "the rig DAP server reaches exactly one client loopback port")
      (testing "the endpoint appears before an editor asks for it"
        (is (= "tcp://127.0.0.1:45678" (str/trim (str out)))))
      (testing "the client runs one program and sends the rig nothing"
        (is (= ["ssh"] (mapv :program @calls)))
        (is (zero? (count (fixture/recorded-stdin client))))))))

(deftest a-debug-session-without-a-requested-port-reserves-a-free-one
  (tunnel-test/with-session! debug-session {}
    (fn [{:keys [client out]}]
      (let [port (tunnel-test/endpoint-port (str out))]
        (is (some? port) "the client prints the endpoint of the forwarded server")
        (is (<= op/min-local-port port op/max-local-port))
        (is (some #{(str "127.0.0.1:" port ":127.0.0.1:50000")}
                  (fixture/recorded-argv client "ssh"))
            "the printed endpoint is the port that SSH forwards")))))

(deftest the-outer-command-lives-until-the-client-ends-it
  (tunnel-test/with-session! debug-session {}
    (fn [{:keys [client result]}]
      (is (stand-in/alive? (fixture/recorded-pid client "ssh"))
          "the outer command keeps the rig lock while an editor connects and disconnects")
      (is (= :pending (deref result 100 :pending)))))
  (testing "the status of the rig is the status of the client"
    (is (= op/exit-busy (:exit (tunnel-test/run-session! debug-session
                                                         {:answer {:exit op/exit-busy}}))))
    (is (= op/exit-ok (:exit (tunnel-test/run-session! debug-session {}))))))

(deftest a-debug-session-asks-the-rig-for-a-reset-only-when-the-client-did
  (testing "the default session leaves the target as the rig found it"
    (is (not (str/includes? (:remote-command (tunnel-test/run-session! debug-session {}))
                            "--reset-on-exit"))))
  (testing "--reset-on-exit travels to the rig and to nothing else"
    (let [{:keys [remote-command calls]}
          (tunnel-test/run-session! (assoc debug-session :reset-on-exit? true) {})]
      (is (= "sudo -n /usr/local/sbin/probetron-rig debug --reset-on-exit" remote-command))
      (is (= ["ssh"] (mapv :program calls)) "the client never runs a reset of its own"))))

(deftest a-handled-client-signal-ends-the-outer-debug-command
  (let [client (fixture/client! {})]
    (try
      (let [session (process/process ["bb" "-m" "probetron.client.tunnel-fixture"
                                      (:directory client) "debug"]
                                     {:out :string :err :string})
            ssh (fixture/await-pid! client "ssh")]
        (is (some? ssh) "the session reaches the rig before the signal arrives")
        (stand-in/signal! "-TERM" (.pid (:proc session)))
        (is (.waitFor ^Process (:proc session) 15000 TimeUnit/MILLISECONDS)
            "a handled signal must end the client")
        (is (not (stand-in/alive? ssh)) "cleanup must end the outer SSH process")
        (is (empty? (fs/list-dir (fixture/path client "run")))
            "and a debug session leaves nothing volatile behind"))
      (finally
        (fixture/stop-all! client)
        (fs/delete-tree (:directory client))))))
