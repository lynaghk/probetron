(ns probetron.rig.hardware
  "Pure description of the fixed Probetron appliance.

   The one DUT slot never moves, so every device, executable, selector, port,
   and limit is a constant here, and so is the argv of every hardware command
   the rig runs.
   Nothing here opens a device, a file, or a process."
  (:require [clojure.string :as str]
            [probetron.operation :as op]))

(declare probe-command with-pty shell-quote spi-probe-selector resources)

;; The absolute path of every hardware executable of the image.
(def probe-rs-executable "/usr/local/bin/probe-rs")
(def gpioset-executable "/usr/bin/gpioset")
(def socat-executable "/usr/bin/socat")

;; script comes from bsdutils, an Essential package, so the image always carries it.
(def script-executable "/usr/bin/script")

;; The size to give the forwarded pseudo-terminal, since it otherwise defaults to zero.
(def pty-rows 24)
(def pty-cols 80)

;; The one target slot, wired as the README table describes.
(def spi-device "/dev/spidev0.0")
(def spi-selector-prefix "0:0:")
(def probe-selector (str spi-selector-prefix spi-device))
(def swd-spi-device "/dev/spidev_swd*")
(def gpio-chip "/dev/gpiochip0")
(def run-gpio 26)
(def uart-device "/dev/ttyAMA0")
(def usb-device "/dev/probetron-dut")

(def dut-link
  "The loopback pseudo-terminal that the holder keeps live for the whole session.

   The holder opens the real DUT once and mirrors it here, so every byte client
   bridges to this always-live link instead of opening the DUT itself."
  "/run/probetron/dut")

;; The services that a locked session exposes on Pi loopback alone.
(def loopback op/rig-loopback)
(def byte-port op/rig-byte-port)
(def dap-port op/rig-dap-port)

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
   :swd-spi-device swd-spi-device
   :gpio-chip gpio-chip
   :run-gpio run-gpio
   :uart-device uart-device
   :usb-device usb-device
   :dut-link dut-link
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
  "Return the argv that identifies the target over the direct Linux SPI probe.

   probe-rs info ignores an explicit chip, so info clocks the bus at the shared
   speed alone and asks for the verbose component tree, which names the debug
   port and the vendor of a target that auto-detection cannot pin to one part."
  [executables hardware {:keys [speed-khz]}]
  (conj (probe-command executables hardware "info" {:speed-khz speed-khz}) "--verbose"))

(defn download-command
  "Return the argv that downloads one uploaded ELF, verifies it, and shows its progress.

   probe-rs draws its erase, program, and verify progress bars only when its
   output is a terminal, but the rig streams that output down a plain SSH pipe,
   so probe-rs would otherwise write nothing until the whole download finished.
   `script` gives probe-rs a pseudo-terminal, so the bars reach the client
   frame by frame across the roughly forty-five seconds the download takes."
  [executables hardware {:keys [chip speed-khz path]}]
  (with-pty (:script executables)
    (conj (probe-command executables hardware "download" {:chip chip :speed-khz speed-khz})
          "--verify" path)))

(defn rtt-command
  "Return the argv that decodes RTT of the firmware the target already runs.

   attach never downloads and never resets, so the decoder joins a target that
   keeps whatever state it had."
  [executables hardware {:keys [chip speed-khz path]}]
  (conj (probe-command executables hardware "attach" {:chip chip :speed-khz speed-khz}) path))

(defn dap-command
  "Return the argv of the probe-rs DAP server that one debug session serves.

   The server binds Pi loopback alone, and it keeps listening after every DAP
   client leaves, which is what probe-rs does whenever --single-session is
   absent, so an editor disconnects and connects again while the rig holds the
   target.
   Chip, speed, ELF, SVD, source, launch, and attach configuration reach
   probe-rs inside the DAP client request instead, so this argv names no
   project value at all."
  [{:keys [probe-rs]} {:keys [loopback dap-port]}]
  [probe-rs "dap-server" "--port" (str dap-port) "--ip" loopback])

(defn spi-probe-selector
  "Return the probe selector of one discovered Linux SPI bus.

   A DAP client request repeats it, so the operator names the bus that the rig
   discovered rather than any other SPI device of the Pi."
  [device]
  (str spi-selector-prefix device))

(def holder-loop
  "The shell that keeps the DUT mirror live across a DUT re-enumeration.

   socat mirrors the DUT to the loopback link, and this loop starts it again
   whenever it ends, exactly as a direct USB cable reconnects when the board
   re-enumerates. The loop waits for the device path before each mirror, so a
   reset or a re-enumeration that drops the DUT for a moment costs the channel a
   moment, not the whole session, and the device path is the udev symlink, so a
   DUT that returns under a new tty name is still the one the holder reopens.
   Each mirror brackets the DUT record with a link-up and a link-lost event, so
   a re-enumeration the rig did not cause leaves the same visible mark a flash
   does.
   $1 is socat, $2 the DUT device path, $3 the DUT address, $4 the loopback
   link, $5 the DUT record."
  (str/join " "
            ["stamp() { date -u +%Y-%m-%dT%H:%M:%SZ; };"
             "while :; do"
             "if [ -e \"$2\" ]; then"
             "printf '{:at #inst \"%s\" :event :dut-link-up}\\n' \"$(stamp)\" >> \"$5\";"
             "\"$1\" \"$3\" \"$4\";"
             "printf '{:at #inst \"%s\" :event :dut-link-lost}\\n' \"$(stamp)\" >> \"$5\";"
             "fi;"
             "sleep 0.5;"
             "done"]))

