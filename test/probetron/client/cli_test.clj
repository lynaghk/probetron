(ns probetron.client.cli-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [probetron.client.cli :as cli]
            [probetron.operation :as operation]))

(def readable-elf
  {:exists? true :regular-file? true :size 65536 :elf-header? true})

(defn elf-facts
  "Return an elf probe that knows only the given paths."
  [known]
  (fn [path] (get known path {:exists? false})))

(defn parse
  ([argv] (parse argv {}))
  ([argv ctx]
   (cli/parse argv (merge {:env {}
                           :elf-facts (elf-facts {"firmware.elf" readable-elf
                                                  "app.elf" readable-elf})}
                          ctx))))

(defn operation
  "Return the operation map that a successful parse produced."
  [argv & [ctx]]
  (let [result (parse argv (or ctx {}))]
    (is (= :run (:action result)) (pr-str result))
    (:operation result)))

(defn usage-error
  "Return the message of a parse that failed with the usage status."
  [argv & [ctx]]
  (let [result (parse argv (or ctx {}))]
    (is (= :error (:action result)) (pr-str result))
    (is (= operation/exit-usage (:exit result)))
    (:message result)))

(deftest help-and-version
  (testing "an empty command line explains itself and fails with the usage status"
    (let [result (parse [])]
      (is (= :help (:action result)))
      (is (= operation/exit-usage (:exit result)))
      (is (str/includes? (:text result) "probetron connect"))))
  (doseq [argv [["--help"] ["-h"] ["help"]]]
    (let [result (parse argv)]
      (is (= :help (:action result)))
      (is (= operation/exit-ok (:exit result)))
      (is (str/includes? (:text result) "PROBETRON_HOST"))))
  (testing "a command asks for its own help"
    (let [result (parse ["flash" "--help"])]
      (is (= :help (:action result)))
      (is (= operation/exit-ok (:exit result)))
      (is (str/includes? (:text result) "probetron flash"))
      (is (not (str/includes? (:text result) "probetron debug")))))
  (let [result (parse ["--version"])]
    (is (= :version (:action result)))
    (is (= operation/exit-ok (:exit result)))))

(deftest unknown-commands-and-options
  (is (str/includes? (usage-error ["fry" "--host" "pi"]) "fry"))
  (is (str/includes? (usage-error ["info" "--host" "pi" "--nozzle" "2"]) "--nozzle"))
  (testing "an option belongs only to the commands that document it"
    (is (str/includes? (usage-error ["reset" "--host" "pi" "--chip" "RP2350"]) "--chip"))
    (is (str/includes? (usage-error ["info" "--host" "pi" "--local-port" "3333"]) "--local-port"))))

(deftest info-and-status
  (is (= {:operation :info :host "pi.lab" :format :text :speed-khz 20} (operation ["info" "--host" "pi.lab"])))
  (is (= {:operation :status :host "10.0.0.7" :format :edn}
         (operation ["status" "--host" "10.0.0.7" "--format" "edn"])))
  (is (str/includes? (usage-error ["info" "--host" "pi" "--format" "yaml"]) "--format"))
  (is (str/includes? (usage-error ["info" "--host" "pi.lab" "extra"]) "extra"))
  (testing "info clocks the SWD bus like every target command, so it shares --speed-khz"
    (is (= 50 (:speed-khz (operation ["info" "--host" "pi" "--speed-khz" "50"]))))
    (is (= 30 (:speed-khz (operation ["info" "--host" "pi"] {:env {"PROBETRON_SPEED_KHZ" "30"}})))))
  (testing "info attaches passively, so it names no chip"
    (is (str/includes? (usage-error ["info" "--host" "pi" "--chip" "RP2350"]) "--chip"))))

(deftest host-validation-and-environment
  (testing "a missing host names both ways of supplying it"
    (let [message (usage-error ["info"])]
      (is (str/includes? message "--host"))
      (is (str/includes? message "PROBETRON_HOST"))))
  (is (= "bench-pi" (:host (operation ["info"] {:env {"PROBETRON_HOST" "bench-pi"}}))))
  (testing "an explicit host wins over the environment"
    (is (= "other" (:host (operation ["info" "--host" "other"] {:env {"PROBETRON_HOST" "bench-pi"}})))))
  (doseq [host ["pi.lab" "probetron-1" "10.0.0.7" "fd00::1" "a"]]
    (is (= host (:host (operation ["info" "--host" host])))))
  (doseq [host ["" "-pi" "pi..lab" "pi lab" "10.0.0.999" "pi/lab" "http://pi"]]
    (is (str/includes? (usage-error ["info" "--host" host]) "--host") (pr-str host))))

