(ns probetron.client.session-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [probetron.client.session :as session]
            [probetron.client.shell :as shell]
            [probetron.operation :as op]
            [probetron.version :as version])
  (:import (java.io StringWriter)))

(declare with-client! run-client! run-shell! fake-ssh! recorded-argv recorded-stdin key-path
         rig-key elf-file rig-text rig-edn)

(def rig-text
  (str "probetron: 0.1.0\n"
       "babashka: 1.13.219\n"
       "probe-rs: probe-rs 0.32.0\n"
       "os: Debian GNU/Linux 13 (trixie)\n"
       "hostname: probetron-01\n"
       "machine-id: 0123456789abcdef0123456789abcdef\n"
       "probe: 0:0:/dev/spidev0.0 swd\n"))

(def rig-edn
  (pr-str {:probetron  "0.1.0"
           :babashka   "1.13.219"
           :probe-rs   "probe-rs 0.32.0"
           :os         "Debian GNU/Linux 13 (trixie)"
           :hostname   "probetron-01"
           :machine-id "0123456789abcdef0123456789abcdef"
           :probe      {:selector "0:0:/dev/spidev0.0" :protocol "swd"}
           :target     "RP2350"}))

(deftest a-short-operation-reaches-the-rig-through-one-quoted-remote-command
  (with-client! {}
    (fn [client]
      (let [{:keys [exit calls]} (run-client! client {:operation :reset :host "pi.lab"})]
        (is (= op/exit-ok exit))
        (is (= [(:ssh client) "-i" (key-path client "pi.lab") "-T"
                "-o" "BatchMode=yes"
                "-o" "IdentitiesOnly=yes"
                "-o" "StrictHostKeyChecking=no"
                "-o" "UserKnownHostsFile=/dev/null"
                "-o" "GlobalKnownHostsFile=/dev/null"
                "-o" "LogLevel=ERROR"
                "-o" "ControlMaster=auto"
                "-o" "ControlPath=/tmp/probetron-mux-%i-%C"
                "-o" "ControlPersist=30"
                "probetron@pi.lab"
                "sudo -n /usr/local/sbin/probetron-rig reset"]
               (recorded-argv client))
            "the rig sees exactly the argv the client built")
        (testing "the remote bytes travel untouched between the rig and the client"
          (is (= :inherit (:out (:opts (first @calls)))))
          (is (= :inherit (:err (:opts (first @calls))))))
        (testing "no operation leaves a persistent host key behind"
          (is (empty? (fs/glob (:home client) "**/known_hosts"))))))))

(deftest erase-carries-the-target-values-that-the-rig-needs
  (with-client! {}
    (fn [client]
      (run-client! client {:operation :erase :host "pi.lab" :chip "RP235x" :speed-khz 2000})
      (is (= "sudo -n /usr/local/sbin/probetron-rig erase --chip RP235x --speed-khz 2000"
             (last (recorded-argv client)))))))

