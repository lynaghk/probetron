(ns probetron.spi-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [probetron.client.cli :as cli]
            [probetron.client.cli-test :as cli-test]
            [probetron.client.tunnel-fixture :as client-fixture]
            [probetron.client.tunnel-test :as tunnel-test]
            [probetron.operation :as op]
            [probetron.rig.config :as config]
            [probetron.rig.runner :as runner]
            [probetron.rig.session-test :as session-test]
            [probetron.rig.target-test :as target-test])
  (:import (java.io StringWriter)))

(declare parse-client parse-rig execute! status)

(deftest spi-selection-is-explicit-and-never-falls-back
  (doseq [[args env expected]
          [[[] {} 0]
           [[] {"PROBETRON_SPI" "1"} 1]
           [["--spi" "0"] {"PROBETRON_SPI" "1"} 0]
           [["--spi" "1"] {} 1]]]
    (let [public (parse-client (into ["debug" "--host" "pi"] args) env)
          remote (parse-rig (vec (rest (op/rig-command public))))]
      (is (= expected (get public :spi 0)))
      (is (= expected (get remote :spi 0)))))
  (testing "invalid selections stop at either entry point"
    (doseq [value ["-1" "2" "auto" "spi1"]]
      (is (= :error (:action (cli/parse ["debug" "--host" "pi" "--spi" value] {}))))
      (is (= :error (:action (config/parse ["debug" "--spi" value] {}))))))
  (testing "invalid environment values do not select another target"
    (is (= :error (:action (cli/parse ["debug" "--host" "pi"]
                                      {:env {"PROBETRON_SPI" "auto"}})))))
  (testing "the rig ignores client-side defaults"
    (is (= 0 (get (:operation (config/parse ["debug"] {:env {"PROBETRON_SPI" "1"}})) :spi 0))))
  (testing "serial channels cannot accidentally reach SPI0 while SPI1 is selected"
    (is (= :error (:action (cli/parse ["connect" "--host" "pi" "--channel" "usb"]
                                      {:env {"PROBETRON_SPI" "1"}}))))
    (is (= :error (:action (config/parse ["connect" "--channel" "uart" "--spi" "1"] {}))))))

(deftest spi1-debugging-forwards-the-second-debugger-port
  (let [client    (client-fixture/client! {})
        operation (parse-client ["debug" "--host" "pi.lab" "--spi" "1"] {})]
    (tunnel-test/with-session! operation {:client client}
      (fn [_]
        (is (= "sudo -n /usr/local/sbin/probetron-rig debug --spi 1"
               (client-fixture/remote-command client)))
        (is (some #(str/ends-with? % ":127.0.0.1:50001")
                  (client-fixture/recorded-argv client "ssh")))))))

(deftest flashing-spi1-does-not-conflict-with-an-active-spi0-session
  (let [directory (str (fs/create-temp-dir {:prefix "probetron-dual-spi"}))
        !calls    (atom [])
        entered   (promise)
        release   (promise)
        runtime   (-> (target-test/appliance! directory !calls {:stdin (target-test/elf)})
                      (assoc-in [:paths :dut-log] (str (fs/path directory "dut.log")))
                      (assoc-in [:filesystem :exists?]
                                (fn [path] (or (= "/dev/spidev1.0" path) (fs/exists? path))))
                      (assoc-in [:filesystem :readable?]
                                (fn [path] (or (= "/dev/spidev1.0" path) (fs/readable? path)))))
        hold      (future
                    (execute! {:operation :debug}
                              (assoc runtime :perform
                                     (fn [_ _]
                                       (deliver entered true)
                                       @release
                                       op/exit-ok))))]
    (try
      (is (= true (deref entered 5000 :timeout)))
      (let [reports (:connections (status runtime))]
        (is (= :held (get-in reports [0 :lock])))
        (is (= :free (get-in reports [1 :lock]))))
      (is (= op/exit-busy (:exit (execute! (parse-rig ["erase" "--spi" "0" "--chip" "RP235x"]) runtime))))
      (is (empty? @!calls) "a busy target opens no probe")
      (let [result   (execute! (parse-rig ["flash" "--spi" "1" "--chip" "RP235x"
                                           "--reset-method" "pin"]) runtime)
            download (first @!calls)
            reset    (second @!calls)]
        (is (= op/exit-ok (:exit result)))
        (is (str/includes? (:err result) "SPI1"))
        (is (= "0:0:/dev/spidev1.0" (nth download 3)))
        (is (= "16=0" (last reset)))
        (is (str/includes? (last download) "/uploads/spi1/"))
        (is (not (fs/exists? (last download))) "the upload is removed after flashing"))
      (is (= :held (:lock (status runtime 0))) "SPI1 cleanup leaves SPI0's owner intact")
      (is (= :free (:lock (status runtime 1))))
      (is (= :debug (get-in (status runtime 0) [:active :command])))
      (let [result (execute! {:operation :log :spi 1} runtime)]
        (is (str/includes? (:out result) ":operation :flash")))
      (is (not (str/includes? (:out (execute! {:operation :log :spi 0} runtime)) ":operation :flash")))
      (finally
        (deliver release true)
        (is (= op/exit-ok (:exit (deref hold 5000 {:exit :timeout}))))
        (fs/delete-tree directory)))))

