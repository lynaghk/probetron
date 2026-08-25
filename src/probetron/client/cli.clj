(ns probetron.client.cli
  "Pure parser of the public probetron command line."
  (:require [clojure.string :as str]
            [probetron.operation :as op]
            [probetron.version :as version]))

(declare help-text command-help command-names command-specs command-usage
         usage-lines environment-lines parse-command option-message)

(defn parse
  "Turn a public argv into an action map.

   The context is {:env env :elf-facts probe}.
   Return {:action :run :operation operation}, {:action :help :text text :exit status},
   {:action :version :text text :exit status}, or {:action :error :message text :exit status}."
  [argv context]
  (let [[head & remaining] argv]
    (cond
      (nil? head)
      {:action :help :text (help-text) :exit op/exit-usage}

      (#{"--help" "-h" "help"} head)
      {:action :help :text (help-text) :exit op/exit-ok}

      (#{"--version" "-V" "version"} head)
      {:action :version :text (str "probetron " version/probetron-version) :exit op/exit-ok}

      :else
      (if-let [command (get command-names head)]
        (if (some #{"--help" "-h"} remaining)
          {:action :help :text (command-help command) :exit op/exit-ok}
          (parse-command command (vec remaining) context))
        {:action :error
         :message (str "unknown command " (pr-str head) ": run probetron --help")
         :exit op/exit-usage}))))

(defn help-text
  "Return the help of the whole public command."
  []
  (str/join "\n"
            (concat ["Probetron gives lab clients network access to one RP2350 target on a Raspberry Pi."
                     ""
                     "Usage:"]
                    (usage-lines)
                    [""
                     "Environment:"]
                    (environment-lines)
                    [""
                     "Exit status:"
                     (str "  " op/exit-ok "   success")
                     (str "  " op/exit-usage "  usage error")
                     (str "  " op/exit-busy "  the rig is busy with another operation")])))

(defn command-help
  "Return the help of one public command."
  [command]
  (str/join "\n" ["Usage:" (command-usage command)]))

(defn parse-command
  "Parse the options of one public command and build its operation."
  [command argv context]
  (let [{:keys [opts args option-error]} (op/parse-options argv (command-specs command))]
    (if option-error
      {:action :error :message (option-message command option-error) :exit op/exit-usage}
      (let [{:keys [operation errors]} (op/build command (assoc context
                                                                :opts opts
                                                                :args args
                                                                :use-env? true))]
        (if errors
          {:action :error :message (str/join "\n" errors) :exit op/exit-usage}
          {:action :run :operation operation})))))

(defn option-message
  "Explain an option that babashka.cli refused."
  [command {:keys [option cause message]}]
  (if (= :restrict cause)
    (str "unknown option --" (name option) " for probetron " (name command)
         ": run probetron " (name command) " --help")
    message))

(def value-option {:coerce :string})
(def flag-option {:coerce :boolean})

(def command-specs
  "The options that each public command accepts."
  {:info {:host value-option :format value-option}
   :status {:host value-option :format value-option}
   :flash {:host value-option :chip value-option :speed-khz value-option}
   :erase {:host value-option :chip value-option :speed-khz value-option}
   :reset {:host value-option}
   :connect {:host value-option
             :channel value-option
             :baud value-option
             :usb-wait-seconds value-option
             :local-port value-option
             :rtt value-option
             :chip value-option
             :speed-khz value-option
             :pty flag-option
             :reset-on-exit flag-option}
   :debug {:host value-option :local-port value-option :reset-on-exit flag-option}})

(def commands
  "The public commands in help order."
  [:info :status :flash :erase :reset :connect :debug])

(def command-names
  "The public command names as the client types them."
  (into {} (map (fn [command] [(name command) command])) commands))

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

(defn usage-lines
  "Return one usage line for every public command."
  []
  (map command-usage commands))

(defn environment-lines
  "Return the environment defaults that the public commands read."
  []
  ["  PROBETRON_HOST              default for --host"
   "  PROBETRON_CHIP              default for --chip"
   (str "  PROBETRON_SPEED_KHZ         default for --speed-khz (" op/default-speed-khz ")")
   (str "  PROBETRON_UART_BAUD         default for --baud (" op/default-baud ")")
   (str "  PROBETRON_USB_WAIT_SECONDS  default for --usb-wait-seconds ("
        op/default-usb-wait-seconds ")")])