(deftest flash-operations
  (is (= {:operation :flash :host "pi" :chip "RP2350" :speed-khz 20 :elf "firmware.elf"}
         (operation ["flash" "--host" "pi" "--chip" "RP2350" "firmware.elf"])))
  (is (= 4000 (:speed-khz (operation ["flash" "--host" "pi" "--chip" "RP2350" "--speed-khz" "4000" "firmware.elf"]))))
  (testing "the environment supplies chip and speed defaults"
    (is (= {:operation :flash :host "pi" :chip "RP2350" :speed-khz 2000 :elf "firmware.elf"}
           (operation ["flash" "firmware.elf"]
                      {:env {"PROBETRON_HOST" "pi"
                             "PROBETRON_CHIP" "RP2350"
                             "PROBETRON_SPEED_KHZ" "2000"}}))))
  (testing "explicit options win over the environment"
    (is (= {:operation :flash :host "pi" :chip "RP2040" :speed-khz 500 :elf "firmware.elf"}
           (operation ["flash" "--host" "pi" "--chip" "RP2040" "--speed-khz" "500" "firmware.elf"]
                      {:env {"PROBETRON_CHIP" "RP2350" "PROBETRON_SPEED_KHZ" "2000"}}))))
  (is (str/includes? (usage-error ["flash" "--host" "pi" "firmware.elf"]) "--chip"))
  (is (str/includes? (usage-error ["flash" "--host" "pi" "--chip" "RP2350"]) "ELF"))
  (is (str/includes? (usage-error ["flash" "--host" "pi" "--chip" "RP2350" "firmware.elf" "other.elf"]) "other.elf")))

(deftest bounded-numbers
  (doseq [speed ["0" "-1" "abc" "1000000" "1.5" ""]]
    (is (str/includes? (usage-error ["erase" "--host" "pi" "--chip" "RP2350" "--speed-khz" speed]) "--speed-khz")
        (pr-str speed)))
  (is (= 100 (:speed-khz (operation ["erase" "--host" "pi" "--chip" "RP2350" "--speed-khz" "100"]))))
  (doseq [baud ["0" "-9600" "nine" "9000000"]]
    (is (str/includes? (usage-error ["connect" "--host" "pi" "--channel" "uart" "--baud" baud]) "--baud")
        (pr-str baud)))
  (doseq [seconds ["-1" "61" "soon"]]
    (is (str/includes? (usage-error ["connect" "--host" "pi" "--channel" "usb" "--usb-wait-seconds" seconds])
                       "--usb-wait-seconds")
        (pr-str seconds)))
  (is (= 60 (:usb-wait-seconds (operation ["connect" "--host" "pi" "--channel" "usb" "--usb-wait-seconds" "60"]))))
  (doseq [port ["0" "80" "70000" "port"]]
    (is (str/includes? (usage-error ["debug" "--host" "pi" "--local-port" port]) "--local-port") (pr-str port)))
  (is (= 3333 (:local-port (operation ["debug" "--host" "pi" "--local-port" "3333"])))))

(deftest chip-validation
  (doseq [chip ["RP2350" "RP2040" "nRF52840_xxAA" "STM32F103C8"]]
    (is (= chip (:chip (operation ["erase" "--host" "pi" "--chip" chip])))))
  (doseq [chip ["" "-RP2350" "RP2350; rm -rf /" "RP 2350" "$(id)"]]
    (is (str/includes? (usage-error ["erase" "--host" "pi" "--chip" chip]) "--chip") (pr-str chip))))

(deftest erase-and-reset
  (is (= {:operation :erase :host "pi" :chip "RP2350" :speed-khz 20}
         (operation ["erase" "--host" "pi" "--chip" "RP2350"])))
  (is (= {:operation :reset :host "pi"} (operation ["reset" "--host" "pi"]))))

