(ns probetron.rig.session-fixture
  "Temporary appliance that the byte, RTT, and DAP session tests own.

   Every device is a file, every owned helper is a stand-in that records its
   own process group and lives until a test releases the session, and the reset
   is a log line, so a test observes exactly what the session started and what
   cleanup removed.
   The DAP stand-in listens for real on an ephemeral loopback port and survives
   every client of it, which is how a test watches a session keep the target
   across a DAP disconnect.
   Its -main holds one whole session in its own process, which is how a test
   reaches the handled signal path."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [probetron.rig.fixture :as fixture]
            [probetron.rig.runner :as runner]
            [probetron.rig.session :as session])
  (:import (java.io ByteArrayInputStream)))

(declare appliance! create-appliance! write-listener! listener-source device-names upload-stream
         spawn-adapter helper-name stand-in-command record-reset! release! released? stop-all!
         recorded-pid await-pid! await-file! alive? signal!)

(def helper-names
  "The stand-ins that replace the owned children of each long session."
  {:connect ["bridge" "bridge-child" "rtt" "rtt-child"]
   :debug ["dap" "dap-child"]})

(defn -main
  "Hold one whole long session in its own process until a signal arrives.

   The test writes the RTT ELF into the directory first, so a connect session
   reads the same bounded upload that a client would send."
  [directory command & flags]
  (let [reset-on-exit? (boolean (some #{"--reset-on-exit"} flags))
        operation (case command
                    "connect" {:operation :connect
                               :channel :uart
                               :baud 115200
                               :rtt {:chip "RP235x" :speed-khz 1000 :elf :stdin}
                               :reset-on-exit? reset-on-exit?}
                    "debug" {:operation :debug :reset-on-exit? reset-on-exit?})
        options {:stdin (when (= "connect" command) :file)}]
    (System/exit (runner/execute! operation (appliance! directory (atom []) options)))))

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
                        :swd-spi-device (path "spidev_swd*")
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
  (write-listener! directory)
  (doseq [name device-names]
    (let [file (fs/path directory name)]
      (when-not (fs/exists? file) (fs/create-file file)))))

(def device-names
  "Every file that stands in for an executable or a device of the appliance."
  ["probe-rs" "gpioset" "socat" "spidev0.0" "spidev_swd0" "gpiochip0" "ttyAMA0" "probetron-dut"])

(defn write-listener!
  "Write the multi-session listener that stands in for the probe-rs DAP server."
  [directory]
  (spit (fs/file (fs/path directory "dap-listener.clj")) listener-source))

(def listener-source
  "A DAP server stand-in that keeps listening after every client leaves.

   It publishes the ephemeral port it took, records one line for every client
   that connected and disconnected, and ends when a test releases the session."
  (str/join "\n"
            ["(let [directory (first *command-line-args*)"
             "      server (java.net.ServerSocket. 0 4 (java.net.InetAddress/getByName \"127.0.0.1\"))]"
             "  (future"
             "    (loop []"
             "      (with-open [socket (.accept server)]"
             "        (.read (.getInputStream socket))"
             "        (spit (str directory \"/dap.sessions\") \"session\\n\" :append true))"
             "      (recur)))"
             "  (spit (str directory \"/dap.port\") (str (.getLocalPort server)))"
             "  (loop []"
             "    (when-not (.exists (java.io.File. (str directory \"/stop\")))"
             "      (Thread/sleep 50)"
             "      (recur))))"]))

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
  "Name the appliance helper that one owned argv starts, or nil when it starts none.

   Both probe-rs sessions run the same executable, so the verb tells the RTT
   decoder and the DAP server apart."
  [directory argv]
  (when (< 1 (count argv))
    (condp = (second argv)
      (str (fs/path directory "socat")) "bridge"
      (str (fs/path directory "probe-rs")) (if (= "dap-server" (nth argv 2 nil)) "dap" "rtt")
      nil)))

(defn stand-in-command
  "Return the stand-in that replaces one owned helper.

   Each one records its own pid and the pid of a grandchild it leaves in the
   same process group, and only the DAP stand-in also serves loopback clients."
  [directory name]
  ["/bin/sh" "-c" (str "sleep 300 & echo $! > " directory "/" name "-child.pid; "
                       "echo $$ > " directory "/" name ".pid; "
                       (if (= "dap" name)
                         (str "exec bb " directory "/dap-listener.clj " directory)
                         (str "while [ ! -f " directory "/stop ]; do sleep 0.05; done")))])

(defn record-reset!
  "Record that the optional reset on exit ran, and what was still alive when it did."
  [directory]
  (spit (fs/file (fs/path directory "reset.log"))
        (str "reset"
             (str/join (for [name (mapcat val helper-names)
                             :let [pid (recorded-pid directory name)]
                             :when pid]
                         (str " " name "=" (if (alive? pid) "alive" "gone"))))
             "\n")
        :append true))

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
  (doseq [name (mapcat val helper-names)]
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

(defn await-file!
  "Wait until one file of the appliance carries text and return that text."
  [directory name]
  (let [file (fs/path directory name)]
    (loop [attempts 1000]
      (if (pos? (if (fs/exists? file) (fs/size file) 0))
        (str/trim (slurp (fs/file file)))
        (when (pos? attempts)
          (Thread/sleep 10)
          (recur (dec attempts)))))))

(defn alive?
  "Tell whether a pid still runs."
  [pid]
  (fixture/alive? pid))
