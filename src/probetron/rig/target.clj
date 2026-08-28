(ns probetron.rig.target
  "Imperative shell of the short target operations.

   info, flash, erase, and reset already own the target lock when they run, so
   each one opens the hardware once, preserves what probe-rs said, and gives
   the target back.
   Every absolute command, device, and volatile path arrives in the runtime, so
   a test substitutes a temporary appliance for the fixed one."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [probetron.rig.elf :as elf]
            [probetron.rig.hardware :as hardware]
            [probetron.rig.information :as information]
            [probetron.rig.runner :as runner]
            [probetron.operation :as op]
            [probetron.version :as version])
  (:import (java.io InputStream OutputStream)))

(declare report-information! flash! erase! pulse-reset!
         capture! upload-path receive-elf! receive-session-elf! read-uint32! with-upload!
         copy-bounded! elf-refusal oversize-message missing-status refuse!)

(defn perform!
  "Carry out one short operation that already owns the target."
  [operation session]
  (let [runtime (:runtime session)]
    (case (:operation operation)
      :info (report-information! operation runtime)
      :flash (flash! operation runtime)
      :erase (erase! operation runtime)
      :reset (pulse-reset! runtime))))

(defn report-information!
  "Report what the rig is and what the probe finds on the SWD bus."
  [{:keys [format speed-khz]} {:keys [executables hardware paths filesystem run!] :as runtime}]
  (or (missing-status runtime [:probe-rs :spi-device])
      (let [read-file (:read-file filesystem)
            release   (capture! run! (hardware/version-command executables))
            probe     (capture! run! (hardware/info-command executables hardware {:speed-khz speed-khz}))]
        (println (information/render
                  (information/report {:probetron      (version/stamp-line (version/describe))
                                       :babashka       (System/getProperty "babashka.version")
                                       :probe-rs       (:output release)
                                       :os             (read-file (:os-release paths))
                                       :hostname       (read-file (:hostname paths))
                                       :machine-id     (read-file (:machine-id paths))
                                       :probe-selector (:probe-selector hardware)
                                       :protocol       hardware/swd-protocol
                                       :target         (:output probe)})
                  format))
        (if (zero? (:exit probe)) op/exit-ok op/exit-failure))))

(defn flash!
  "Download one uploaded ELF, verify it, and start the firmware it verified.

   The upload validates before probe-rs opens the target, and the RUN line
   pulses only after probe-rs verified what it wrote."
  [{:keys [chip speed-khz]} {:keys [executables hardware run!] :as runtime}]
  (or (missing-status runtime [:probe-rs :spi-device :gpioset :gpio-chip])
      (with-upload!
        (upload-path runtime)
        (fn [path]
          (if-let [error (receive-elf! runtime path)]
            (refuse! error)
            (let [exit (:exit (run! (hardware/download-command
                                     executables hardware
                                     {:chip chip :speed-khz speed-khz :path path})
                                    {}))]
              (if (zero? exit)
                (pulse-reset! runtime)
                exit)))))))

(defn erase!
  "Erase the target once and give back exactly the status probe-rs returned."
  [{:keys [chip speed-khz]} {:keys [executables hardware run!] :as runtime}]
  (or (missing-status runtime [:probe-rs :spi-device])
      (:exit (run! (hardware/erase-command executables hardware
                                           {:chip chip :speed-khz speed-khz})
                   {}))))

(defn pulse-reset!
  "Hold the RUN line of the target low for the reset pulse and release it."
  [{:keys [executables hardware run!] :as runtime}]
  (or (missing-status runtime [:gpioset :gpio-chip])
      (let [exit (:exit (run! (hardware/reset-command executables hardware) {}))]
        (if (zero? exit)
          op/exit-ok
          (refuse! (str "the reset of the target failed with gpioset exit " exit
                        ": check that GPIO26 on header pin 37 reaches the DUT RUN line"))))))

(defn upload-path
  "Return the unique volatile file that carries one upload."
  [{:keys [paths]}]
  (let [directory (fs/path (:uploads paths))]
    (fs/create-dirs directory)
    (str (fs/path directory (str "upload-" (random-uuid) ".elf")))))

