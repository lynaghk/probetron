(ns probetron.run-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [probetron.client.cli-test :as cli-test]
            [probetron.client.tunnel-fixture :as client-fixture]
            [probetron.client.tunnel-test :as tunnel-test]
            [probetron.operation :as op]
            [probetron.rig.config :as config]
            [probetron.rig.session-fixture :as rig-fixture]
            [probetron.rig.session-test :as session-test]
            [probetron.rig.target-test :as target-test]))

(defn ram-image
  []
  (let [image (target-test/elf)]
    (target-test/put32! image 24 0x20000001)
    (target-test/put32! image 64 0x20000000)
    image))

(deftest run-ram-defaults-to-12-mhz-and-honors-speed-overrides
  (doseq [[args env expected]
          [[[] {} 12000]
           [[] {"PROBETRON_SPEED_KHZ" "16000"} 16000]
           [["--speed-khz" "8000"] {"PROBETRON_SPEED_KHZ" "16000"} 8000]]]
    (let [client    (client-fixture/client! {})
          elf       (client-fixture/elf client)
          operation (cli-test/operation
                     (conj (into ["run-ram" "--host" "pi.lab" "--chip" "RP235x"] args) elf)
                     {:env env :elf-facts (cli-test/elf-facts {elf cli-test/readable-elf})})]
      (tunnel-test/with-session! operation {:client client}
        (fn [{:keys [out]}]
          (is (str/blank? (str out)))
          (is (= (str "sudo -n /usr/local/sbin/probetron-rig run-ram --chip RP235x --speed-khz " expected)
                 (client-fixture/remote-command client)))
          (is (= (vec (fs/read-all-bytes elf))
                 (vec (drop 4 (client-fixture/recorded-stdin client))))))))))

(deftest run-ram-verifies-and-executes-an-sram-image-at-12-mhz
  (let [operation (:operation (config/parse ["run-ram" "--chip" "RP235x"] {}))
        result    (session-test/with-running-session! operation {:stdin (ram-image) :helper "rtt"}
                    (fn [{:keys [directory calls session]}]
                      (is (= [(session-test/probe-rs directory) "run" "--probe" "0:0:/dev/spidev0.0"
                              "--protocol" "swd" "--chip" "RP235x" "--speed" "12000" "--verify"]
                             (vec (butlast (:argv (session-test/helper-call @calls "rtt"))))))
                      (rig-fixture/close-upload! directory)
                      (is (= op/exit-ok (deref session 15000 :timeout)))))]
    (is (= op/exit-ok (:status result)))
    (is (empty? (:uploads result)))
    (is (false? (:reset? result)))))

(deftest run-ram-refuses-flash-and-mixed-images-before-opening-the-probe
  (let [flash (target-test/elf)
        mixed (target-test/elf :segments [{:type 1 :offset 116 :filesz 8}
                                          {:type 1 :offset 116 :filesz 8}])]
    (target-test/put32! flash 24 0x10000001)
    (target-test/put32! flash 64 0x10000000)
    (target-test/put32! mixed 24 0x20000001)
    (target-test/put32! mixed 64 0x20000000)
    (target-test/put32! mixed 96 0x10000000)
    (doseq [image [flash mixed]]
      (let [operation (:operation (config/parse ["run-ram" "--chip" "RP235x"] {}))
            result    (session-test/run-session! operation {:stdin image})]
        (is (= op/exit-failure (:exit result)))
        (is (str/includes? (:err result) "SRAM"))
        (is (empty? (:calls result)))
        (is (empty? (:uploads result)))))))

(deftest run-streams-an-elf-without-opening-a-forwarded-port
  (let [client    (client-fixture/client! {})
        elf       (client-fixture/elf client)
        operation (cli-test/operation
                   ["run" "--host" "pi.lab" "--chip" "RP235x" "--speed-khz" "2000"
                    "--reset-on-exit" elf]
                   {:elf-facts (cli-test/elf-facts {elf cli-test/readable-elf})})]
    (tunnel-test/with-session! operation {:client client}
      (fn [{:keys [out]}]
        (is (str/blank? (str out)) "run publishes no TCP endpoint")
        (is (not-any? #{"-L"} (client-fixture/recorded-argv client "ssh")))
        (is (= "sudo -n /usr/local/sbin/probetron-rig run --chip RP235x --speed-khz 2000 --reset-on-exit"
               (client-fixture/remote-command client)))
        (let [captured (client-fixture/recorded-stdin client)
              length   (.getInt (java.nio.ByteBuffer/wrap captured))]
          (is (= length (- (alength captured) 4)))
          (is (= (vec (fs/read-all-bytes elf)) (vec (drop 4 captured)))))))))

(deftest run-loads-verifies-and-monitors-the-upload-until-the-client-leaves
  (let [operation (:operation (config/parse ["run" "--chip" "RP235x" "--speed-khz" "2000"] {}))
        image     (target-test/elf)
        result    (session-test/with-running-session! operation {:stdin image :helper "rtt"}
                    (fn [{:keys [directory calls session]}]
                      (let [argv (:argv (session-test/helper-call @calls "rtt"))
                            path (last argv)]
                        (is (= [(session-test/probe-rs directory) "run" "--probe" "0:0:/dev/spidev0.0"
                                "--protocol" "swd" "--chip" "RP235x" "--speed" "2000" "--verify"]
                               (vec (butlast argv))))
                        (is (= (vec image) (vec (fs/read-all-bytes path))))
                        (is (= :pending (deref session 100 :pending)))
                        (rig-fixture/close-upload! directory))))]
    (is (= op/exit-ok (:status result)))
    (is (empty? (:uploads result)))
    (is (false? (:reset? result)))))

(deftest run-refuses-a-malformed-upload-before-opening-the-probe
  (let [operation (:operation (config/parse ["run" "--chip" "RP235x"] {}))
        result    (session-test/run-session! operation {:stdin (byte-array [1 2 3])})]
    (is (= op/exit-failure (:exit result)))
    (is (str/includes? (:err result) "ELF"))
    (is (empty? (:calls result)))
    (is (empty? (:uploads result)))))
