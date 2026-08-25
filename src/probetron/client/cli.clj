(ns probetron.client.cli
  "Pure parser of the public probetron command line."
  (:require [clojure.string :as str]
            [probetron.frontend :as frontend]
            [probetron.operation :as op]))

(declare front-end help-text command-specs command-usage environment-lines)

(defn parse
  "Turn a public argv into an action map.

   The context is {:env env :elf-facts probe}.
   Return {:action :run :operation operation}, {:action :help :text text :exit status},
   {:action :version :text text :exit status}, or {:action :error :message text :exit status}."
  [argv context]
  (frontend/parse front-end argv context))

(defn help-text
  "Return the help of the whole public command."
  []
  (str/join "\n"
            (concat ["Probetron gives lab clients network access to one RP2350 target on a Raspberry Pi."
                     ""
                     "Usage:"]
                    (frontend/usage-lines front-end)
                    [""
                     "Environment:"]
                    (environment-lines)
                    [""
                     "Exit status:"
                     (str "  " op/exit-ok "   success")
                     (str "  " op/exit-usage "  usage error")
                     (str "  " op/exit-busy "  the rig is busy with another operation")])))

(defn environment-lines
  "Return the environment defaults that the public commands read."
  []
  ["  PROBETRON_HOST              default for --host"
   "  PROBETRON_CHIP              default for --chip"
   (str "  PROBETRON_SPEED_KHZ         default for --speed-khz (" op/default-speed-khz ")")
   (str "  PROBETRON_UART_BAUD         default for --baud (" op/default-baud ")")
   (str "  PROBETRON_USB_WAIT_SECONDS  default for --usb-wait-seconds ("
        op/default-usb-wait-seconds ")")])

(def command-specs
  "The options that each public command accepts."
  (let [value frontend/value-option
        flag frontend/flag-option]
    {:info {:host value :format value}
     :status {:host value :format value}
     :flash {:host value :chip value :speed-khz value}
     :erase {:host value :chip value :speed-khz value}
     :reset {:host value}
     :connect {:host value
               :channel value
               :baud value
               :usb-wait-seconds value
               :local-port value
               :rtt value
               :chip value
               :speed-khz value
               :pty flag
               :reset-on-exit flag}
     :debug {:host value :local-port value :reset-on-exit flag}}))

(def command-usage
  "The documented form of every public command."
  {:info "  probetron info    --host <host> [--format <text|edn>]"
   :status "  probetron status  --host <host> [--format <text|edn>]"
   :flash "  probetron flash   --host <host> --chip <chip> [--speed-khz <speed>] <elf>"
   :erase "  probetron erase   --host <host> --chip <chip> [--speed-khz <speed>]"
   :reset "  probetron reset   --host <host>"
   :connect (str "  probetron connect --host <host> --channel <usb|uart> [--baud <baud>]"
                 " [--usb-wait-seconds <seconds>] [--local-port <port>]"
                 " [--rtt <elf> --chip <chip> [--speed-khz <speed>]] [--pty] [--reset-on-exit]")
   :debug "  probetron debug   --host <host> [--local-port <port>] [--reset-on-exit]"})

(def front-end
  "How the public command line reaches the operation model.

   The client reads environment defaults, and every ELF path it takes is a
   client path that validation probes before the operation leaves."
  {:program "probetron"
   :help-text help-text
   :command-specs command-specs
   :command-usage command-usage
   :use-env? true
   :build op/build})