(defn channel-holder-command
  "Return the argv that holds the DUT channel live all session and reopens it if the DUT re-enumerates.

   The holder mirrors the DUT to a loopback pseudo-terminal, so the board
   settles one connection at the start of the session and every byte client
   after that attaches to a channel that is already live, exactly as a direct
   USB cable behaves. Holding the DUT open keeps the board's own bytes waiting
   on the link until a client reads them; reopening it on a re-enumeration keeps
   a reset or a brownout mid-session from bricking the channel until the client
   reconnects, and records each link that comes and goes."
  [{:keys [socat]} {:keys [dut-link]} device address dut-log]
  ["sh" "-c" holder-loop "probetron-holder"
   socat device address (str "PTY,link=" dut-link ",raw,echo=0") dut-log])

(defn byte-service-command
  "Return the argv of the rig byte listener.

   socat accepts on Pi loopback alone and bridges every accepted connection to
   the persistent link that the holder keeps open, so one client after another
   reaches a channel that is already live rather than opening the DUT itself."
  [{:keys [socat]} {:keys [loopback byte-port dut-link]}]
  [socat
   (str "TCP-LISTEN:" byte-port ",bind=" loopback "," byte-listen-options)
   (str "FILE:" dut-link ",raw,echo=0")])

(defn channel-address
  "Return the socat address of the DUT byte channel that the holder opens.

   Both channels are raw byte streams behind a stable path, and only the UART
   carries a bit rate. o-noctty opens the device without making it a controlling
   terminal: the holder is a session leader, so without it the first byte the
   board streams could raise a terminal signal and end the holder at once."
  [{:keys [uart-device usb-device]} {:keys [channel baud]}]
  (case channel
    :uart (str "FILE:" uart-device ",raw,echo=0,o-noctty,b" baud)
    :usb (str "FILE:" usb-device ",raw,echo=0,o-noctty")))

(defn channel-resource
  "Name the rig resource that carries one DUT byte channel."
  [channel]
  (case channel
    :uart :uart-device
    :usb :usb-device))

(defn erase-command
  "Return the argv that erases the whole target once and shows its progress.

   probe-rs draws an erase progress bar the same way it draws the download bars,
   so erase runs under `script` too and its bar reaches the client rather than
   nothing until the erase finishes."
  [executables hardware {:keys [chip speed-khz]}]
  (with-pty (:script executables)
    (probe-command executables hardware "erase" {:chip chip :speed-khz speed-khz})))

(defn probe-command
  "Return one probe-rs argv with the explicit probe, protocol, chip, and speed."
  [{:keys [probe-rs]} {:keys [probe-selector]} verb {:keys [chip speed-khz]}]
  (cond-> [probe-rs verb "--probe" probe-selector "--protocol" swd-protocol]
    chip (into ["--chip" chip])
    speed-khz (into ["--speed" (str speed-khz)])))

(defn with-pty
  "Wrap one command so it runs under a pseudo-terminal that forwards its output.

   `script` runs the command with a pseudo-terminal for its standard output and
   error, copies every byte the command writes to script's own standard output,
   and, under -e, returns the command's own exit status. -q drops script's own
   banner, and the /dev/null typescript discards the second copy script keeps.
   A program that prints only for a terminal therefore prints down a pipe too.

   The client reaches the rig without a terminal, so `script` cannot copy a size
   onto the new pseudo-terminal and it opens at zero rows and columns, at which
   probe-rs draws its bars but breaks the line between one finished bar and the
   next. `stty` sizes the pseudo-terminal before probe-rs starts, so every bar
   lands on its own line, and a semicolon keeps probe-rs's own exit status."
  [script argv]
  (let [sized (str "stty rows " pty-rows " cols " pty-cols "; "
                   (str/join " " (map shell-quote argv)))]
    [script "-q" "-e" "-c" sized "/dev/null"]))

(defn shell-quote
  "Quote one token so the shell that -c starts reads it as exactly one word."
  [token]
  (if (re-matches #"[A-Za-z0-9._/:=@%+,-]+" token)
    token
    (str "'" (str/replace token "'" "'\\''") "'")))

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
   :swd-spi-device {:in :hardware
                    :name "SWD SPI device"
                    :repair (str "enable SPI0 in the rig image and reinstall the udev rule that"
                                 " names the SWD bus, then wire SWCLK, SWDIO, and ground to"
                                 " header pins 23, 21, 19, and 20")}
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
                :repair (str "plug the DUT into the Pi, and check that the firmware exposes a"
                             " USB CDC device, because a board in BOOTSEL is mass storage"
                             " and matches no rule")}})

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