(deftest missing-spi1-never-opens-spi0
  (let [directory (str (fs/create-temp-dir {:prefix "probetron-missing-spi"}))
        !calls    (atom [])]
    (try
      (let [runtime (-> (target-test/appliance! directory !calls {})
                        (assoc-in [:filesystem :exists?]
                                  (fn [path] (and (not= "/dev/spidev1.0" path) (fs/exists? path)))))
            result  (execute! (parse-rig ["erase" "--spi" "1" "--chip" "RP235x"]) runtime)]
        (is (= op/exit-unavailable (:exit result)))
        (is (str/includes? (:err result) "/dev/spidev1.0"))
        (is (empty? @!calls)))
      (finally (fs/delete-tree directory)))))

(deftest spi1-debug-announces-only-its-own-probe
  (let [operation (parse-rig ["debug" "--spi" "1" "--reset-on-exit"])
        result    (session-test/with-running-session!
                    operation
                    {:helper     "dap"
                     :filesystem {:glob (fn [pattern]
                                          (if (= "/dev/spidev_swd1" pattern)
                                            ["/dev/spidev_swd1"]
                                            ["/dev/spidev_swd0"]))}}
                    (fn [{:keys [calls]}]
                      (is (some #{"50001"} (:argv (session-test/helper-call @calls "dap"))))))]
    (is (= op/exit-ok (:status result)))
    (is (str/includes? (:out result) "probe: 0:0:/dev/spidev_swd1 swd"))
    (is (not (str/includes? (:out result) "spidev_swd0")))
    (is (:reset? result))))

(deftest spi1-run-loads-firmware-and-holds-its-rtt-session
  (let [operation (parse-rig ["run" "--spi" "1" "--chip" "RP235x"])
        result    (session-test/with-running-session!
                    operation
                    {:helper     "rtt"
                     :stdin      (target-test/elf)
                     :filesystem {:exists?   (fn [path]
                                               (or (= "/dev/spidev1.0" path) (fs/exists? path)))
                                  :readable? (fn [path]
                                               (or (= "/dev/spidev1.0" path) (fs/readable? path)))}}
                    (fn [{:keys [calls session]}]
                      (let [argv (:argv (session-test/helper-call @calls "rtt"))]
                        (is (= "run" (second argv)))
                        (is (= "0:0:/dev/spidev1.0" (nth argv 3)))
                        (is (str/includes? (last argv) "/uploads/spi1/"))
                        (is (= :pending (deref session 100 :pending))))))]
    (is (= op/exit-ok (:status result)))))

(defn parse-client
  [argv env]
  (let [result (cli/parse argv {:env env :elf-facts (cli-test/elf-facts {})})]
    (is (= :run (:action result)) (pr-str result))
    (:operation result)))

(defn parse-rig
  [argv]
  (let [result (config/parse argv {})]
    (is (= :run (:action result)) (pr-str result))
    (:operation result)))

(defn execute!
  [operation runtime]
  (let [out  (StringWriter.)
        err  (StringWriter.)
        exit (binding [*out* out *err* err] (runner/execute! operation runtime))]
    {:exit exit :out (str out) :err (str err)}))

(defn status
  ([runtime] (edn/read-string (:out (execute! {:operation :status :format :edn} runtime))))
  ([runtime spi] (edn/read-string (:out (execute! {:operation :status :spi spi :format :edn} runtime)))))
