(ns probetron.operation
  "Pure operation model that the public client and the rig entry point share.

   An operation is an explicit map such as
   {:operation :flash :host \"pi\" :chip \"RP2350\" :speed-khz 1000 :elf \"firmware.elf\"}.
   Nothing here touches the filesystem, the network, or the hardware."
  (:require [babashka.cli :as cli]
            [clojure.string :as str]))

(declare fields resolve-field parse-host parse-chip parse-speed-khz parse-baud
         parse-usb-wait-seconds parse-local-port parse-channel parse-format
         parse-integer ok invalid hostname? ipv4-literal? ipv6-literal?)

;; Exit statuses of both entry points.
(def exit-ok 0)
(def exit-failure 1)
(def exit-usage 64)
(def exit-unavailable 69)
(def exit-busy 75)

;; Defaults and bounds of the target-specific values.
(def default-speed-khz 1000)
(def min-speed-khz 1)
(def max-speed-khz 50000)
(def default-baud 115200)
(def min-baud 50)
(def max-baud 4000000)
(def default-usb-wait-seconds 10)
(def max-usb-wait-seconds 60)
(def min-local-port 1024)
(def max-local-port 65535)
(def max-elf-bytes (* 64 1024 1024))

(def channels [:usb :uart])
(def formats [:text :edn])

(defn rig-command
  "Return the argv that runs a public operation on the rig.

   Client-only values such as the local port and the pty request stay out of it
   and each ELF file travels over standard input rather than as a path."
  [{:keys [operation format chip speed-khz channel baud usb-wait-seconds rtt reset-on-exit?]}]
  (into ["probetron-rig" (name operation)]
        (case operation
          (:info :status) ["--format" (name format)]
          (:flash :erase) ["--chip" chip "--speed-khz" (str speed-khz)]
          :reset []
          :connect (cond-> ["--channel" (name channel)]
                     (= :uart channel) (into ["--baud" (str baud)])
                     (= :usb channel) (into ["--usb-wait-seconds" (str usb-wait-seconds)])
                     (some? rtt) (into ["--rtt" "--chip" (:chip rtt) "--speed-khz" (str (:speed-khz rtt))])
                     reset-on-exit? (conj "--reset-on-exit"))
          :debug (cond-> []
                   reset-on-exit? (conj "--reset-on-exit")))))

(defn stdin-elf?
  "Tell whether an operation sends one ELF file to the rig over standard input."
  [{:keys [operation rtt]}]
  (boolean (or (= :flash operation)
               (and (= :connect operation) (some? rtt)))))

(defn parse-options
  "Parse one command line against a babashka.cli spec.

   Return {:opts opts :args args} or {:option-error {:option key :message text}}."
  [argv spec]
  (try
    (let [{:keys [opts args]} (cli/parse-args argv {:spec spec :restrict true})]
      {:opts opts :args (vec args)})
    (catch Exception exception
      {:option-error {:option (:option (ex-data exception))
                      :cause (:cause (ex-data exception))
                      :message (ex-message exception)}})))

(defn collect
  "Resolve field keys against options, environment, and defaults.

   Return [values errors]."
  [field-keys context]
  (reduce (fn [[values errors] key]
            (let [resolved (resolve-field key context)]
              (cond
                (:error resolved) [values (conj errors (:error resolved))]
                (contains? resolved :value) [(assoc values key (:value resolved)) errors]
                :else [values errors])))
          [{} []]
          field-keys))

(defn finish
  "Return the assembled operation or every accumulated error."
  [[values errors] extra-errors assemble]
  (let [all-errors (into (vec errors) (remove nil? extra-errors))]
    (if (seq all-errors)
      {:errors all-errors}
      {:operation (assemble values)})))

(defn unexpected-argument-error
  "Return an error for the first positional argument that a command does not take."
  [args]
  (when (seq args)
    (str "unexpected argument " (pr-str (first args)))))

(defn elf-errors
  "Return the errors of an ELF file described by probe facts.

   Facts are {:exists? bool :regular-file? bool :size bytes :elf-header? bool}."
  [path {:keys [exists? regular-file? size elf-header?]}]
  (cond
    (not exists?) [(str "missing ELF file " (pr-str path) ": pass the path of a readable ELF file")]
    (not regular-file?) [(str "invalid ELF file " (pr-str path) ": expected a regular file")]
    (> (or size 0) max-elf-bytes) [(str "invalid ELF file " (pr-str path)
                                        ": expected at most " max-elf-bytes " bytes")]
    (not elf-header?) [(str "invalid ELF file " (pr-str path) ": expected the ELF magic number")]
    :else []))

(defn elf-header?
  "Tell whether the leading bytes of a file carry the ELF magic number."
  [bytes]
  (= [0x7f 0x45 0x4c 0x46]
     (mapv #(bit-and (int %) 0xff) (take 4 bytes))))

(defn build
  "Build a validated public operation from a command keyword and a parsed command line.

   The context is {:opts opts :args args :env env :elf-facts probe :use-env? bool}.
   Return {:operation operation} or {:errors [message ...]}."
  [command {:keys [opts args elf-facts] :as context}]
  (case command
    (:info :status)
    (finish (collect [:host :format] context)
            [(unexpected-argument-error args)]
            (fn [values] {:operation command :host (:host values) :format (:format values)}))

    :flash
    (let [elf (first args)]
      (finish (collect [:host :chip :speed-khz] context)
              (into [(when-not elf "missing argument: pass the path of the ELF file to flash")
                     (unexpected-argument-error (rest args))]
                    (when elf (elf-errors elf (elf-facts elf))))
              (fn [values] {:operation :flash
                            :host (:host values)
                            :chip (:chip values)
                            :speed-khz (:speed-khz values)
                            :elf elf})))

    :erase
    (finish (collect [:host :chip :speed-khz] context)
            [(unexpected-argument-error args)]
            (fn [values] {:operation :erase
                          :host (:host values)
                          :chip (:chip values)
                          :speed-khz (:speed-khz values)}))

    :reset
    (finish (collect [:host] context)
            [(unexpected-argument-error args)]
            (fn [values] {:operation :reset :host (:host values)}))

    :connect
    (let [[values errors] (collect [:host :channel :local-port] context)
          channel (:channel values)
          rtt-path (:rtt opts)
          [rtt-values rtt-errors] (collect (cond-> []
                                             (= :uart channel) (conj :baud)
                                             (= :usb channel) (conj :usb-wait-seconds)
                                             (some? rtt-path) (into [:chip :speed-khz]))
                                           context)]
      (finish [(merge values rtt-values) (into errors rtt-errors)]
              (into [(when (and (:baud opts) (not= :uart channel))
                       "invalid --baud: it is valid only with --channel uart")
                     (when (and (:usb-wait-seconds opts) (not= :usb channel))
                       "invalid --usb-wait-seconds: it is valid only with --channel usb")
                     (when (and (:chip opts) (nil? rtt-path))
                       "invalid --chip: it is valid only with --rtt <elf>")
                     (when (and (:speed-khz opts) (nil? rtt-path))
                       "invalid --speed-khz: it is valid only with --rtt <elf>")
                     (unexpected-argument-error args)]
                    (when rtt-path (elf-errors rtt-path (elf-facts rtt-path))))
              (fn [values]
                (cond-> {:operation :connect :host (:host values) :channel (:channel values)}
                  (= :uart (:channel values)) (assoc :baud (:baud values))
                  (= :usb (:channel values)) (assoc :usb-wait-seconds (:usb-wait-seconds values))
                  true (assoc :local-port (:local-port values)
                              :rtt (when rtt-path
                                     {:elf rtt-path
                                      :chip (:chip values)
                                      :speed-khz (:speed-khz values)})
                              :pty? (true? (:pty opts))
                              :reset-on-exit? (true? (:reset-on-exit opts)))))))

    :debug
    (finish (collect [:host :local-port] context)
            [(unexpected-argument-error args)]
            (fn [values] {:operation :debug
                          :host (:host values)
                          :local-port (:local-port values)
                          :reset-on-exit? (true? (:reset-on-exit opts))}))))

(def fields
  "How every option-carried value is named, defaulted, and validated."
  {:host {:flag "--host" :label "<host>" :env-var "PROBETRON_HOST" :parse #'parse-host :required? true}
   :chip {:flag "--chip" :label "<chip>" :env-var "PROBETRON_CHIP" :parse #'parse-chip :required? true}
   :speed-khz {:flag "--speed-khz" :label "<speed>" :env-var "PROBETRON_SPEED_KHZ"
               :parse #'parse-speed-khz :default default-speed-khz}
   :baud {:flag "--baud" :label "<baud>" :env-var "PROBETRON_UART_BAUD"
          :parse #'parse-baud :default default-baud}
   :usb-wait-seconds {:flag "--usb-wait-seconds" :label "<seconds>" :env-var "PROBETRON_USB_WAIT_SECONDS"
                      :parse #'parse-usb-wait-seconds :default default-usb-wait-seconds}
   :local-port {:flag "--local-port" :label "<port>" :parse #'parse-local-port}
   :channel {:flag "--channel" :label "<usb|uart>" :parse #'parse-channel :required? true}
   :format {:flag "--format" :label "<text|edn>" :parse #'parse-format :default :text}})

(defn resolve-field
  "Resolve one field from the explicit option, the environment, and the default."
  [key {:keys [opts env use-env?]}]
  (let [{:keys [flag env-var parse default required?]} (get fields key)
        explicit (get opts key)
        from-environment (when (and use-env? env-var) (get env env-var))
        raw (if (some? explicit) explicit from-environment)
        source (if (some? explicit) flag env-var)]
    (cond
      (some? raw) (let [result (parse raw)]
                    (if (contains? result :value)
                      result
                      {:error (str "invalid " source " " (pr-str raw) ": " (:error result))}))
      (some? default) {:value default}
      required? {:error (str "missing " flag ": pass " flag " " (:label (get fields key))
                             (when (and use-env? env-var) (str " or set " env-var)))}
      :else {})))

(defn parse-host
  "Accept a DNS host name, an IPv4 literal, or an IPv6 literal."
  [value]
  (if (and (<= 1 (count value) 253)
           (if (re-matches #"[0-9.]+" value)
             (ipv4-literal? value)
             (or (ipv6-literal? value) (hostname? value))))
    (ok value)
    (invalid "expected a host name or an IP address")))

(defn parse-chip
  "Accept a probe-rs chip name."
  [value]
  (if (re-matches #"[A-Za-z0-9][A-Za-z0-9_.+-]{0,63}" value)
    (ok value)
    (invalid "expected a probe-rs chip name such as RP2350")))

(defn parse-speed-khz
  "Accept an SWD clock in kilohertz."
  [value]
  (parse-integer value min-speed-khz max-speed-khz))

(defn parse-baud
  "Accept a UART bit rate."
  [value]
  (parse-integer value min-baud max-baud))

(defn parse-usb-wait-seconds
  "Accept how long the rig waits for the DUT USB device."
  [value]
  (parse-integer value 0 max-usb-wait-seconds))

(defn parse-local-port
  "Accept an unprivileged TCP port on the client."
  [value]
  (parse-integer value min-local-port max-local-port))

(defn parse-channel
  "Accept the byte channel that carries DUT traffic."
  [value]
  (if-let [channel (some #{(keyword value)} channels)]
    (ok channel)
    (invalid (str "expected one of " (str/join " or " (map name channels))))))

(defn parse-format
  "Accept the output format of an information operation."
  [value]
  (if-let [format (some #{(keyword value)} formats)]
    (ok format)
    (invalid (str "expected one of " (str/join " or " (map name formats))))))

(defn parse-integer
  "Accept a decimal integer inside an inclusive range."
  [value low high]
  (let [number (when (re-matches #"-?\d{1,10}" value) (parse-long value))]
    (if (and number (<= low number high))
      (ok number)
      (invalid (str "expected an integer between " low " and " high)))))

(defn ok
  "Wrap an accepted value."
  [value]
  {:value value})

(defn invalid
  "Wrap the reason a value was refused."
  [reason]
  {:error reason})

(defn hostname?
  "Tell whether a string is a dotted DNS host name."
  [value]
  (let [labels (str/split value #"\." -1)]
    (and (seq labels)
         (every? #(re-matches #"(?i)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?" %) labels))))

(defn ipv4-literal?
  "Tell whether a string is a dotted IPv4 literal."
  [value]
  (let [octets (str/split value #"\." -1)]
    (and (= 4 (count octets))
         (every? #(and (re-matches #"\d{1,3}" %) (<= 0 (parse-long %) 255)) octets))))

(defn ipv6-literal?
  "Tell whether a string is an IPv6 literal, with an optional zone identifier."
  [value]
  (let [[address zone & extra] (str/split value #"%" -1)]
    (and (nil? (seq extra))
         (or (nil? zone) (re-matches #"(?i)[a-z0-9-]+" zone))
         (re-matches #"(?i)[0-9a-f:]+" address)
         (<= 2 (count (filter #{\:} address)) 7)
         (not (str/includes? address ":::"))
         (<= (count (re-seq #"::" address)) 1)
         (every? #(<= (count %) 4) (str/split address #":" -1)))))
