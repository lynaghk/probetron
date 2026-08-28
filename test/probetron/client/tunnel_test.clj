(ns probetron.client.tunnel-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [probetron.client.tunnel-fixture :as fixture]
            [probetron.stand-in :as stand-in]
            [probetron.operation :as op])
  (:import (java.util.concurrent TimeUnit)))

(declare with-session! run-session! session-record endpoint-port pty-link ssh-options)

(def uart-connect
  {:operation      :connect
   :host           fixture/host
   :channel        :uart
   :baud           115200
   :local-port     nil
   :rtt            nil
   :pty?           false
   :reset-on-exit? false})

(deftest the-session-forwards-the-rig-byte-service-to-one-client-loopback-port
  (with-session! (assoc uart-connect :local-port 45678) {}
    (fn [{:keys [client out calls]}]
      (is (= (concat [(fixture/program client "ssh") "-i" (fixture/key-path client) "-T"]
                     ssh-options
                     ["-L" "127.0.0.1:45678:127.0.0.1:5555"
                      "-o" "ExitOnForwardFailure=yes"
                      "probetron@pi.lab"
                      "sudo -n /usr/local/sbin/probetron-rig connect --channel uart --baud 115200"])
             (fixture/recorded-argv client "ssh"))
          "the rig service reaches exactly one client loopback port")
      (testing "the endpoint appears before the session waits for a host program"
        (is (= "tcp://127.0.0.1:45678" (str/trim (str out)))))
      (testing "the client runs one program and never touches the target itself"
        (is (= ["ssh"] (mapv :program @calls)))))))

(deftest a-session-without-a-requested-port-reserves-a-free-one
  (with-session! uart-connect {}
    (fn [{:keys [client out]}]
      (let [port (endpoint-port (str out))]
        (is (some? port) "the client prints the endpoint of the forwarded service")
        (is (<= op/min-local-port port op/max-local-port))
        (is (some #{(str "127.0.0.1:" port ":127.0.0.1:5555")}
                  (fixture/recorded-argv client "ssh"))
            "the printed endpoint is the port that SSH forwards")))))