(defn receive-elf!
  "Read a short operation's ELF from standard input, delimited by end of input.

   A short operation reads its upload to end of input and then ends, so it needs
   no more of standard input and lets the client close it. It stops one byte
   past the limit, so an oversized upload never fills the volatile filesystem,
   and it returns the reason it refuses an upload, or nil."
  [{:keys [hardware stdin]} path]
  (let [limit (:max-elf-bytes hardware)
        size  (copy-bounded! (stdin) path (inc limit))]
    (if (> size limit) (oversize-message limit) (elf-refusal path))))

(defn receive-session-elf!
  "Read a tethered session's ELF from standard input, delimited by a length.

   A session holds standard input open past its upload, because its end of input
   is the client-gone signal the tether waits on (see the rig runner). It
   therefore cannot delimit the ELF by end of input the way a short operation
   does; the client frames the ELF with a four-byte big-endian length instead,
   so the rig reads exactly the ELF and leaves the rest of standard input to the
   tether. A truncated or oversized frame is refused before any hardware opens."
  [{:keys [hardware stdin]} path]
  (let [limit  (:max-elf-bytes hardware)
        in     (stdin)
        length (read-uint32! in)]
    (cond
      (nil? length) "the RTT upload ended before it declared its length"
      (neg? length) "the RTT upload declared a negative length"
      (> length limit) (oversize-message limit)
      :else (let [size (copy-bounded! in path length)]
              (if (< size length)
                "the RTT upload ended before the ELF length it declared"
                (elf-refusal path))))))

(defn read-uint32!
  "Read one four-byte big-endian length from a stream, or nil at end of input."
  [^InputStream in]
  (try (.readInt (java.io.DataInputStream. in))
       (catch java.io.EOFException _ nil)))

(defn oversize-message
  "Explain that an upload is over the ELF size limit."
  [limit]
  (str "the upload is larger than the " limit "-byte ELF limit: flash a smaller ELF file"))

(defn elf-refusal
  "Return why the bytes at path are not a flashable RP2350 ELF, or nil."
  [path]
  (when-let [reason (elf/error (fs/read-all-bytes path))]
    (str "the upload is not a flashable RP2350 ELF file: " reason)))

(defn with-upload!
  "Run the body that owns one upload and remove the upload on every exit path.

   The hook is in place before the first byte arrives, so a client that
   disconnects in the middle of an upload leaves nothing behind either."
  [path body]
  (let [remove! (fn [] (fs/delete-if-exists path))
        hook    (Thread. ^Runnable remove!)]
    (.addShutdownHook (Runtime/getRuntime) hook)
    (try
      (body path)
      (finally
        (remove!)
        (try (.removeShutdownHook (Runtime/getRuntime) hook)
             (catch IllegalStateException _ nil))))))

(defn copy-bounded!
  "Copy at most a bounded number of bytes into a file and return how many arrived."
  [stream path limit]
  (with-open [out (io/output-stream (fs/file path))]
    (let [buffer (byte-array 65536)]
      (loop [total 0]
        (if (<= limit total)
          total
          (let [read (.read ^InputStream stream buffer 0
                            (int (min (alength buffer) (- limit total))))]
            (if (neg? read)
              total
              (do (.write ^OutputStream out buffer 0 read)
                  (recur (+ total read))))))))))

(defn capture!
  "Run one hardware command and keep everything it wrote."
  [run! argv]
  (let [{:keys [exit out err]} (run! argv {:out :string :err :string})]
    {:exit exit :output (str (or out "") (or err ""))}))

(defn missing-status
  "Return the unavailable status of the first rig resource that is absent."
  [{:keys [filesystem] :as runtime} resources]
  (some (fn [resource]
          (let [path (hardware/resource-path runtime resource)]
            (when-not ((:exists? filesystem) path)
              (runner/fail! (hardware/missing-resource-message resource path)))))
        resources))

(defn refuse!
  "Report a refused upload and return the failure status."
  [message]
  (runner/warn! message)
  op/exit-failure)