(deftest flash-streams-the-elf-to-the-rig-without-naming-a-client-path
  (with-client! {}
    (fn [client]
      (let [elf   (elf-file client)
            bytes (fs/read-all-bytes elf)]
        (run-client! client {:operation :flash :host "pi.lab" :chip "RP235x"
                             :speed-khz 4000   :elf  elf})
        (is (= (seq bytes) (seq (recorded-stdin client))) "the rig reads the ELF on standard input")
        (is (= "sudo -n /usr/local/sbin/probetron-rig flash --chip RP235x --speed-khz 4000"
               (last (recorded-argv client))))
        (is (not-any? #(str/includes? % (str (fs/file-name elf))) (recorded-argv client)))))))

(deftest a-relayed-operation-frames-the-rig-output-with-its-command-and-outcome
  (with-client! {}
    (fn [client]
      (let [{:keys [err]} (run-client! client {:operation :reset :host "pi.lab"})]
        (is (str/includes? err "probetron rig running: sudo -n /usr/local/sbin/probetron-rig reset")
            "the command banner names where the rig output begins")
        (is (str/includes? err "probetron rig reset finished")
            "the outcome line names where the rig output ends")))))

(deftest a-failed-relayed-operation-names-the-rig-and-the-failing-status
  (with-client! {:exit op/exit-failure
                 :err  "Error: An error with the flashing procedure has occurred.\n"}
    (fn [client]
      (let [elf                      (elf-file client)
            {:keys [exit err calls]} (run-client! client {:operation :flash   :host      "pi.lab"
                                                          :chip      "RP235x" :speed-khz 4000     :elf elf})]
        (is (= op/exit-failure exit))
        (is (str/includes? (:err (:result (first @calls))) "Error: An error with the flashing procedure")
            "the rig probe-rs diagnostic reaches the client untouched")
        (is (str/includes? err "probetron rig flash failed with exit 1")
            "the outcome line marks the failure as the rig's, not the client's")))))

(deftest the-rig-status-and-diagnostics-reach-the-client
  (with-client! {:exit op/exit-busy :err "probetron-rig: the target is busy with connect\n"}
    (fn [client]
      (let [{:keys [exit calls]} (run-client! client {:operation :reset :host "pi.lab"})]
        (is (= op/exit-busy exit) "the busy status of the rig is the status of the client")
        (is (str/includes? (:err (:result (first @calls))) "busy"))))))

(deftest a-lost-connection-explains-itself
  (with-client! {:exit 255 :err "ssh: connect to host pi.lab port 22: No route to host\n"}
    (fn [client]
      (let [{:keys [exit err]} (run-client! client {:operation :reset :host "pi.lab"})]
        (is (= 255 exit))
        (is (str/includes? err "pi.lab"))
        (is (str/includes? err "SSH"))))))

(deftest a-failed-key-refresh-stops-the-operation-before-ssh
  (with-client! {:key-status 503}
    (fn [client]
      (let [{:keys [exit err]} (run-client! client {:operation :reset :host "pi.lab"})]
        (is (= op/exit-failure exit))
        (is (str/includes? err "http://pi.lab/probetron_key"))
        (is (nil? (recorded-argv client)) "no SSH invocation follows a failed refresh")))))

(deftest every-operation-refreshes-the-key
  (with-client! {}
    (fn [client]
      (run-client! client {:operation :reset :host "pi.lab"})
      (run-client! client {:operation :reset :host "pi.lab"})
      (is (= 2 (count @(:requested client)))))))

(deftest information-joins-the-client-facts-to-the-rig-report
  (with-client! {:out rig-text}
    (fn [client]
      (let [{:keys [exit out]} (run-client! client {:operation :info :host "pi.lab" :format :text :speed-khz 20})]
        (is (= op/exit-ok exit))
        (is (str/includes? out (str "client probetron: " version/probetron-version)))
        (is (str/includes? out (str "client babashka: " (System/getProperty "babashka.version"))))
        (is (str/includes? out (str "client key: " (key-path client "pi.lab"))))
        (is (str/includes? out "probe-rs: probe-rs 0.32.0"))
        (is (str/includes? out "hostname: probetron-01"))
        (is (str/includes? out "machine-id: 0123456789abcdef0123456789abcdef"))
        (is (= "sudo -n /usr/local/sbin/probetron-rig info --speed-khz 20 --format text"
               (last (recorded-argv client))))))))

(deftest information-in-edn-carries-both-sides-under-stable-keys
  (with-client! {:out rig-edn}
    (fn [client]
      (let [{:keys [exit out]} (run-client! client {:operation :info :host "pi.lab" :format :edn :speed-khz 20})
            report             (edn/read-string out)]
        (is (= op/exit-ok exit))
        (is (= #{:client :rig} (set (keys report))))
        (is (= version/probetron-version (:probetron (:client report))))
        (is (= (System/getProperty "babashka.version") (:babashka (:client report))))
        (is (= (key-path client "pi.lab") (:key (:client report))))
        (is (= "1.13.219" (:babashka (:rig report))))
        (is (= "probe-rs 0.32.0" (:probe-rs (:rig report))))
        (is (= "Debian GNU/Linux 13 (trixie)" (:os (:rig report))))
        (is (= "probetron-01" (:hostname (:rig report))))
        (is (= "0123456789abcdef0123456789abcdef" (:machine-id (:rig report))))
        (is (= "sudo -n /usr/local/sbin/probetron-rig info --speed-khz 20 --format edn"
               (last (recorded-argv client))))))))

(deftest information-that-the-rig-cannot-give-fails-and-keeps-what-it-said
  (with-client! {:out "Error: no debug probe found\n" :exit op/exit-failure}
    (fn [client]
      (let [{:keys [exit out]} (run-client! client {:operation :info :host "pi.lab" :format :edn})]
        (is (= op/exit-failure exit))
        (is (str/includes? out "no debug probe found"))))))

(deftest a-usb-console-shell-is-one-interactive-login-and-no-remote-command
  (with-client! {}
    (fn [client]
      (let [{:keys [exit calls]} (run-shell! client {:operation :shell
                                                     :host      op/usb-console-address})]
        (is (= op/exit-ok exit))
        (is (= [(:ssh client) "-i" (key-path client op/usb-console-address) "-t"
                "-o" "BatchMode=yes"
                "-o" "IdentitiesOnly=yes"
                "-o" "StrictHostKeyChecking=no"
                "-o" "UserKnownHostsFile=/dev/null"
                "-o" "GlobalKnownHostsFile=/dev/null"
                "-o" "LogLevel=ERROR"
                (str "probetron@" op/usb-console-address)]
               (recorded-argv client))
            "the login asks for a terminal and carries no remote command")
        (testing "the operator owns every stream of the session"
          (let [opts (:opts (first @calls))]
            (is (= [:inherit :inherit :inherit] [(:in opts) (:out opts) (:err opts)]))))
        (testing "the shell fetches the key of that rig like every other operation"
          (is (= [(str "http://" op/usb-console-address "/probetron_key")]
                 @(:requested client))))))))

(deftest a-usb-console-shell-that-cannot-fetch-a-key-runs-nothing
  (with-client! {:key-status 404}
    (fn [client]
      (let [{:keys [exit err]} (run-shell! client {:operation :shell
                                                   :host      op/usb-console-address})]
        (is (= op/exit-failure exit))
        (is (str/includes? err "probetron:"))
        (is (nil? (recorded-argv client)) "no SSH invocation follows a failed key fetch")))))

(defn with-client!
  "Give the body a temporary client home, a fake rig key, and a fake SSH executable."
  [{:keys [key-status] :as answers} body]
  (let [home      (str (fs/create-temp-dir {:prefix "probetron-client"}))
        requested (atom [])]
    (try
      (body {:home      home
             :ssh       (fake-ssh! home answers)
             :requested requested
             :fetch!    (fn [url]
                          (swap! requested conj url)
                          (if key-status
                            {:error (str "HTTP status " key-status)}
                            {:body (.getBytes ^String rig-key)}))})
      (finally (fs/delete-tree home)))))

(def rig-key
  "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAA\n-----END OPENSSH PRIVATE KEY-----\n")

(defn fake-ssh!
  "Write an SSH stand-in that records its argv and standard input and answers as asked."
  [home {:keys [exit out err]}]
  (let [path (str (fs/path home "ssh"))]
    (spit (str (fs/path home "rig-out")) (or out ""))
    (spit (str (fs/path home "rig-err")) (or err ""))
    (spit path (str "#!/bin/sh\n"
                    "printf '%s\\0' \"$0\" \"$@\" > " home "/argv\n"
                    "cat > " home "/stdin\n"
                    "cat " home "/rig-out\n"
                    "cat " home "/rig-err >&2\n"
                    "exit " (or exit 0) "\n"))
    (fs/set-posix-file-permissions path "rwx------")
    path))

(defn run-client!
  "Run one client operation against the fake rig and return everything it wrote."
  [client operation]
  (let [out     (StringWriter.)
        err     (StringWriter.)
        calls   (atom [])
        runtime (session/runtime
                 {:env         {"XDG_CACHE_HOME" (:home client)}
                  :fetch!      (:fetch! client)
                  :executables {:ssh (:ssh client)}
                  :run!        (fn [argv opts]
                                 (let [result @(process/process argv (merge {:throw false} opts
                                                                            {:out :string :err :string}))]
                                   (swap! calls conj {:argv (vec argv) :opts opts :result result})
                                   result))})
        exit    (binding [*out* out *err* err] (session/execute! operation runtime))]
    {:exit exit :out (str out) :err (str err) :calls calls}))

(defn run-shell!
  "Open one interactive shell against the fake rig and return everything it wrote.

   The fake SSH executable reads standard input, so the recorded call keeps the
   streams the client asked for while the run itself takes none of the terminal."
  [client operation]
  (let [out     (StringWriter.)
        err     (StringWriter.)
        calls   (atom [])
        runtime (session/runtime
                 {:env         {"XDG_CACHE_HOME" (:home client)}
                  :fetch!      (:fetch! client)
                  :executables {:ssh (:ssh client)}
                  :run!        (fn [argv opts]
                                 (let [result @(process/process argv (merge {:throw false} opts
                                                                            {:in "" :out :string :err :string}))]
                                   (swap! calls conj {:argv (vec argv) :opts opts :result result})
                                   result))})
        exit    (binding [*out* out *err* err] (shell/open! operation runtime))]
    {:exit exit :out (str out) :err (str err) :calls calls}))

(defn recorded-argv
  "Return the argv that the fake SSH executable received, or nil when it never ran."
  [client]
  (let [path (fs/path (:home client) "argv")]
    (when (fs/exists? path)
      (vec (butlast (str/split (String. (fs/read-all-bytes path)) #"\x00" -1))))))

(defn recorded-stdin
  "Return the bytes that the fake SSH executable read from standard input."
  [client]
  (fs/read-all-bytes (fs/path (:home client) "stdin")))

(defn key-path
  "Return the cache file that carries the key of one host."
  [client host]
  (str (:home client) "/probetron/keys/" host ".key"))

(defn elf-file
  "Write a small file that stands in for one firmware image."
  [client]
  (let [path (str (fs/path (:home client) "firmware.elf"))]
    (fs/write-bytes path (byte-array (map unchecked-byte (range 0 200))))
    path))
