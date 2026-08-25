(ns probetron.rig.target-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [probetron.rig.hardware :as hardware]
            [probetron.rig.runner :as runner]
            [probetron.rig.target :as target]
            [probetron.operation :as op]
            [probetron.version :as version])
  (:import (java.io ByteArrayInputStream PipedInputStream StringWriter)))

(declare with-rig! run-rig! appliance! by-command command-name
         elf put8! put16! put32! flash-operation probe-rs gpioset)

(def flash-operation
  {:operation :flash :chip "RP235x" :speed-khz 4000 :elf :stdin})

(deftest information-identifies-the-rig-and-the-target
  (let [{:keys [exit out calls directory]}
        (with-rig! {:operation :info :format :text}
                    {:responses (by-command
                                 {:version {:exit 0 :out "probe-rs 0.32.0\n"}
                                  :info {:exit 0 :out "Probe: linux SPI\nARM Chip with debug port\n"}})})]
    (is (= op/exit-ok exit))
    (testing "the probe-rs argv names the Linux SPI selector and the SWD protocol"
      (is (= [[(probe-rs directory) "--version"]
              [(probe-rs directory) "info" "--probe" "0:0:/dev/spidev0.0" "--protocol" "swd"]]
             calls)))
    (is (str/includes? out (str "probetron: " version/probetron-version)))
    (is (str/includes? out (str "babashka: " (System/getProperty "babashka.version"))))
    (is (str/includes? out "probe-rs: probe-rs 0.32.0"))
    (is (str/includes? out "os: Debian GNU/Linux 13 (trixie)"))
    (is (str/includes? out "hostname: probetron-01"))
    (is (str/includes? out "machine-id: 0123456789abcdef0123456789abcdef"))
    (is (str/includes? out "probe: 0:0:/dev/spidev0.0 swd"))
    (is (str/includes? out "ARM Chip with debug port"))))

