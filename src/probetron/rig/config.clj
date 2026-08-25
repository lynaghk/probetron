(ns probetron.rig.config
  "Pure parser of the rig-only SSH protocol.

   The rig takes no client-only option, reads no client environment default,
   and receives every ELF file over standard input."
  (:require [clojure.string :as str]
            [probetron.rig.lifecycle :as lifecycle]
            [probetron.operation :as op]
            [probetron.version :as version]))

(declare help-text command-help command-names command-specs command-usage
         parse-command build option-message client-only-options)

(def program-name lifecycle/program-name)

(defn parse
  "Turn a rig argv into an action map.

   Return {:action :run :operation operation}, {:action :help :text text :exit status},
   {:action :version :text text :exit status}, or {:action :error :message text :exit status}."
  [argv _context]
  (let [[head & remaining] argv]
    (cond
      (nil? head)
      {:action :help :text (help-text) :exit op/exit-usage}

      (#{"--help" "-h" "help"} head)
      {:action :help :text (help-text) :exit op/exit-ok}

      (#{"--version" "-V" "version"} head)
      {:action :version :text (str program-name " " version/probetron-version) :exit op/exit-ok}

      :else
      (if-let [command (get command-names head)]
        (if (some #{"--help" "-h"} remaining)
          {:action :help :text (command-help command) :exit op/exit-ok}
          (parse-command command (vec remaining)))
        {:action :error
         :message (str "unknown command " (pr-str head) ": run " program-name " --help")
         :exit op/exit-usage}))))

(defn parse-command
  "Parse the options of one rig command and build its operation."
  [command argv]
  (let [{:keys [opts args option-error]} (op/parse-options argv (command-specs command))]
    (if option-error
      {:action :error :message (option-message command option-error) :exit op/exit-usage}
      (let [{:keys [operation errors]} (build command {:opts opts :args args :use-env? false})]
        (if errors
          {:action :error :message (str/join "\n" errors) :exit op/exit-usage}
          {:action :run :operation operation})))))

(defn build
  "Build a validated rig operation from a command keyword and a parsed command line."
  [command {:keys [opts args] :as context}]
  (case command
    (:info :status)
    (op/finish (op/collect [:format] context)
               [(op/unexpected-argument-error args)]
               (fn [values] {:operation command :format (:format values)}))

    (:flash :erase)
    (op/finish (op/collect [:chip :speed-khz] context)
               [(op/unexpected-argument-error args)]
               (fn [values] (cond-> {:operation command
                                     :chip (:chip values)
                                     :speed-khz (:speed-khz values)}
                              (= :flash command) (assoc :elf :stdin))))

    :reset
    (op/finish [{} []]
               [(op/unexpected-argument-error args)]
               (fn [_] {:operation :reset}))

    :connect
    (let [[values errors] (op/collect [:channel] context)
          channel (:channel values)
          rtt? (true? (:rtt opts))
          [rtt-values rtt-errors] (op/collect (cond-> []
                                                (= :uart channel) (conj :baud)
                                                (= :usb channel) (conj :usb-wait-seconds)
                                                rtt? (into [:chip :speed-khz]))
                                              context)]
      (op/finish [(merge values rtt-values) (into errors rtt-errors)]
                 [(when (and (:baud opts) (not= :uart channel))
                    "invalid --baud: it is valid only with --channel uart")
                  (when (and (:usb-wait-seconds opts) (not= :usb channel))
                    "invalid --usb-wait-seconds: it is valid only with --channel usb")
                  (when (and (:chip opts) (not rtt?))
                    "invalid --chip: it is valid only with --rtt")
                  (when (and (:speed-khz opts) (not rtt?))
                    "invalid --speed-khz: it is valid only with --rtt")
                  (op/unexpected-argument-error args)]
                 (fn [values]
                   (cond-> {:operation :connect :channel (:channel values)}
                     (= :uart (:channel values)) (assoc :baud (:baud values))
                     (= :usb (:channel values)) (assoc :usb-wait-seconds (:usb-wait-seconds values))
                     true (assoc :rtt (when rtt?
                                        {:chip (:chip values)
                                         :speed-khz (:speed-khz values)
                                         :elf :stdin})
                                 :reset-on-exit? (true? (:reset-on-exit opts)))))))

    :debug
    (op/finish [{} []]
               [(op/unexpected-argument-error args)]
               (fn [_] {:operation :debug :reset-on-exit? (true? (:reset-on-exit opts))}))))

(defn option-message
  "Explain an option that the rig refused."
  [command {:keys [option cause message]}]
  (cond
    (and (= :restrict cause) (client-only-options option))
    (str "unknown option --" (name option) ": it is a client-only option that never reaches the rig")

    (= :restrict cause)
    (str "unknown option --" (name option) " for " program-name " " (name command)
         ": run " program-name " " (name command) " --help")

    :else message))

(def client-only-options
  "Options that belong to the client and stop at the rig boundary."
  #{:host :local-port :pty})

(def value-option {:coerce :string})
(def flag-option {:coerce :boolean})

(def command-specs
  "The options that each rig command accepts."
  {:info {:format value-option}
   :status {:format value-option}
   :flash {:chip value-option :speed-khz value-option}
   :erase {:chip value-option :speed-khz value-option}
   :reset {}
   :connect {:channel value-option
             :baud value-option
             :usb-wait-seconds value-option
             :rtt flag-option
             :chip value-option
             :speed-khz value-option
             :reset-on-exit flag-option}
   :debug {:reset-on-exit flag-option}})

(def commands
  "The rig commands in help order."
  [:info :status :flash :erase :reset :connect :debug])

(def command-names
  "The rig command names as the client sends them."
  (into {} (map (fn [command] [(name command) command])) commands))

(def command-usage
  "The documented form of every rig command."
  {:info "  probetron-rig info    [--format <text|edn>]"
   :status "  probetron-rig status  [--format <text|edn>]"
   :flash "  probetron-rig flash   --chip <chip> [--speed-khz <speed>]"
   :erase "  probetron-rig erase   --chip <chip> [--speed-khz <speed>]"
   :reset "  probetron-rig reset"
   :connect (str "  probetron-rig connect --channel <usb|uart> [--baud <baud>]"
                 " [--usb-wait-seconds <seconds>] [--rtt --chip <chip> [--speed-khz <speed>]]"
                 " [--reset-on-exit]")
   :debug "  probetron-rig debug   [--reset-on-exit]"})

(defn help-text
  "Return the help of the whole rig protocol."
  []
  (str/join "\n"
            (concat ["Probetron rig entry point. The client calls it over SSH."
                     ""
                     "Usage:"]
                    (map command-usage commands)
                    [""
                     "flash and connect --rtt read one bounded ELF file from standard input."])))

(defn command-help
  "Return the help of one rig command."
  [command]
  (str/join "\n" ["Usage:" (command-usage command)]))
