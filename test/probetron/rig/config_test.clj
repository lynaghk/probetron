(ns probetron.rig.config-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [probetron.rig.config :as rig]
            [probetron.operation :as operation]))

(defn parse
  ([argv] (parse argv {}))
  ([argv ctx] (rig/parse argv ctx)))

(defn operation
  [argv & [ctx]]
  (let [result (parse argv (or ctx {}))]
    (is (= :run (:action result)) (pr-str result))
    (:operation result)))

(defn usage-error
  [argv & [ctx]]
  (let [result (parse argv (or ctx {}))]
    (is (= :error (:action result)) (pr-str result))
    (is (= operation/exit-usage (:exit result)))
    (:message result)))

(deftest rig-help
  (let [result (parse [])]
    (is (= :help (:action result)))
    (is (= operation/exit-usage (:exit result)))
    (is (str/includes? (:text result) "probetron-rig connect")))
  (is (= :help (:action (parse ["--help"]))))
  (is (= operation/exit-ok (:exit (parse ["--help"])))))

(deftest the-rig-entry-point-carries-no-shell-of-its-own
  (testing "a shell belongs to the client alone, because the rig is already the login"
    (is (str/includes? (usage-error ["shell"]) "unknown command")))
  (testing "and the rig help never offers one"
    (is (not (str/includes? (:text (parse [])) "probetron-rig shell")))))

(deftest rig-information
  (is (= {:operation :info :format :text :speed-khz 1000} (operation ["info"])))
  (is (= {:operation :info :format :edn :speed-khz 1000} (operation ["info" "--format" "edn"])))
  (is (= 50 (:speed-khz (operation ["info" "--speed-khz" "50"]))))
  (is (= {:operation :status :format :text} (operation ["status"])))
  (is (str/includes? (usage-error ["status" "--format" "toml"]) "--format"))
  (is (= {:operation :log} (operation ["log"])) "the rig prints its DUT record"))

(deftest rig-flash-reads-the-elf-from-standard-input
  (is (= {:operation :flash :chip "RP2350" :speed-khz 1000 :elf :stdin}
         (operation ["flash" "--chip" "RP2350"])))
  (is (= 4000 (:speed-khz (operation ["flash" "--chip" "RP2350" "--speed-khz" "4000"]))))
  (testing "no client path reaches the rig"
    (is (str/includes? (usage-error ["flash" "--chip" "RP2350" "firmware.elf"]) "firmware.elf")))
  (is (str/includes? (usage-error ["flash"]) "--chip")))

(deftest rig-erase-and-reset
  (is (= {:operation :erase :chip "RP2350" :speed-khz 1000} (operation ["erase" "--chip" "RP2350"])))
  (is (= {:operation :reset} (operation ["reset"])))
  (is (str/includes? (usage-error ["reset" "--chip" "RP2350"]) "--chip")))

(deftest rig-connect
  (is (= {:operation :connect :channel :usb :usb-wait-seconds 10 :rtt nil :reset-on-exit? false}
         (operation ["connect" "--channel" "usb"])))
  (is (= {:operation :connect :channel :uart :baud 115200 :rtt nil :reset-on-exit? false}
         (operation ["connect" "--channel" "uart"])))
  (is (= 9600 (:baud (operation ["connect" "--channel" "uart" "--baud" "9600"]))))
  (is (= {:chip "RP2350" :speed-khz 2000 :elf :stdin}
         (:rtt (operation ["connect" "--channel" "usb" "--rtt" "--chip" "RP2350" "--speed-khz" "2000"]))))
  (is (true? (:reset-on-exit? (operation ["connect" "--channel" "usb" "--reset-on-exit"]))))
  (testing "rtt options need rtt"
    (is (str/includes? (usage-error ["connect" "--channel" "usb" "--chip" "RP2350"]) "--rtt")))
  (testing "rtt needs a chip"
    (is (str/includes? (usage-error ["connect" "--channel" "usb" "--rtt"]) "--chip")))
  (is (str/includes? (usage-error ["connect" "--channel" "usb" "--baud" "9600"]) "--baud"))
  (is (str/includes? (usage-error ["connect"]) "--channel")))

(deftest rig-debug
  (is (= {:operation :debug :reset-on-exit? false} (operation ["debug"])))
  (is (= {:operation :debug :reset-on-exit? true} (operation ["debug" "--reset-on-exit"]))))

(deftest client-only-options-stop-at-the-rig-boundary
  (doseq [argv [["info" "--host" "pi"]
                ["debug" "--local-port" "3333"]
                ["connect" "--channel" "usb" "--pty"]]]
    (let [message (usage-error argv)]
      (is (str/includes? message "client")))))

(deftest the-rig-ignores-client-environment-defaults
  (let [env {"PROBETRON_CHIP" "RP2350" "PROBETRON_SPEED_KHZ" "2000" "PROBETRON_UART_BAUD" "9600"}]
    (is (str/includes? (usage-error ["flash"] {:env env}) "--chip"))
    (is (= 1000 (:speed-khz (operation ["erase" "--chip" "RP2040"] {:env env}))))
    (is (= 115200 (:baud (operation ["connect" "--channel" "uart"] {:env env}))))))

(deftest rig-rejects-unknown-commands-and-options
  (is (str/includes? (usage-error ["fry"]) "fry"))
  (is (str/includes? (usage-error ["info" "--verbose"]) "--verbose")))