(deftest information-in-edn-carries-stable-keys
  (let [{:keys [out]} (with-rig! {:operation :info :format :edn}
                                  {:responses (by-command
                                               {:version {:exit 0 :out "probe-rs 0.32.0"}
                                                :info {:exit 0 :out "ARM Chip with debug port"}})})
        report (edn/read-string out)]
    (is (= #{:probetron :babashka :probe-rs :os :hostname :machine-id :probe :target} (set (keys report))))
    (is (= version/probetron-version (:probetron report)))
    (is (= "probe-rs 0.32.0" (:probe-rs report)))
    (is (= "Debian GNU/Linux 13 (trixie)" (:os report)))
    (is (= "probetron-01" (:hostname report)))
    (is (= "0123456789abcdef0123456789abcdef" (:machine-id report)))
    (is (= {:selector "0:0:/dev/spidev0.0" :protocol "swd"} (:probe report)))
    (is (= "ARM Chip with debug port" (:target report)))))

(deftest information-owns-the-target-while-it-reads-the-probe
  (let [directory (str (fs/create-temp-dir {:prefix "probetron-target"}))
        owner (atom nil)]
    (try
      (run-rig! directory {:operation :info :format :text}
                 {:responses (fn [argv]
                               (when (= "info" (second argv))
                                 (reset! owner (slurp (fs/file (fs/path directory "active.edn")))))
                               {:exit 0})})
      (is (str/includes? (str @owner) ":info") "info must hold the transaction lock")
      (is (not (fs/exists? (fs/path directory "active.edn"))) "and give the target back")
      (finally (fs/delete-tree directory)))))

(deftest information-reports-a-probe-that-does-not-answer
  (let [{:keys [exit out]}
        (with-rig! {:operation :info :format :text}
                    {:responses (by-command {:info {:exit 1 :err "Error: no debug probe found"}})})]
    (is (= op/exit-failure exit))
    (is (str/includes? out "no debug probe found"))))

(deftest flash-verifies-the-download-and-then-pulses-the-reset-line
  (let [image (elf)
        uploaded (atom nil)
        {:keys [exit calls directory uploads]}
        (with-rig! flash-operation
                    {:stdin image
                     :responses (fn [argv]
                                  (when (= "download" (second argv))
                                    (reset! uploaded (fs/read-all-bytes (last argv))))
                                  {:exit 0})})
        [download reset] calls]
    (is (= op/exit-ok exit))
    (is (= 2 (count calls)) "flash downloads once and resets once")
    (is (= [(probe-rs directory) "download" "--probe" "0:0:/dev/spidev0.0" "--chip" "RP235x"
            "--protocol" "swd" "--speed" "4000" "--verify"]
           (vec (butlast download))))
    (is (str/starts-with? (last download) (str (fs/path directory "uploads"))))
    (is (= (seq image) (seq @uploaded)) "probe-rs reads exactly what the client sent")
    (is (= [(gpioset directory) "--chip" (str (fs/path directory "gpiochip0"))
            "--toggle" "100ms,0" "26=0"]
           reset))
    (is (empty? uploads) "the upload must not survive the operation")))

(deftest a-failed-download-never-pulses-the-reset-line
  (let [{:keys [exit calls uploads]}
        (with-rig! flash-operation
                    {:stdin (elf)
                     :responses (by-command {:download {:exit 2 :err "Error: verification failed"}})})]
    (is (= 2 exit) "the rig gives back the status probe-rs returned")
    (is (= ["download"] (mapv second calls)))
    (is (empty? uploads))))

(deftest flash-refuses-a-malformed-elf-before-probe-rs-opens-the-target
  (doseq [[reason image]
          [["ELF32 header" (byte-array 16)]
           ["magic number" (elf :magic [0x7f 0x45 0x4c 0x00])]
           ["32-bit" (elf :class 2)]
           ["little-endian" (elf :data 2)]
           ["identification version" (elf :ident-version 0)]
           ["object version" (elf :version 0)]
           ["machine" (elf :machine 0x03)]
           ["52-byte ELF32 header" (elf :ehsize 64)]
           ["program header entry" (elf :phentsize 56)]
           ["section header entry" (elf :shentsize 64 :shoff 64 :shnum 1)]
           ["program header table" (elf :phoff 120)]
           ["program header table" (elf :phoff 4294967295)]
           ["section header table" (elf :shoff 100 :shnum 2)]
           ["load segment 0" (elf :segments [{:type 1 :offset 4294967290 :filesz 8}])]
           ["load segment 0" (elf :segments [{:type 1 :offset 120 :filesz 16}])]]]
    (let [{:keys [exit err calls uploads]} (with-rig! flash-operation {:stdin image})]
      (is (= op/exit-failure exit) reason)
      (is (str/includes? err reason) (str reason " in " err))
      (is (empty? calls) "no process may start before the upload validates")
      (is (empty? uploads) "a refused upload must not survive"))))

(deftest flash-refuses-an-upload-larger-than-the-limit
  (testing "an upload of exactly the maximum reaches probe-rs"
    (let [{:keys [exit calls]} (with-rig! flash-operation
                                           {:stdin (elf) :hardware {:max-elf-bytes 128}})]
      (is (= op/exit-ok exit))
      (is (= 2 (count calls)))))
  (testing "one byte more is refused before probe-rs runs"
    (let [{:keys [exit err calls uploads]} (with-rig! flash-operation
                                                       {:stdin (elf) :hardware {:max-elf-bytes 127}})]
      (is (= op/exit-failure exit))
      (is (str/includes? err "127-byte"))
      (is (empty? calls))
      (is (empty? uploads))))
  (is (= (* 64 1024 1024) (:max-elf-bytes hardware/defaults))))

(deftest an-upload-that-breaks-in-the-middle-leaves-nothing-behind
  (let [{:keys [exit calls uploads]} (with-rig! flash-operation {:stdin :broken})]
    (is (= op/exit-failure exit))
    (is (empty? calls) "a broken upload never reaches probe-rs")
    (is (empty? uploads) "and the volatile file goes with it")))

(deftest erase-delegates-once-to-probe-rs
  (let [{:keys [exit calls directory]}
        (with-rig! {:operation :erase :chip "RP235x" :speed-khz 1000}
                    {:responses (by-command {:erase {:exit 3 :err "Error: erase failed"}})})]
    (is (= 3 exit) "the rig gives back the status probe-rs returned")
    (is (= [[(probe-rs directory) "erase" "--probe" "0:0:/dev/spidev0.0" "--chip" "RP235x"
             "--protocol" "swd" "--speed" "1000"]]
           calls)
        "a failed erase must not run a second destructive attempt")))

(deftest reset-pulses-the-run-line-low-and-releases-it
  (let [{:keys [exit calls directory]} (with-rig! {:operation :reset} {})]
    (is (= op/exit-ok exit))
    (is (= [[(gpioset directory) "--chip" (str (fs/path directory "gpiochip0"))
             "--toggle" "100ms,0" "26=0"]]
           calls))))

(deftest a-missing-rig-resource-names-itself-and-the-repair
  (doseq [[operation resource repair]
          [[{:operation :info :format :text} "probe-rs" "reinstall the rig image"]
           [{:operation :erase :chip "RP235x" :speed-khz 1000} "spidev0.0" "SPI0"]
           [{:operation :reset} "gpioset" "gpiod"]
           [{:operation :reset} "gpiochip0" "header pin 37"]
           [flash-operation "gpiochip0" "header pin 37"]]]
    (let [{:keys [exit err calls]} (with-rig! operation {:remove [resource]})]
      (is (= op/exit-unavailable exit) resource)
      (is (str/includes? err resource) resource)
      (is (str/includes? err repair) repair)
      (is (empty? calls) "a missing resource stops the operation before any process"))))

(defn with-rig!
  "Run one short rig operation against a fresh temporary appliance."
  [operation options]
  (let [directory (str (fs/create-temp-dir {:prefix "probetron-target"}))]
    (try (run-rig! directory operation options)
         (finally (fs/delete-tree directory)))))

(defn run-rig!
  "Run one short rig operation and return its status, output, and recorded commands."
  [directory operation {:keys [remove] :as options}]
  (let [calls (atom [])
        out (StringWriter.)
        err (StringWriter.)
        runtime (appliance! directory calls options)]
    (doseq [name remove] (fs/delete-if-exists (fs/path directory name)))
    (let [exit (binding [*out* out *err* err] (runner/execute! operation runtime))]
      {:exit exit
       :out (str out)
       :err (str err)
       :calls @calls
       :directory directory
       :uploads (mapv str (fs/list-dir (fs/path directory "uploads")))})))

(defn appliance!
  "Create a temporary appliance and return the runtime that owns it."
  [directory calls {:keys [stdin responses hardware]}]
  (let [path (fn [name] (str (fs/path directory name)))
        respond (or responses (constantly {:exit 0}))]
    (doseq [name ["probe-rs" "gpioset" "spidev0.0" "gpiochip0"]]
      (fs/create-file (fs/path directory name)))
    (fs/create-dirs (fs/path directory "uploads"))
    (spit (path "os-release") "NAME=\"Debian GNU/Linux\"\nPRETTY_NAME=\"Debian GNU/Linux 13 (trixie)\"\n")
    (spit (path "hostname") "probetron-01\n")
    (spit (path "machine-id") "0123456789abcdef0123456789abcdef\n")
    (runner/runtime
     {:paths {:lock (path "target.lock")
              :active (path "active.edn")
              :uploads (path "uploads")
              :os-release (path "os-release")
              :hostname (path "hostname")
              :machine-id (path "machine-id")}
      :executables {:probe-rs (path "probe-rs") :gpioset (path "gpioset")}
      :hardware (merge {:spi-device (path "spidev0.0") :gpio-chip (path "gpiochip0")} hardware)
      :stdin (fn [] (if (= :broken stdin)
                      (PipedInputStream.)
                      (ByteArrayInputStream. (or stdin (byte-array 0)))))
      :run! (fn [argv _opts] (swap! calls conj (vec argv)) (respond argv))
      :perform target/perform!
      :reset-target! target/pulse-reset!})))

(defn by-command
  "Return a fake command adapter that answers by command name."
  [responses]
  (fn [argv] (get responses (command-name argv) {:exit 0})))

(defn command-name
  "Name the command that one argv runs."
  [argv]
  (let [executable (fs/file-name (first argv))]
    (if (= "probe-rs" executable)
      (keyword (str/replace (second argv) #"^--" ""))
      (keyword executable))))

(defn probe-rs
  "Return the fake probe-rs of a temporary appliance."
  [directory]
  (str (fs/path directory "probe-rs")))

(defn gpioset
  "Return the fake gpioset of a temporary appliance."
  [directory]
  (str (fs/path directory "gpioset")))

(defn elf
  "Return an ELF32 little-endian RP2350 image with any header field replaced."
  [& {:as overrides}]
  (let [{:keys [magic class data ident-version version machine ehsize phentsize shentsize
                phoff phnum shoff shnum segments size]}
        (merge {:magic [0x7f 0x45 0x4c 0x46]
                :class 1 :data 1 :ident-version 1 :version 1 :machine 0x28
                :ehsize 52 :phentsize 32 :shentsize 40
                :phoff 52 :shoff 0 :shnum 0
                :segments [{:type 1 :offset 116 :filesz 8}]
                :size 128}
               overrides)
        entries (or phnum (count segments))
        bytes (byte-array size)]
    (doseq [[index value] (map-indexed vector magic)] (put8! bytes index value))
    (put8! bytes 4 class)
    (put8! bytes 5 data)
    (put8! bytes 6 ident-version)
    (put16! bytes 16 2)
    (put16! bytes 18 machine)
    (put32! bytes 20 version)
    (put32! bytes 28 phoff)
    (put32! bytes 32 shoff)
    (put16! bytes 40 ehsize)
    (put16! bytes 42 phentsize)
    (put16! bytes 44 entries)
    (put16! bytes 46 shentsize)
    (put16! bytes 48 shnum)
    (doseq [[index segment] (map-indexed vector segments)]
      (let [entry (+ phoff (* index 32))]
        (when (<= (+ entry 32) size)
          (put32! bytes entry (:type segment))
          (put32! bytes (+ entry 4) (:offset segment))
          (put32! bytes (+ entry 16) (:filesz segment)))))
    bytes))

(defn put8!
  "Write one byte."
  [bytes offset value]
  (aset-byte bytes (int offset) (unchecked-byte value)))

(defn put16!
  "Write one little-endian halfword."
  [bytes offset value]
  (put8! bytes offset (bit-and value 0xff))
  (put8! bytes (inc offset) (bit-and (bit-shift-right value 8) 0xff)))

(defn put32!
  "Write one little-endian word."
  [bytes offset value]
  (put16! bytes offset (bit-and value 0xffff))
  (put16! bytes (+ offset 2) (bit-and (bit-shift-right value 16) 0xffff)))
