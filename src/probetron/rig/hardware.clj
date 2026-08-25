(ns probetron.rig.hardware
  "Pure description of the fixed Probetron appliance.

   The one DUT slot never moves, so every device, executable, selector, port,
   and limit is a constant here, and so is the argv of every hardware command
   the rig runs.
   Nothing here opens a device, a file, or a process.")

(declare probe-command resources)

;; The absolute path of every hardware executable of the image.
(def probe-rs-executable "/usr/local/bin/probe-rs")
(def gpioset-executable "/usr/bin/gpioset")
(def socat-executable "/usr/bin/socat")

;; The one target slot, wired as the README table describes.
(def spi-device "/dev/spidev0.0")
(def probe-selector "0:0:/dev/spidev0.0")
(def gpio-chip "/dev/gpiochip0")
(def run-gpio 26)
(def uart-device "/dev/ttyAMA0")
(def usb-device "/dev/probetron-dut")

;; The services that a locked session exposes on Pi loopback alone.
(def loopback "127.0.0.1")
(def byte-port 5555)
(def dap-port 50000)

(def byte-listen-options
  "How the rig byte service listens.

   It reuses the address, forks one child for every accepted connection, and
   allows one child at a time, so exactly one byte client is active and the
   next one connects as soon as that client leaves."
  "reuseaddr,fork,max-children=1")

(def swd-protocol
  "The only wire protocol of the direct Linux SPI probe."
  "swd")

(def reset-pulse
  "How long gpioset holds the RUN line low before it toggles the line high and exits."
  "100ms")

(def max-elf-bytes
  "The largest upload that flash and RTT accept."
  (* 64 1024 1024))

(def upload-directory
  "The bounded volatile directory that carries one upload at a time."
  "/run/probetron/uploads")

(def defaults
  "The fixed hardware of the appliance, which a test replaces value by value."
  {:probe-selector probe-selector
   :spi-device spi-device
   :gpio-chip gpio-chip
   :run-gpio run-gpio
   :uart-device uart-device
   :usb-device usb-device
   :loopback loopback
   :byte-port byte-port
   :dap-port dap-port
   :reset-pulse reset-pulse
   :max-elf-bytes max-elf-bytes})

(defn version-command
  "Return the argv that asks probe-rs which release the image carries."
  [{:keys [probe-rs]}]
  [probe-rs "--version"])

(defn info-command
  "Return the argv that identifies the target over the direct Linux SPI probe."
  [executables hardware]
  (probe-command executables hardware "info" {}))

(defn download-command
  "Return the argv that downloads one uploaded ELF and verifies it on the target."
  [executables hardware {:keys [chip speed-khz path]}]
  (conj (probe-command executables hardware "download" {:chip chip :speed-khz speed-khz})
        "--verify" path))

(defn rtt-command
  "Return the argv that decodes RTT of the firmware the target already runs.

   attach never downloads and never resets, so the decoder joins a target that
   keeps whatever state it had."
  [executables hardware {:keys [chip speed-khz path]}]
  (conj (probe-command executables hardware "attach" {:chip chip :speed-khz speed-khz}) path))

(defn byte-service-command
  "Return the argv of the rig byte listener.

   socat accepts on Pi loopback alone, and it opens the channel address again
   for every accepted connection, so a DUT that re-enumerated resolves once more."
  [{:keys [socat]} {:keys [loopback byte-port]} address]
  [socat
   (str "TCP-LISTEN:" byte-port ",bind=" loopback "," byte-listen-options)
   address])

(defn channel-address
  "Return the socat address of one DUT byte channel.

   Both channels are raw byte streams behind a stable path, and only the UART
   carries a bit rate."
  [{:keys [uart-device usb-device]} {:keys [channel baud]}]
  (case channel
    :uart (str "FILE:" uart-device ",raw,echo=0,b" baud)
    :usb (str "FILE:" usb-device ",raw,echo=0")))

(defn channel-resource
  "Name the rig resource that carries one DUT byte channel."
  [channel]
  (case channel
    :uart :uart-device
    :usb :usb-device))

(defn erase-command
  "Return the argv that erases the whole target once."
  [executables hardware {:keys [chip speed-khz]}]
  (probe-command executables hardware "erase" {:chip chip :speed-khz speed-khz}))

(defn probe-command
  "Return one probe-rs argv with the explicit probe, protocol, chip, and speed."
  [{:keys [probe-rs]} {:keys [probe-selector]} verb {:keys [chip speed-khz]}]
  (cond-> [probe-rs verb "--probe" probe-selector]
    chip (into ["--chip" chip])
    true (into ["--protocol" swd-protocol])
    speed-khz (into ["--speed" (str speed-khz)])))

(defn reset-command
  "Return the argv that pulses the RUN line of the target.

   gpioset drives the line low, waits one pulse, toggles it high, and exits on
   the trailing zero period, so the pull-up on RUN restarts the firmware.
   libgpiod v2 gpioset holds an output for the life of the process, so the
   trailing zero is what makes this a pulse rather than an indefinite hold."
  [{:keys [gpioset]} {:keys [gpio-chip run-gpio reset-pulse]}]
  [gpioset "--chip" gpio-chip "--toggle" (str reset-pulse ",0") (str run-gpio "=0")])

(def resources
  "Every rig resource that a hardware operation opens, and where it comes from."
  {:probe-rs {:in :executables
              :name "probe-rs executable"
              :repair "reinstall the rig image, which installs probe-rs"}
   :gpioset {:in :executables
             :name "gpioset executable"
             :repair "reinstall the rig image, which installs the gpiod tools"}
   :spi-device {:in :hardware
                :name "SPI device"
                :repair (str "enable SPI0 in the rig image and wire SWCLK, SWDIO, and ground"
                             " to header pins 23, 21, 19, and 20")}
   :gpio-chip {:in :hardware
               :name "GPIO chip"
               :repair "reboot the rig and wire GPIO26 on header pin 37 to the DUT RUN line"}
   :socat {:in :executables
           :name "socat executable"
           :repair "reinstall the rig image, which installs socat"}
   :uart-device {:in :hardware
                 :name "UART device"
                 :repair (str "enable UART0 in the rig image and wire the DUT RX and TX lines"
                              " to header pins 8 and 10")}
   :usb-device {:in :hardware
                :name "DUT USB device"
                :repair (str "plug the DUT into the fixed Pi USB port that the image udev rule"
                             " names, and check that the firmware exposes a USB CDC device")}})

(defn resource-path
  "Return the absolute path that a runtime gives one rig resource."
  [runtime resource]
  (get-in runtime [(:in (resources resource)) resource]))

(defn missing-resource-message
  "Name a rig resource that a hardware operation needs and say what repairs it."
  [resource path]
  (let [{:keys [name repair]} (resources resource)]
    (str "missing " name " " path ": " repair)))

(defn unreadable-resource-message
  "Name a rig resource that exists but that the rig cannot open, and say what repairs it."
  [resource path]
  (let [{:keys [name repair]} (resources resource)]
    (str "cannot read " name " " path ": " repair)))
