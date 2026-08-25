(ns probetron.rig.session-fixture
  "Temporary appliance that the byte and RTT session tests own.

   Every device is a file, every owned helper is a shell stand-in that records
   its own process group and lives until a test releases the session, and the
   reset is a log line, so a test observes exactly what the session started and
   what cleanup removed.
   Its -main holds one whole session in its own process, which is how a test
   reaches the handled signal path."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.java.io :as io]
            [probetron.rig.fixture :as fixture]
            [probetron.rig.runner :as runner]
            [probetron.rig.session :as session])
  (:import (java.io ByteArrayInputStream)))

(declare appliance! create-appliance! upload-stream spawn-adapter helper-name stand-in-command
         record-reset! release! released? stop-all! recorded-pid await-pid! alive? signal!)

(def helper-names
  "The stand-ins that replace the two children of a connect session."
  ["bridge" "bridge-child" "rtt" "rtt-child"])

(defn -main
  "Hold one whole connect session in its own process until a signal arrives.

   The test writes the RTT ELF into the directory first, so the session reads
   the same bounded upload that a client would send."
  [directory & flags]
  (let [operation {:operation :connect
                   :channel :uart
                   :baud 115200
                   :rtt {:chip "RP235x" :speed-khz 1000 :elf :stdin}
                   :reset-on-exit? (boolean (some #{"--reset-on-exit"} flags))}]
    (System/exit (runner/execute! operation (appliance! directory (atom []) {:stdin :file})))))

(defn appliance!
  "Create a temporary appliance and return the runtime that owns it."
  [directory calls {:keys [stdin filesystem hardware]}]
  (let [path (fn [name] (str (fs/path directory name)))]
    (create-appliance! directory)
    (runner/runtime
     {:paths {:lock (path "target.lock")
              :active (path "active.edn")
              :uploads (path "uploads")}
      :executables {:probe-rs (path "probe-rs")
                    :gpioset (path "gpioset")
                    :socat (path "socat")}
      :hardware (merge {:spi-device (path "spidev0.0")
                        :gpio-chip (path "gpiochip0")
                        :uart-device (path "ttyAMA0")
                        :usb-device (path "probetron-dut")}
                       hardware)
      :filesystem (or filesystem {})
      :stdin (fn [] (upload-stream directory stdin))
      :spawn! (spawn-adapter directory calls)
      :perform session/perform!
      :reset-target! (fn [_runtime] (record-reset! directory))})))

(defn create-appliance!
  "Give the temporary appliance every executable, device, and directory it needs."
  [directory]
  (fs/create-dirs (fs/path directory "uploads"))
  (doseq [name ["probe-rs" "gpioset" "socat" "spidev0.0" "gpiochip0" "ttyAMA0" "probetron-dut"]]
    (let [file (fs/path directory name)]
      (when-not (fs/exists? file) (fs/create-file file)))))

(defn upload-stream
  "Return the standard input that carries one upload into the session."
  [directory stdin]
  (cond
    (= :file stdin) (io/input-stream (fs/file (fs/path directory "rtt.elf")))
    (bytes? stdin) (ByteArrayInputStream. ^bytes stdin)
    :else (ByteArrayInputStream. (byte-array 0))))

(defn spawn-adapter
  "Return a spawn function that records every owned helper and runs a stand-in for it.

   A lock or signal command is not a helper, so it reaches the real process
   unchanged and ownership stays exactly as production has it."
  [directory calls]
  (fn [argv opts]
    (let [argv (vec argv)]
      (if-let [helper (helper-name directory argv)]
        (do (swap! calls conj {:helper helper :argv (vec (rest argv)) :opts opts})
            (process/process (into [(first argv)] (stand-in-command directory helper))
                             {:out :inherit :err :inherit}))
        (process/process argv opts)))))

(defn helper-name
  "Name the appliance helper that one owned argv starts, or nil when it starts none."
  [directory argv]
  (when (< 1 (count argv))
    (condp = (second argv)
      (str (fs/path directory "socat")) "bridge"
      (str (fs/path directory "probe-rs")) "rtt"
      nil)))

(defn stand-in-command
  "Return the stand-in that replaces one owned helper.

   It records its own pid and the pid of a grandchild it leaves in the same
   process group, then lives until a test releases the session."
  [directory name]
  ["/bin/sh" "-c" (str "sleep 300 & echo $! > " directory "/" name "-child.pid; "
                       "echo $$ > " directory "/" name ".pid; "
                       "while [ ! -f " directory "/stop ]; do sleep 0.05; done")])

(defn record-reset!
  "Record that the optional reset on exit ran."
  [directory]
  (spit (fs/file (fs/path directory "reset.log")) "reset\n" :append true))

(defn release!
  "Let every stand-in of one session end."
  [directory]
  (when-not (released? directory)
    (fs/create-file (fs/path directory "stop"))))

(defn released?
  "Tell whether a test already released the stand-ins of one session."
  [directory]
  (fs/exists? (fs/path directory "stop")))

(defn stop-all!
  "Make sure no stand-in survives a test, even one that never cleaned up."
  [directory]
  (doseq [name helper-names]
    (when-let [pid (recorded-pid directory name)]
      (signal! "-KILL" pid))))

(defn signal!
  "Send one signal to one process."
  [signal pid]
  (process/shell {:continue true :out :string :err :string} "/bin/kill" signal (str pid)))

(defn recorded-pid
  "Return the pid that one stand-in recorded, or nil when it recorded none."
  [directory name]
  (fixture/pid-of directory (str name ".pid")))

(defn await-pid!
  "Wait until one stand-in has recorded its pid and return it."
  [directory name]
  (loop [attempts 1000]
    (if-let [pid (recorded-pid directory name)]
      pid
      (when (pos? attempts)
        (Thread/sleep 10)
        (recur (dec attempts))))))

(defn alive?
  "Tell whether a pid still runs."
  [pid]
  (fixture/alive? pid))
