(ns probetron.rig.session
  "Imperative shell of the locked byte, RTT, and DAP sessions.

   connect and debug already own the target lock when they run, so each one
   publishes its service on Pi loopback alone and holds the target until the
   outer SSH command ends: connect opens the DUT byte channel and decodes RTT
   beside it when the client asked for it, while debug serves the probe-rs DAP
   protocol across one DAP client after another.
   Every device, executable, and volatile path arrives in the runtime, so a
   test substitutes a temporary appliance for the fixed one."
  (:require [probetron.rig.hardware :as hardware]
            [probetron.rig.runner :as runner]
            [probetron.rig.target :as target]
            [probetron.operation :as op]))

(declare connect! debug! required-resources with-rtt-upload! bridge! start-holder! start-bridge!
         start-decoder! start-server! swd-device announce-probe! channel-status! await-device!
         await-service! services helper-options)

(def operations
  "The long sessions that this shell carries out."
  #{:connect :debug})

(def device-poll-ms
  "How often the session looks for the DUT USB device while it waits for it."
  100)

(defn perform!
  "Carry out one long session that already owns the target."
  [operation session]
  (case (:operation operation)
    :connect (connect! operation session)
    :debug (debug! operation session)))

(defn connect!
  "Bridge the DUT byte channel, decode the optional RTT beside it, and hold the target.

   The optional RTT ELF arrives and validates before any hardware opens, so a
   refused upload costs the DUT nothing."
  [{:keys [rtt] :as operation} session]
  (let [runtime (:runtime session)]
    (or (target/missing-status runtime (required-resources operation))
        (if rtt
          (with-rtt-upload! operation runtime #(bridge! operation session %))
          (bridge! operation session nil)))))

(defn debug!
  "Serve the DAP protocol on Pi loopback and hold the target across every client of it.

   Discovery accepts only the udev-named SWD bus, so a DAP client request that
   names a probe reaches that one bus and no other SPI device of the Pi, and
   the rig announces the selector that such a request repeats."
  [operation session]
  (let [runtime (:runtime session)]
    (or (target/missing-status runtime (required-resources operation))
        (if-let [device (swd-device runtime)]
          (do (announce-probe! device)
              ;; A debug session sends no upload, so standard input is the tether
              ;; from the start; its end frees the target across DAP clients.
              ((:watch-client! session))
              (await-service! (start-server! session) :dap
                              (:hardware runtime) (:stopping? session)))
          (runner/fail! (hardware/missing-resource-message
                         :swd-spi-device (hardware/resource-path runtime :swd-spi-device)))))))

(defn swd-device
  "Return the one SWD SPI device that the rig discovered, or nil when it has none."
  [{:keys [hardware filesystem]}]
  (first (sort ((:glob filesystem) (:swd-spi-device hardware)))))

(defn announce-probe!
  "Name the probe selector and the protocol that a DAP client request repeats."
  [device]
  (println (str "probe: " (hardware/spi-probe-selector device) " " hardware/swd-protocol))
  (flush))

(defn start-server!
  "Start the multi-session probe-rs DAP server that publishes DAP on Pi loopback.

   The server keeps listening for as long as the outer rig operation lives, so
   an editor disconnects and connects again without releasing the target lock
   and without resetting the DUT."
  [{:keys [runtime start-helper!]}]
  (let [{:keys [executables hardware]} runtime]
    (start-helper! (hardware/dap-command executables hardware) helper-options)))

(defn required-resources
  "Return the rig resources that one session needs before it opens anything."
  [{:keys [operation rtt]}]
  (case operation
    :connect (cond-> [:socat]
               rtt (into [:probe-rs :spi-device]))
    :debug [:probe-rs]))

(defn with-rtt-upload!
  "Receive and validate the bounded RTT ELF, then run the body that owns it.

   The upload takes the same volatile path as a flash, and it disappears on
   every exit path, including a handled signal."
  [{:keys [rtt]} runtime body]
  (if-not (and (:chip rtt) (:speed-khz rtt))
    (target/refuse! "invalid --rtt: RTT decoding needs --chip and --speed-khz")
    (target/with-upload!
      (target/upload-path runtime)
      (fn [path]
        (if-let [error (target/receive-session-elf! runtime path)]
          (target/refuse! error)
          (body path))))))

(defn bridge!
  "Hold the DUT open, publish it on Pi loopback, and hold the target while they live."
  [operation session rtt-path]
  (let [{:keys [hardware] :as runtime} (:runtime session)]
    (or (channel-status! operation runtime)
        (do
          ;; Watch the client now that its upload, if any, is read: whatever is
          ;; left on standard input is the tether, and its end frees the target.
          ((:watch-client! session))
          (start-holder! operation session)
          (let [bridge (start-bridge! session)]
            (when rtt-path (start-decoder! operation session rtt-path))
            (await-service! bridge :byte hardware (:stopping? session)))))))

(defn start-holder!
  "Open the DUT channel once and hold it live for the whole session.

   The holder settles the board at the start of the session and keeps the DUT
   bytes waiting on the loopback link, so a byte client attaches to a channel
   that is already live rather than reopening the DUT itself. Cleanup owns it, so
   it never outlives the session."
  [operation {:keys [runtime start-helper!]}]
  (let [{:keys [executables hardware]} runtime]
    (start-helper! (hardware/channel-holder-command
                    executables hardware (hardware/channel-address hardware operation))
                   helper-options)))

(defn start-bridge!
  "Start the rig byte listener that publishes the persistent DUT link on Pi loopback.

   The listener keeps accepting for as long as the outer rig operation lives, so
   one byte client after another reaches the same live channel that the holder
   keeps open."
  [{:keys [runtime start-helper!]}]
  (let [{:keys [executables hardware]} runtime]
    (start-helper! (hardware/byte-service-command executables hardware)
                   helper-options)))

(defn start-decoder!
  "Start probe-rs RTT decoding beside the byte listener.

   The decoder attaches over the explicit Linux SPI probe and the SWD protocol,
   and it writes decoded text to rig standard output and standard error alone."
  [{:keys [rtt]} {:keys [runtime start-helper!]} path]
  (let [{:keys [executables hardware]} runtime]
    (start-helper! (hardware/rtt-command executables hardware (assoc rtt :path path))
                   helper-options)))

(def helper-options
  "How an owned session child reaches the operator.

   Neither child inherits the standard input of the rig, which carried the
   upload, and neither one writes DUT bytes anywhere but the TCP service."
  {:out :inherit :err :inherit})

(defn channel-status!
  "Return the unavailable status of a byte channel the rig cannot open, or nil.

   The USB channel waits for the DUT to enumerate, while the UART is a fixed
   device of the image that either exists now or never will."
  [{:keys [channel usb-wait-seconds]} {:keys [filesystem] :as runtime}]
  (let [resource (hardware/channel-resource channel)
        path (hardware/resource-path runtime resource)]
    (when (= :usb channel)
      (await-device! filesystem path (or usb-wait-seconds 0)))
    (cond
      (not ((:exists? filesystem) path))
      (runner/fail! (hardware/missing-resource-message resource path))

      (not ((:readable? filesystem) path))
      (runner/fail! (hardware/unreadable-resource-message resource path)))))

(defn await-device!
  "Wait up to the requested interval for the DUT USB device to enumerate."
  [filesystem path seconds]
  (let [deadline (+ (System/nanoTime) (* (long seconds) 1000000000))]
    (loop []
      (when (and (not ((:exists? filesystem) path)) (< (System/nanoTime) deadline))
        (Thread/sleep device-poll-ms)
        (recur)))))

(defn await-service!
  "Hold the target for as long as the loopback service of one session lives.

   Cleanup owns every other child, so an RTT decoder that stops never closes
   the byte bridge, and a service that stops never leaves the lock behind.
   A service that cleanup itself reaped ended the session it served, so it
   reports success rather than a service that failed."
  [helper service hardware stopping?]
  (let [{:keys [name program port]} (services service)
        exit (.waitFor ^Process (:proc helper))]
    (if (or (zero? exit) (stopping?))
      op/exit-ok
      (do (runner/warn! (str "the " name " on " (:loopback hardware) ":" (get hardware port)
                             " stopped with " program " exit " exit
                             ": check that no other operation already listens there"))
          op/exit-failure))))

(def services
  "How each long session names the loopback service that holds the target."
  {:byte {:name "byte service" :program "socat" :port :byte-port}
   :dap {:name "DAP service" :program "probe-rs" :port :dap-port}})
