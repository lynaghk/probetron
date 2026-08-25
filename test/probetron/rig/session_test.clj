(ns probetron.rig.session-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [probetron.rig.runner :as runner]
            [probetron.rig.session-fixture :as fixture]
            [probetron.rig.target-test :as target-test]
            [probetron.operation :as op])
  (:import (java.io StringWriter)
           (java.util.concurrent TimeUnit)))

(declare with-running-session! run-session! reset-log temporary-directory helper-call socat
         probe-rs device start-fixture! await-exit! elapsed-ms)

(def uart-operation
  {:operation :connect :channel :uart :baud 115200 :rtt nil :reset-on-exit? false})

(def usb-operation
  {:operation :connect :channel :usb :usb-wait-seconds 10 :rtt nil :reset-on-exit? false})

(def rtt
  {:chip "RP235x" :speed-khz 1000 :elf :stdin})

(deftest the-byte-service-listens-on-pi-loopback-alone-and-serves-one-client-at-a-time
  (with-running-session! uart-operation {}
    (fn [{:keys [calls directory]}]
      (let [{:keys [argv opts]} (helper-call @calls "bridge")]
        (is (= [(socat directory)
                "TCP-LISTEN:5555,bind=127.0.0.1,reuseaddr,fork,max-children=1"
                (str "FILE:" (device directory "ttyAMA0") ",raw,echo=0,b115200")]
               argv))
        (testing "the listener carries structured bytes on the service alone"
          (is (= {:out :inherit :err :inherit} opts))
          (is (not-any? #{"-" "STDIO" "STDIN"} argv)))))))

(deftest the-uart-channel-takes-the-requested-baud
  (with-running-session! (assoc uart-operation :baud 921600) {}
    (fn [{:keys [calls directory]}]
      (is (= (str "FILE:" (device directory "ttyAMA0") ",raw,echo=0,b921600")
             (last (:argv (helper-call @calls "bridge"))))))))

(deftest the-usb-channel-reopens-the-stable-device-path-for-every-client
  (with-running-session! usb-operation {}
    (fn [{:keys [calls directory]}]
      (let [argv (:argv (helper-call @calls "bridge"))]
        (is (= (str "FILE:" (device directory "probetron-dut") ",raw,echo=0") (last argv))
            "every accepted connection resolves the same stable path again")
        (is (str/includes? (second argv) "fork")
            "socat opens the channel again for every accepted connection")))))

(deftest the-usb-channel-waits-for-the-dut-to-enumerate
  (let [directory (temporary-directory)
        appearing (fs/path directory "probetron-dut")]
    (try
      (let [calls (atom [])
            runtime (fixture/appliance! directory calls {})]
        (fs/delete-if-exists appearing)
        (future (Thread/sleep 300) (fs/create-file appearing))
        (let [exit (future (runner/execute! (assoc usb-operation :usb-wait-seconds 10) runtime))]
          (is (some? (fixture/await-pid! directory "bridge"))
              "the bridge starts once the DUT enumerates")
          (is (= (str "FILE:" appearing ",raw,echo=0")
                 (last (:argv (helper-call @calls "bridge")))))
          (fixture/release! directory)
          (is (= op/exit-ok (deref exit 15000 :timeout)))))
      (finally (fixture/stop-all! directory) (fs/delete-tree directory)))))

(deftest the-usb-channel-gives-up-after-the-requested-interval
  (let [started (System/nanoTime)
        {:keys [exit err calls]} (run-session! (assoc usb-operation :usb-wait-seconds 1)
                                               {:remove ["probetron-dut"]})]
    (is (= op/exit-unavailable exit))
    (is (<= 1000 (elapsed-ms started)) "the session waits for the whole requested interval")
    (is (< (elapsed-ms started) 15000))
    (is (str/includes? err "probetron-dut"))
    (is (str/includes? err "USB"))
    (is (empty? calls) "a channel the rig cannot open starts no helper")))

(deftest a-missing-uart-device-names-itself-and-the-repair
  (let [{:keys [exit err calls]} (run-session! uart-operation {:remove ["ttyAMA0"]})]
    (is (= op/exit-unavailable exit))
    (is (str/includes? err "ttyAMA0"))
    (is (str/includes? err "header pins 8 and 10"))
    (is (empty? calls))))

(deftest a-missing-socat-names-itself-and-the-repair
  (let [{:keys [exit err calls]} (run-session! uart-operation {:remove ["socat"]})]
    (is (= op/exit-unavailable exit))
    (is (str/includes? err "socat"))
    (is (str/includes? err "rig image"))
    (is (empty? calls))))

(deftest an-unreadable-channel-device-names-itself-and-the-repair
  (let [{:keys [exit err calls]} (run-session! uart-operation
                                               {:filesystem {:readable? (constantly false)}})]
    (is (= op/exit-unavailable exit))
    (is (str/includes? err "ttyAMA0"))
    (is (str/includes? err "header pins 8 and 10"))
    (is (empty? calls))))

(deftest rtt-decodes-beside-the-byte-listener
  (with-running-session! (assoc uart-operation :rtt rtt) {:stdin (target-test/elf)}
    (fn [{:keys [calls directory]}]
      (let [bridge (helper-call @calls "bridge")
            decoder (helper-call @calls "rtt")]
        (is (= 2 (count @calls)) "the bridge and the decoder are two owned children")
        (is (fixture/alive? (fixture/await-pid! directory "bridge")))
        (is (fixture/alive? (fixture/await-pid! directory "rtt")))
        (testing "the decoder names the Linux SPI selector and the SWD protocol"
          (is (= [(probe-rs directory) "attach" "--probe" "0:0:/dev/spidev0.0"
                  "--chip" "RP235x" "--protocol" "swd" "--speed" "1000"]
                 (vec (butlast (:argv decoder)))))
          (is (str/starts-with? (last (:argv decoder))
                                (str (fs/path directory "uploads")))))
        (testing "RTT text reaches rig standard output and standard error alone"
          (is (= {:out :inherit :err :inherit} (:opts decoder))))
        (testing "the two channels name nothing of each other"
          (is (not-any? #(str/includes? % "probe-rs") (:argv bridge)))
          (is (not-any? #(str/includes? % "ttyAMA0") (:argv decoder)))
          (is (not-any? #(str/includes? % "TCP-LISTEN") (:argv decoder))))))))

(deftest an-rtt-decoder-that-stops-leaves-the-bridge-usable
  (with-running-session! (assoc uart-operation :rtt rtt) {:stdin (target-test/elf)}
    (fn [{:keys [directory session]}]
      (let [bridge (fixture/await-pid! directory "bridge")
            decoder (fixture/await-pid! directory "rtt")]
        (fixture/signal! "-KILL" decoder)
        (Thread/sleep 500)
        (is (not (fixture/alive? decoder)))
        (is (fixture/alive? bridge) "the byte bridge outlives its decoder")
        (is (= :pending (deref session 100 :pending))
            "and the session keeps the target until its listener ends")))))

(deftest rtt-refuses-a-malformed-elf-before-any-hardware-opens
  (let [{:keys [exit err calls uploads]}
        (run-session! (assoc uart-operation :rtt rtt) {:stdin (target-test/elf :machine 0x03)})]
    (is (= op/exit-failure exit))
    (is (str/includes? err "machine"))
    (is (empty? calls) "no helper starts before the upload validates")
    (is (empty? uploads) "a refused upload must not survive")))

(deftest the-rtt-upload-does-not-survive-the-session
  (let [{:keys [uploads status]}
        (with-running-session! (assoc uart-operation :rtt rtt) {:stdin (target-test/elf)}
          (fn [{:keys [calls directory]}]
            (is (str/starts-with? (last (:argv (helper-call @calls "rtt")))
                                  (str (fs/path directory "uploads"))))))]
    (is (= op/exit-ok status))
    (is (empty? uploads))))

(deftest a-session-exit-preserves-the-target-unless-reset-on-exit-asks-for-one
  (testing "the default exit leaves the DUT as it found it"
    (is (false? (:reset? (with-running-session! uart-operation {} (fn [_] nil))))))
  (testing "--reset-on-exit asks for one best-effort reset"
    (is (true? (:reset? (with-running-session! (assoc uart-operation :reset-on-exit? true)
                          {} (fn [_] nil)))))))

(deftest a-handled-signal-removes-both-child-groups-and-the-rtt-upload
  (let [directory (temporary-directory)]
    (try
      (fs/write-bytes (fs/file (fs/path directory "rtt.elf")) (target-test/elf))
      (let [rig (start-fixture! directory "connect")
            pids (into {} (map (fn [name] [name (fixture/await-pid! directory name)]))
                       (:connect fixture/helper-names))]
        (is (every? some? (vals pids)) "both children run before the signal arrives")
        (fixture/signal! "-TERM" (.pid (:proc rig)))
        (await-exit! rig)
        (is (not (str/includes? (:err @rig) "byte service"))
            "a handled signal must not accuse the byte service it reaped")
        (doseq [[name pid] pids]
          (is (not (fixture/alive? pid)) (str "cleanup must reap " name)))
        (is (empty? (fs/list-dir (fs/path directory "uploads")))
            "cleanup must remove the RTT upload")
        (is (not (fs/exists? (fs/path directory "active.edn")))
            "cleanup must drop the active metadata")
        (is (not (fs/exists? (fs/path directory "reset.log")))
            "a handled signal preserves the target by default"))
      (finally (fixture/stop-all! directory) (fs/delete-tree directory)))))

(defn with-running-session!
  "Start one long session, wait until its first helper runs, and stop it after the body.

   The helper option names the stand-in that a session must start before the
   body runs, and the result is what the session left behind once the outer
   operation ended."
  [operation options body]
  (let [directory (temporary-directory)
        helper (get options :helper "bridge")
        calls (atom [])
        out (StringWriter.)
        err (StringWriter.)
        runtime (fixture/appliance! directory calls options)
        session (binding [*out* out *err* err] (future (runner/execute! operation runtime)))]
    (try
      (is (some? (fixture/await-pid! directory helper))
          (str "the session must start its " helper))
      (body {:directory directory :calls calls :session session})
      (fixture/release! directory)
      {:status (deref session 15000 :timeout)
       :out (str out)
       :err (str err)
       :calls @calls
       :uploads (mapv str (fs/list-dir (fs/path directory "uploads")))
       :reset? (fs/exists? (fs/path directory "reset.log"))
       :reset-log (reset-log directory)}
      (finally
        (fixture/release! directory)
        (deref session 15000 :timeout)
        (fixture/stop-all! directory)
        (fs/delete-tree directory)))))

(defn run-session!
  "Run one connect session that ends on its own and return what it did.

   A session that keeps the target instead of refusing it fails here rather
   than holding the suite, so the release is an assertion of its own."
  [operation {:keys [remove] :as options}]
  (let [directory (temporary-directory)]
    (try
      (let [calls (atom [])
            out (StringWriter.)
            err (StringWriter.)
            runtime (fixture/appliance! directory calls options)]
        (doseq [name remove] (fs/delete-if-exists (fs/path directory name)))
        (let [session (binding [*out* out *err* err] (future (runner/execute! operation runtime)))
              exit (deref session 20000 :running)]
          (is (not= :running exit) "the session must end on its own")
          (fixture/release! directory)
          {:exit exit
           :out (str out)
           :err (str err)
           :calls @calls
           :uploads (mapv str (fs/list-dir (fs/path directory "uploads")))}))
      (finally
        (fixture/release! directory)
        (fixture/stop-all! directory)
        (fs/delete-tree directory)))))

(defn reset-log
  "Return what the optional reset on exit recorded, or nothing when it never ran."
  [directory]
  (let [file (fs/path directory "reset.log")]
    (if (fs/exists? file) (slurp (fs/file file)) "")))

(defn temporary-directory
  "Return a fresh directory that carries one temporary appliance."
  []
  (str (fs/create-temp-dir {:prefix "probetron-session"})))

(defn helper-call
  "Return the recorded spawn of one owned helper."
  [calls helper]
  (first (filter #(= helper (:helper %)) calls)))

(defn socat
  "Return the fake socat of a temporary appliance."
  [directory]
  (str (fs/path directory "socat")))

(defn probe-rs
  "Return the fake probe-rs of a temporary appliance."
  [directory]
  (str (fs/path directory "probe-rs")))

(defn device
  "Return one fake device of a temporary appliance."
  [directory name]
  (str (fs/path directory name)))

(defn start-fixture!
  "Start the fixture rig that holds one whole session in its own process."
  [directory command]
  (process/process ["bb" "-m" "probetron.rig.session-fixture" (str directory) command]
                   {:out :inherit :err :string}))

(defn await-exit!
  "Wait for the fixture rig to finish its handled shutdown."
  [rig]
  (is (.waitFor (:proc rig) 15000 TimeUnit/MILLISECONDS)
      "a handled signal must end the fixture rig"))

(defn elapsed-ms
  "Return how many milliseconds passed since a nanosecond mark."
  [started]
  (quot (- (System/nanoTime) started) 1000000))
