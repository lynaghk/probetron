(ns probetron.operation-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [probetron.rig.config :as rig]
            [probetron.operation :as operation]
            [probetron.version :as version]))

(def flash-operation
  {:operation :flash :host "pi" :chip "RP2350" :speed-khz 1000 :elf "firmware.elf"})

(def connect-operation
  {:operation :connect
   :host "pi"
   :channel :usb
   :usb-wait-seconds 10
   :local-port 4444
   :rtt nil
   :pty? true
   :reset-on-exit? false})

(deftest exit-statuses
  (is (= 0 operation/exit-ok))
  (is (= 64 operation/exit-usage))
  (is (= 75 operation/exit-busy)))

(deftest defaults
  (is (= 20 operation/default-speed-khz))
  (is (= 115200 operation/default-baud))
  (is (= 10 operation/default-usb-wait-seconds))
  (is (= 60 operation/max-usb-wait-seconds)))

(deftest version-matches-the-version-file
  (is (= (str/trim (slurp "VERSION")) version/probetron-version)))

(deftest rig-command-of-short-operations
  (is (= ["probetron-rig" "info" "--speed-khz" "20" "--format" "text"]
         (operation/rig-command {:operation :info :host "pi" :format :text :speed-khz 20})))
  (is (= ["probetron-rig" "status" "--format" "edn"]
         (operation/rig-command {:operation :status :host "pi" :format :edn})))
  (is (= ["probetron-rig" "reset"]
         (operation/rig-command {:operation :reset :host "pi"})))
  (is (= ["probetron-rig" "flash" "--chip" "RP2350" "--speed-khz" "1000"]
         (operation/rig-command flash-operation)))
  (is (= ["probetron-rig" "erase" "--chip" "RP2350" "--speed-khz" "4000"]
         (operation/rig-command {:operation :erase :host "pi" :chip "RP2350" :speed-khz 4000}))))

(deftest rig-command-of-long-operations
  (testing "the client keeps its own transport options out of the rig command"
    (is (= ["probetron-rig" "connect" "--channel" "usb" "--usb-wait-seconds" "10"]
           (operation/rig-command connect-operation))))
  (testing "a uart channel carries the baud rate instead of the usb wait"
    (is (= ["probetron-rig" "connect" "--channel" "uart" "--baud" "9600" "--reset-on-exit"]
           (operation/rig-command {:operation :connect
                                    :host "pi"
                                    :channel :uart
                                    :baud 9600
                                    :local-port nil
                                    :rtt nil
                                    :pty? false
                                    :reset-on-exit? true}))))
  (testing "rtt travels as a flag because the elf goes over standard input"
    (is (= ["probetron-rig" "connect" "--channel" "usb" "--usb-wait-seconds" "10"
            "--rtt" "--chip" "RP2350" "--speed-khz" "2000"]
           (operation/rig-command
            (assoc connect-operation :rtt {:elf "app.elf" :chip "RP2350" :speed-khz 2000})))))
  (is (= ["probetron-rig" "debug"]
         (operation/rig-command {:operation :debug :host "pi" :local-port nil :reset-on-exit? false})))
  (is (= ["probetron-rig" "debug" "--reset-on-exit"]
         (operation/rig-command {:operation :debug :host "pi" :local-port 3333 :reset-on-exit? true}))))

(deftest rig-commands-parse-on-the-rig
  (doseq [public [flash-operation
                  connect-operation
                  (assoc connect-operation :rtt {:elf "app.elf" :chip "RP2350" :speed-khz 2000})
                  {:operation :erase :host "pi" :chip "RP2350" :speed-khz 4000}
                  {:operation :reset :host "pi"}
                  {:operation :info :host "pi" :format :edn :speed-khz 20}
                  {:operation :debug :host "pi" :local-port 3333 :reset-on-exit? true}]]
    (let [argv (rest (operation/rig-command public))
          result (rig/parse argv {})]
      (is (= :run (:action result)) (str "rig rejected " (pr-str argv)))
      (is (= (:operation public) (:operation (:operation result)))))))

(deftest elf-bearing-operations
  (is (= "firmware.elf" (operation/stdin-elf flash-operation)))
  (is (= "a.elf" (operation/stdin-elf (assoc connect-operation
                                             :rtt {:elf "a.elf" :chip "RP2350" :speed-khz 1000}))))
  (is (nil? (operation/stdin-elf connect-operation)))
  (is (nil? (operation/stdin-elf {:operation :erase :host "pi" :chip "RP2350" :speed-khz 1000}))))

(deftest elf-header-recognition
  (is (true? (operation/elf-header? [0x7f 0x45 0x4c 0x46 0x02])))
  (is (false? (operation/elf-header? [0x7f 0x45 0x4c 0x00])))
  (is (false? (operation/elf-header? [0x7f 0x45]))))

(deftest elf-file-errors
  (is (empty? (operation/elf-errors "a.elf" {:exists? true :regular-file? true :size 4096 :elf-header? true})))
  (is (str/includes? (first (operation/elf-errors "missing.elf" {:exists? false})) "missing.elf"))
  (is (seq (operation/elf-errors "d" {:exists? true :regular-file? false :size 0 :elf-header? false})))
  (is (seq (operation/elf-errors "a.elf" {:exists? true :regular-file? true :size 4096 :elf-header? false})))
  (is (seq (operation/elf-errors "a.elf" {:exists? true
                                          :regular-file? true
                                          :size (inc operation/max-elf-bytes)
                                          :elf-header? true}))))