(deftest an-rtt-elf-travels-to-the-rig-on-standard-input
  (let [client (fixture/client! {})
        elf    (fixture/elf client)]
    (with-session! (assoc uart-connect :rtt {:elf elf :chip "RP235x" :speed-khz 2000})
      {:client client}
      (fn [{:keys [calls]}]
        (let [elf-bytes (vec (fs/read-all-bytes elf))
              captured  (vec (fixture/recorded-stdin client))]
          (is (= elf-bytes (subvec captured 4))
              "the rig reads the RTT ELF on standard input, after its length frame")
          (is (= (count elf-bytes)
                 (.getInt (java.nio.ByteBuffer/wrap (byte-array (subvec captured 0 4)))))
              "the ELF is framed by a four-byte big-endian length"))
        (is (= (str "sudo -n /usr/local/sbin/probetron-rig connect --channel uart --baud 115200"
                    " --rtt --chip RP235x --speed-khz 2000")
               (fixture/remote-command client)))
        (is (not-any? #(str/includes? % "rtt.elf") (fixture/recorded-argv client "ssh"))
            "no client path reaches the rig")
        (testing "RTT text and probe-rs diagnostics stay on the outer command output"
          (let [{:keys [opts]} (fixture/call-of @calls "ssh")]
            (is (= :inherit (:out opts)))
            (is (= :inherit (:err opts)))))))))

(deftest a-pseudo-terminal-bridges-the-forwarded-endpoint
  (with-session! (assoc uart-connect :local-port 45678 :pty? true) {}
    (fn [{:keys [client out calls]}]
      (let [link (pty-link (str out))]
        (is (some? link) "the client prints the pseudo-terminal path")
        (is (= [(fixture/program client "socat")
                (str "PTY,link=" link ",raw,echo=0")
                "TCP:127.0.0.1:45678,retry=30,interval=1"]
               (fixture/recorded-argv client "socat"))
            "the helper bridges the endpoint that the client printed")
        (is (str/includes? (str out) "tcp://127.0.0.1:45678")
            "and the TCP endpoint stays printed beside it")
        (is (str/starts-with? link (str (fixture/path client "run")))
            "the link lives in a private volatile directory of the client")
        (is (= "rwx------" (fs/posix->str (fs/posix-file-permissions (str (fs/parent link)))))
            "that nobody else may reach")
        (is (= ["ssh" "socat"] (mapv :program @calls))))))
  (testing "the private link directory does not survive the session"
    (is (empty? (:volatile (run-session! (assoc uart-connect :pty? true) {}))))))

(deftest a-client-without-socat-keeps-the-tcp-endpoint
  (with-session! (assoc uart-connect :local-port 45678 :pty? true) {:remove ["socat"]}
    (fn [{:keys [out err calls]}]
      (is (str/includes? (str err) "socat"))
      (is (str/includes? (str err) "install"))
      (is (str/includes? (str err) "--pty"))
      (is (= "tcp://127.0.0.1:45678" (str/trim (str out)))
          "the same session continues, and its TCP endpoint stays usable")
      (is (= ["ssh"] (mapv :program @calls))))))

(deftest a-lost-pseudo-terminal-leaves-the-session-alone
  (with-session! (assoc uart-connect :pty? true) {}
    (fn [{:keys [client err result]}]
      (stand-in/signal! "-KILL" (fixture/await-pid! client "socat"))
      (is (str/includes? (fixture/await-output! err "pseudo-terminal") "stays usable")
          "the client says that only the presentation is gone")
      (is (stand-in/alive? (fixture/recorded-pid client "ssh"))
          "the outer command keeps the target")
      (is (= :pending (deref result 100 :pending))))))

(deftest the-status-of-the-rig-is-the-status-of-the-client
  (is (= op/exit-busy (:exit (run-session! uart-connect {:answer {:exit op/exit-busy}}))))
  (is (= op/exit-ok (:exit (run-session! uart-connect {}))))
  (testing "an SSH invocation that never reached the rig explains itself"
    (let [{:keys [exit err]} (run-session! uart-connect {:answer {:exit 255}})]
      (is (= 255 exit))
      (is (str/includes? err "pi.lab"))
      (is (str/includes? err "SSH")))))

(deftest a-session-asks-the-rig-for-a-reset-only-when-the-client-did
  (testing "the default session leaves the target as the rig found it"
    (is (not (str/includes? (:remote-command (run-session! uart-connect {})) "--reset-on-exit"))))
  (testing "--reset-on-exit travels to the rig and to nothing else"
    (let [{:keys [remote-command calls]}
          (run-session! (assoc uart-connect :reset-on-exit? true) {})]
      (is (str/ends-with? remote-command "--reset-on-exit"))
      (is (= ["ssh"] (mapv :program calls)) "the client never runs a reset of its own"))))

(deftest a-handled-client-signal-stops-the-helpers-and-the-outer-command
  (let [client (fixture/client! {})]
    (try
      (let [session (process/process ["bb" "-m" "probetron.client.tunnel-fixture"
                                      (:directory client) "connect"]
                                     {:out :string :err :string})
            pids    (into {} (map (fn [name] [name (fixture/await-pid! client name)]))
                          fixture/programs)]
        (is (every? some? (vals pids)) "the session runs both helpers before the signal arrives")
        (stand-in/signal! "-TERM" (.pid (:proc session)))
        (is (.waitFor ^Process (:proc session) 15000 TimeUnit/MILLISECONDS)
            "a handled signal must end the client")
        (doseq [[name pid] pids]
          (is (not (stand-in/alive? pid)) (str "cleanup must stop " name)))
        (is (empty? (fs/list-dir (fixture/path client "run")))
            "cleanup must remove the private link directory"))
      (finally
        (fixture/stop-all! client)
        (fs/delete-tree (:directory client))))))

(defn with-session!
  "Open one client session, wait until it reached the rig, and end it after the body.

   It returns what the session left behind once the outer command ended."
  [operation {:keys [answer] :as options} body]
  (let [client (or (:client options) (fixture/client! options))]
    (try
      (fixture/answer! client (or answer {}))
      (let [session (fixture/open! client operation)]
        (is (some? (fixture/await-pid! client "ssh")) "the session must reach the rig")
        (body (assoc session :client client))
        (fixture/release! client)
        (session-record client session (deref (:result session) 15000 :timeout)))
      (finally
        (fixture/stop-all! client)
        (fs/delete-tree (:directory client))))))

(defn run-session!
  "Open one client session, end it at once, and return everything it left behind."
  [operation options]
  (with-session! operation options (fn [_] nil)))

(defn session-record
  "Return everything one finished session said, ran, and left behind."
  [client session exit]
  {:exit           exit
   :out            (str (:out session))
   :err            (str (:err session))
   :calls          @(:calls session)
   :remote-command (fixture/remote-command client)
   :volatile       (mapv str (fs/list-dir (fixture/path client "run")))})

(def ssh-options
  "The options that every Probetron SSH invocation carries."
  ["-o" "BatchMode=yes"
   "-o" "IdentitiesOnly=yes"
   "-o" "StrictHostKeyChecking=no"
   "-o" "UserKnownHostsFile=/dev/null"
   "-o" "GlobalKnownHostsFile=/dev/null"
   "-o" "LogLevel=ERROR"])

(defn endpoint-port
  "Return the port of the TCP endpoint that a client printed."
  [text]
  (some-> (re-find #"(?m)^tcp://127\.0\.0\.1:(\d+)$" text) second parse-long))

(defn pty-link
  "Return the pseudo-terminal path that a client printed, or nil when it printed none."
  [text]
  (some-> (re-find #"(?m)^(/\S+/tty)$" text) second))