(deftest connect-channels
  (is (= {:operation :connect
          :host "pi"
          :channel :usb
          :usb-wait-seconds 10
          :local-port nil
          :rtt nil
          :pty? false
          :reset-on-exit? false}
         (operation ["connect" "--host" "pi" "--channel" "usb"])))
  (is (= {:operation :connect
          :host "pi"
          :channel :uart
          :baud 115200
          :local-port nil
          :rtt nil
          :pty? false
          :reset-on-exit? false}
         (operation ["connect" "--host" "pi" "--channel" "uart"])))
  (is (= 9600 (:baud (operation ["connect" "--host" "pi" "--channel" "uart" "--baud" "9600"]))))
  (is (= 9600 (:baud (operation ["connect" "--host" "pi" "--channel" "uart"]
                                {:env {"PROBETRON_UART_BAUD" "9600"}}))))
  (is (= 30 (:usb-wait-seconds (operation ["connect" "--host" "pi" "--channel" "usb"]
                                          {:env {"PROBETRON_USB_WAIT_SECONDS" "30"}}))))
  (testing "a channel-specific option is refused on the other channel"
    (is (str/includes? (usage-error ["connect" "--host" "pi" "--channel" "usb" "--baud" "9600"]) "--baud"))
    (is (str/includes? (usage-error ["connect" "--host" "pi" "--channel" "uart" "--usb-wait-seconds" "5"])
                       "--usb-wait-seconds")))
  (testing "an unrelated environment default never reaches the other channel"
    (is (nil? (:baud (operation ["connect" "--host" "pi" "--channel" "usb"]
                                {:env {"PROBETRON_UART_BAUD" "9600"}}))))
    (is (nil? (:usb-wait-seconds (operation ["connect" "--host" "pi" "--channel" "uart"]
                                            {:env {"PROBETRON_USB_WAIT_SECONDS" "30"}})))))
  (is (str/includes? (usage-error ["connect" "--host" "pi"]) "--channel"))
  (is (str/includes? (usage-error ["connect" "--host" "pi" "--channel" "spi"]) "--channel")))

(deftest connect-rtt-and-session-options
  (is (= {:elf "app.elf" :chip "RP2350" :speed-khz 20}
         (:rtt (operation ["connect" "--host" "pi" "--channel" "usb" "--rtt" "app.elf" "--chip" "RP2350"]))))
  (is (= {:elf "app.elf" :chip "RP2350" :speed-khz 2000}
         (:rtt (operation ["connect" "--host" "pi" "--channel" "usb" "--rtt" "app.elf" "--speed-khz" "2000"]
                          {:env {"PROBETRON_CHIP" "RP2350"}}))))
  (testing "rtt options need rtt"
    (is (str/includes? (usage-error ["connect" "--host" "pi" "--channel" "usb" "--chip" "RP2350"]) "--rtt"))
    (is (str/includes? (usage-error ["connect" "--host" "pi" "--channel" "usb" "--speed-khz" "2000"]) "--rtt")))
  (testing "rtt needs a chip"
    (is (str/includes? (usage-error ["connect" "--host" "pi" "--channel" "usb" "--rtt" "app.elf"]) "--chip")))
  (is (str/includes? (usage-error ["connect" "--host" "pi" "--channel" "usb" "--rtt" "gone.elf" "--chip" "RP2350"])
                     "gone.elf"))
  (let [session (operation ["connect" "--host" "pi" "--channel" "usb" "--pty" "--reset-on-exit" "--local-port" "4444"])]
    (is (true? (:pty? session)))
    (is (true? (:reset-on-exit? session)))
    (is (= 4444 (:local-port session)))))

(deftest debug-sessions
  (is (= {:operation :debug :host "pi" :local-port nil :reset-on-exit? false}
         (operation ["debug" "--host" "pi"])))
  (is (= {:operation :debug :host "pi" :local-port 3333 :reset-on-exit? true}
         (operation ["debug" "--host" "pi" "--local-port" "3333" "--reset-on-exit"])))
  (is (str/includes? (usage-error ["debug" "--host" "pi" "--pty"]) "--pty")))

(deftest the-usb-console-carries-a-shell-and-a-fixed-address
  (testing "a shell over the USB console needs no host at all"
    (is (= {:operation :shell :host operation/usb-console-address}
           (operation ["shell" "--usb"]))))
  (testing "a shell over the lab network names its rig"
    (is (= {:operation :shell :host "pi.lab"} (operation ["shell" "--host" "pi.lab"]))))
  (testing "--usb resolves the host of every other command too"
    (is (= operation/usb-console-address (:host (operation ["reset" "--usb"]))))
    (is (= operation/usb-console-address (:host (operation ["info" "--usb"])))))
  (testing "--usb and --host would name two rigs at once"
    (is (str/includes? (usage-error ["shell" "--usb" "--host" "pi.lab"]) "--usb")))
  (testing "a shell that names no rig at all says how to name one"
    (is (str/includes? (usage-error ["shell"]) "--host")))
  (testing "a shell takes no argument of its own"
    (is (str/includes? (usage-error ["shell" "--usb" "extra"]) "unexpected argument"))))
