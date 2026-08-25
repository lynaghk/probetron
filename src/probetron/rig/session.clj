(ns probetron.rig.session
  "Imperative shell of the locked byte and RTT session.

   connect already owns the target lock when it runs, so it opens the one DUT
   byte channel, publishes it on Pi loopback alone, decodes RTT beside it when
   the client asked for it, and holds the target until the outer SSH command
   ends.
   Every device, executable, and volatile path arrives in the runtime, so a
   test substitutes a temporary appliance for the fixed one."
  (:require [probetron.rig.hardware :as hardware]
            [probetron.rig.runner :as runner]
            [probetron.rig.target :as target]
            [probetron.operation :as op]))

(declare connect! required-resources with-rtt-upload! bridge! start-bridge! start-decoder!
         channel-status! await-device! await-bridge! helper-options)

(def operations
  "The long sessions that this shell carries out."
  #{:connect})

(def device-poll-ms
  "How often the session looks for the DUT USB device while it waits for it."
  100)

(defn perform!
  "Carry out one long session that already owns the target."
  [operation session]
  (case (:operation operation)
    :connect (connect! operation session)))

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

(defn required-resources
  "Return the rig resources that one connect session needs before it opens anything."
  [{:keys [rtt]}]
  (cond-> [:socat]
    rtt (into [:probe-rs :spi-device])))

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
        (if-let [error (target/receive-elf! runtime path)]
          (target/refuse! error)
          (body path))))))

(defn bridge!
  "Open the byte channel, start both children, and hold the target while they live."
  [operation session rtt-path]
  (let [{:keys [hardware] :as runtime} (:runtime session)]
    (or (channel-status! operation runtime)
        (let [bridge (start-bridge! operation session)]
          (when rtt-path (start-decoder! operation session rtt-path))
          (await-bridge! bridge hardware (:stopping? session))))))

(defn start-bridge!
  "Start the rig byte listener that publishes the DUT channel on Pi loopback.

   The listener keeps accepting for as long as the outer rig operation lives,
   so one byte client after another reaches the same DUT."
  [operation {:keys [runtime start-helper!]}]
  (let [{:keys [executables hardware]} runtime]
    (start-helper! (hardware/byte-service-command
                    executables hardware (hardware/channel-address hardware operation))
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

(defn await-bridge!
  "Hold the target for as long as the byte listener lives.

   Cleanup owns the decoder, so a decoder that stops never closes the bridge,
   and a listener that stops never leaves the lock behind.
   A listener that cleanup itself reaped ended the session it served, so it
   reports success rather than a service that failed."
  [bridge {:keys [loopback byte-port]} stopping?]
  (let [exit (.waitFor ^Process (:proc bridge))]
    (if (or (zero? exit) (stopping?))
      op/exit-ok
      (do (runner/warn! (str "the byte service on " loopback ":" byte-port
                             " stopped with socat exit " exit
                             ": check that no other operation already listens there"))
          op/exit-failure))))
