(ns probetron.operation
  "Pure operation model that the public client and the rig entry point share.

   An operation is an explicit map such as
   {:operation :flash :host \"pi\" :chip \"target-chip\" :speed-khz 1000 :elf \"firmware.elf\"}.
   Nothing here touches the filesystem, the network, or the hardware."
  (:require [babashka.cli :as cli]
            [clojure.string :as str]))

(declare build-command usb-error connect-fields connect-option-errors fields resolve-field
         parse-host parse-chip
         parse-speed-khz parse-baud parse-usb-wait-seconds parse-local-port parse-channel
         parse-format parse-terminal-dimension parse-integer ok invalid
         hostname? ipv4-literal? ipv6-literal?)

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
(def min-terminal-dimension 1)
(def max-terminal-dimension 1000)
(def max-elf-bytes (* 64 1024 1024))

(def usb-console-address
  "The address of the rig on its own USB console link.

   The rig holds this address and serves the one lease that a client takes, so
   --usb reaches a rig that no lab network has to carry."
  "192.168.99.1")

(def channels [:usb :uart])
(def formats [:text :edn])

;; The loopback services that one locked rig session publishes and one client forwards.
(def rig-loopback "127.0.0.1")
(def rig-byte-port 5555)
(def rig-dap-port 50000)

(def chip-and-speed-fields
  "The options that name the chip to load and clock the SWD bus.

   Every command that flashes, erases, or attaches to a named target resolves
   both, so the two travel together wherever a probe-rs attach is configured."
  [:chip :speed-khz])

(defn speed-args
  "Return the --speed-khz token pair that clocks one probe-rs attach."
  [{:keys [speed-khz]}]
  ["--speed-khz" (str speed-khz)])

(defn chip-and-speed-args
  "Return the shared --chip and --speed-khz tokens of one named-target attach."
  [{:keys [chip] :as attach}]
  (into ["--chip" chip] (speed-args attach)))

(defn terminal-args
  "Return the --terminal-cols and --terminal-rows tokens of one client terminal.

   The rig sizes the pseudo-terminal behind the probe-rs progress bars to
   exactly this size, because the bars render correctly only on a terminal of
   the width they were drawn for. A client without a terminal sends no size."
  [{:keys [terminal]}]
  (if-let [{:keys [cols rows]} terminal]
    ["--terminal-cols" (str cols) "--terminal-rows" (str rows)]
    []))

(defn rig-command
  "Return the argv that runs a public operation on the rig.

   Client-only values such as the local port and the pty request stay out of it
   and each ELF file travels over standard input rather than as a path."
  [{:keys [operation format channel baud usb-wait-seconds rtt reset-on-exit?] :as public}]
  (into ["probetron-rig" (name operation)]
        (case operation
          :status ["--format" (name format)]
          :info (into (speed-args public) ["--format" (name format)])
          (:flash :erase) (into (chip-and-speed-args public) (terminal-args public))
          :reset []
          :log []
          :connect (cond-> ["--channel" (name channel)]
                     (= :uart channel) (into ["--baud" (str baud)])
                     (= :usb channel) (into ["--usb-wait-seconds" (str usb-wait-seconds)])
                     (some? rtt) (into (cons "--rtt" (chip-and-speed-args rtt)))
                     reset-on-exit? (conj "--reset-on-exit"))
          :debug (cond-> []
                   reset-on-exit? (conj "--reset-on-exit")))))

(defn stdin-elf
  "Return the client path of the ELF file an operation sends to the rig, or nil.

   A flash sends the firmware it downloads and a connect session sends the ELF
   that decodes RTT, and both travel over standard input rather than as a path."
  [{:keys [operation elf rtt]}]
  (case operation
    :flash elf
    :connect (:elf rtt)
    nil))

(defn parse-options
  "Parse one command line against a babashka.cli spec.

   Return {:opts opts :args args} or {:option-error {:option key :message text}}."
  [argv spec]
  (try
    (let [{:keys [opts args]} (cli/parse-args argv {:spec spec :restrict true})]
      {:opts opts :args (vec args)})
    (catch Exception exception
      {:option-error {:option  (:option (ex-data exception))
                      :cause   (:cause (ex-data exception))
                      :message (ex-message exception)}})))

(defn collect
  "Resolve field keys against options, environment, and defaults.

   Return [values errors]."
  [field-keys context]
  (reduce (fn [[values errors] field]
            (let [resolved (resolve-field field context)]
              (cond
                (:error resolved) [values (conj errors (:error resolved))]
                (contains? resolved :value) [(assoc values field (:value resolved)) errors]
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
  [data]
  (= [0x7f 0x45 0x4c 0x46]
     (mapv #(bit-and (int %) 0xff) (take 4 data))))

(defn build
  "Build a validated public operation from a command keyword and a parsed command line.

   The context is {:opts opts :args args :env env :elf-facts probe :use-env? bool}.
   Return {:operation operation} or {:errors [message ...]}.
   --usb is the whole of the USB console: it resolves to the one address the
   rig holds on that link, so every command reaches a rig over one cable."
  [command {:keys [opts] :as context}]
  (if-let [error (usb-error opts)]
    {:errors [error]}
    (build-command command (cond-> context
                             (:usb opts) (assoc-in [:opts :host] usb-console-address)))))

(defn usb-error
  "Return why one command line may not name a rig twice, or nil."
  [opts]
  (when (and (:usb opts) (:host opts))
    "invalid --usb: it names the rig on the USB console, so it is valid only without --host"))

(defn build-command
  "Build the operation of one command from a context whose host is resolved."
  [command {:keys [opts args elf-facts] :as context}]
  (case command
    :status
    (finish (collect [:host :format] context)
            [(unexpected-argument-error args)]
            (fn [values] {:operation :status :host (:host values) :format (:format values)}))

    :info
    (finish (collect [:host :speed-khz :format] context)
            [(unexpected-argument-error args)]
            (fn [values] {:operation :info
                          :host      (:host values)
                          :speed-khz (:speed-khz values)
                          :format    (:format values)}))

    :flash
    (let [elf (first args)]
      (finish (collect (into [:host] chip-and-speed-fields) context)
              (into [(when-not elf "missing argument: pass the path of the ELF file to flash")
                     (unexpected-argument-error (rest args))]
                    (when elf (elf-errors elf (elf-facts elf))))
              (fn [values] {:operation :flash
                            :host      (:host values)
                            :chip      (:chip values)
                            :speed-khz (:speed-khz values)
                            :elf       elf})))

    :erase
    (finish (collect (into [:host] chip-and-speed-fields) context)
            [(unexpected-argument-error args)]
            (fn [values] {:operation :erase
                          :host      (:host values)
                          :chip      (:chip values)
                          :speed-khz (:speed-khz values)}))

    :reset
    (finish (collect [:host] context)
            [(unexpected-argument-error args)]
            (fn [values] {:operation :reset :host (:host values)}))

    :log
    (finish (collect [:host] context)
            [(unexpected-argument-error args)]
            (fn [values] {:operation :log :host (:host values)}))

    :connect
    (let [[values errors]         (collect [:host :channel :local-port] context)
          channel                 (:channel values)
          rtt-path                (:rtt opts)
          rtt?                    (some? rtt-path)
          [rtt-values rtt-errors] (collect (connect-fields channel rtt?) context)]
      (finish [(merge values rtt-values) (into errors rtt-errors)]
              (into (conj (connect-option-errors opts channel rtt? "--rtt <elf>")
                          (unexpected-argument-error args))
                    (when rtt-path (elf-errors rtt-path (elf-facts rtt-path))))
              (fn [values]
                (cond-> {:operation      :connect
                         :host           (:host values)
                         :channel        (:channel values)
                         :local-port     (:local-port values)
                         :rtt            (when rtt-path
                                           {:elf       rtt-path
                                            :chip      (:chip values)
                                            :speed-khz (:speed-khz values)})
                         :pty?           (true? (:pty opts))
                         :reset-on-exit? (true? (:reset-on-exit opts))}
                  (= :uart (:channel values)) (assoc :baud (:baud values))
                  (= :usb (:channel values)) (assoc :usb-wait-seconds (:usb-wait-seconds values))))))

    :debug
    (finish (collect [:host :local-port] context)
            [(unexpected-argument-error args)]
            (fn [values] {:operation      :debug
                          :host           (:host values)
                          :local-port     (:local-port values)
                          :reset-on-exit? (true? (:reset-on-exit opts))}))

    :shell
    (finish (collect [:host] context)
            [(unexpected-argument-error args)]
            (fn [values] {:operation :shell :host (:host values)}))))

(defn connect-fields
  "Return the extra fields that one connect command resolves.

   The channel decides between a bit rate and a USB wait, and RTT decoding
   needs the target that probe-rs attaches to."
  [channel rtt?]
  (cond-> []
    (= :uart channel) (conj :baud)
    (= :usb channel) (conj :usb-wait-seconds)
    rtt? (into chip-and-speed-fields)))

(defn connect-option-errors
  "Return the connect options that belong to another channel or to no RTT at all.

   Both front ends refuse the same combinations, and each one names the RTT
   option the way its own command line spells it."
  [opts channel rtt? rtt-flag]
  [(when (and (:baud opts) (not= :uart channel))
     "invalid --baud: it is valid only with --channel uart")
   (when (and (:usb-wait-seconds opts) (not= :usb channel))
     "invalid --usb-wait-seconds: it is valid only with --channel usb")
   (when (and (:chip opts) (not rtt?))
     (str "invalid --chip: it is valid only with " rtt-flag))
   (when (and (:speed-khz opts) (not rtt?))
     (str "invalid --speed-khz: it is valid only with " rtt-flag))])

(def fields
  "How every option-carried value is named, defaulted, and validated."
  {:host             {:flag "--host" :label "<host>" :env-var "PROBETRON_HOST" :parse #'parse-host :required? true}
   :chip             {:flag "--chip" :label "<chip>" :env-var "PROBETRON_CHIP" :parse #'parse-chip :required? true}
   :speed-khz        {:flag  "--speed-khz"     :label   "<speed>"         :env-var "PROBETRON_SPEED_KHZ"
                      :parse #'parse-speed-khz :default default-speed-khz}
   :baud             {:flag  "--baud"     :label   "<baud>"     :env-var "PROBETRON_UART_BAUD"
                      :parse #'parse-baud :default default-baud}
   :usb-wait-seconds {:flag  "--usb-wait-seconds"     :label   "<seconds>"              :env-var "PROBETRON_USB_WAIT_SECONDS"
                      :parse #'parse-usb-wait-seconds :default default-usb-wait-seconds}
   :local-port       {:flag "--local-port" :label "<port>" :parse #'parse-local-port}
   :terminal-cols    {:flag "--terminal-cols" :label "<columns>" :parse #'parse-terminal-dimension}
   :terminal-rows    {:flag "--terminal-rows" :label "<rows>" :parse #'parse-terminal-dimension}
   :channel          {:flag "--channel" :label "<usb|uart>" :parse #'parse-channel :required? true}
   :format           {:flag "--format" :label "<text|edn>" :parse #'parse-format :default :text}})

(defn resolve-field
  "Resolve one field from the explicit option, the environment, and the default."
  [field {:keys [opts env use-env?]}]
  (let [{:keys [flag env-var parse default required?]} (get fields field)
        explicit                                       (get opts field)
        from-environment                               (when (and use-env? env-var) (get env env-var))
        raw                                            (if (some? explicit) explicit from-environment)
        source                                         (if (some? explicit) flag env-var)]
    (cond
      (some? raw) (let [result (parse raw)]
                    (if (contains? result :value)
                      result
                      {:error (str "invalid " source " " (pr-str raw) ": " (:error result))}))
      (some? default) {:value default}
      required? {:error (str "missing " flag ": pass " flag " " (:label (get fields field))
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
    (invalid "expected a probe-rs chip name")))

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

(defn parse-terminal-dimension
  "Accept one dimension of the client terminal in character cells."
  [value]
  (parse-integer value min-terminal-dimension max-terminal-dimension))

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
    (and (empty? extra)
         (or (nil? zone) (re-matches #"(?i)[a-z0-9-]+" zone))
         (re-matches #"(?i)[0-9a-f:]+" address)
         (<= 2 (count (filter #{\:} address)) 7)
         (not (str/includes? address ":::"))
         (<= (count (re-seq #"::" address)) 1)
         (every? #(<= (count %) 4) (str/split address #":" -1)))))
