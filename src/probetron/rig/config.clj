(ns probetron.rig.config
  "Pure parser of the rig-only SSH protocol.

   The rig takes no client-only option, reads no client environment default,
   and receives every ELF file over standard input."
  (:require [clojure.string :as str]
            [probetron.frontend :as frontend]
            [probetron.rig.lifecycle :as lifecycle]
            [probetron.operation :as op]))

(declare front-end build refused-option client-only-options command-specs command-usage help-text)

(def program-name lifecycle/program-name)

(defn parse
  "Turn a rig argv into an action map.

   Return {:action :run :operation operation}, {:action :help :text text :exit status},
   {:action :version :text text :exit status}, or {:action :error :message text :exit status}."
  [argv context]
  (frontend/parse front-end argv context))

(defn build
  "Build a validated rig operation from a command keyword and a parsed command line."
  [command {:keys [opts args] :as context}]
  (case command
    :status
    (op/finish (op/collect [:format] context)
               [(op/unexpected-argument-error args)]
               (fn [values] {:operation :status :format (:format values)}))

    :info
    (op/finish (op/collect [:speed-khz :format] context)
               [(op/unexpected-argument-error args)]
               (fn [values] {:operation :info
                             :speed-khz (:speed-khz values)
                             :format (:format values)}))

    (:flash :erase)
    (op/finish (op/collect op/chip-and-speed-fields context)
               [(op/unexpected-argument-error args)]
               (fn [values] (cond-> {:operation command
                                     :chip (:chip values)
                                     :speed-khz (:speed-khz values)}
                              (= :flash command) (assoc :elf :stdin))))

    :reset
    (op/finish [{} []]
               [(op/unexpected-argument-error args)]
               (fn [_] {:operation :reset}))

    :log
    (op/finish [{} []]
               [(op/unexpected-argument-error args)]
               (fn [_] {:operation :log}))

    :connect
    (let [[values errors] (op/collect [:channel] context)
          channel (:channel values)
          rtt? (true? (:rtt opts))
          [rtt-values rtt-errors] (op/collect (op/connect-fields channel rtt?) context)]
      (op/finish [(merge values rtt-values) (into errors rtt-errors)]
                 (conj (op/connect-option-errors opts channel rtt? "--rtt")
                       (op/unexpected-argument-error args))
                 (fn [values]
                   (cond-> {:operation :connect
                            :channel (:channel values)
                            :rtt (when rtt?
                                   {:chip (:chip values)
                                    :speed-khz (:speed-khz values)
                                    :elf :stdin})
                            :reset-on-exit? (true? (:reset-on-exit opts))}
                     (= :uart (:channel values)) (assoc :baud (:baud values))
                     (= :usb (:channel values)) (assoc :usb-wait-seconds (:usb-wait-seconds values))))))

    :debug
    (op/finish [{} []]
               [(op/unexpected-argument-error args)]
               (fn [_] {:operation :debug :reset-on-exit? (true? (:reset-on-exit opts))}))))

(defn refused-option
  "Explain a client-only option that stops at the rig boundary, or return nil."
  [option]
  (when (client-only-options option)
    (str "unknown option --" (name option)
         ": it is a client-only option that never reaches the rig")))

(def client-only-options
  "Options that belong to the client and stop at the rig boundary."
  #{:host :local-port :pty})

(def command-specs
  "The options that each rig command accepts."
  (let [value frontend/value-option
        flag frontend/flag-option
        chip-and-speed {:chip value :speed-khz value}]
    {:info {:speed-khz value :format value}
     :status {:format value}
     :flash chip-and-speed
     :erase chip-and-speed
     :reset {}
     :log {}
     :connect (merge {:channel value
                      :baud value
                      :usb-wait-seconds value
                      :rtt flag
                      :reset-on-exit flag}
                     chip-and-speed)
     :debug {:reset-on-exit flag}}))

(def command-usage
  "The documented form of every rig command."
  {:info "  probetron-rig info    [--speed-khz <speed>] [--format <text|edn>]"
   :status "  probetron-rig status  [--format <text|edn>]"
   :log "  probetron-rig log"
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
                    (frontend/usage-lines front-end)
                    [""
                     "flash and connect --rtt read one bounded ELF file from standard input."])))

(def front-end
  "How the rig protocol reaches the operation model.

   The rig reads no environment default, and every ELF file it flashes or
   decodes arrives on standard input rather than as a path."
  {:program program-name
   :help-text help-text
   :command-specs command-specs
   :command-usage command-usage
   :use-env? false
   :refused refused-option
   :build build})
