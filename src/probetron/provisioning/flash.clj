(ns probetron.provisioning.flash
  "Write a built Probetron image to an SD card on macOS.

   `flash!` and `list-disks!` are the two entry points, and both start with the
   platform gate so a build host that cannot flash fails before it lists one
   disk. `flash!` shows the removable disks as a lettered menu, and the letter
   you pick is the whole of the confirmation: only removable disks carry a
   letter, and each one names its size and where it is mounted. A /dev path
   argument, of the kind `bb disks` prints, names the disk instead and skips the
   menu. Everything below the entry points is either a pure reading of the
   diskutil report or the imperative shell that owns diskutil, dd, and standard
   streams.

   The image travels to the raw device through `xz -dc | sudo dd`, so the write
   never lands a whole image in memory and never touches an internal disk."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [probetron.operation :as op]))

(declare usage enumerate! selectable internal order-disks pick letters menu!
         resolve-image! verify-image! write! prompt-disk! split-argv find-disk
         disk-records disk-record partition-mounts format-size human clock
         json! stream! sha-256-file fail! fatal
         uncompressed-size! copy-progress! progress-line
         platform! image-tool!)

(def project-root
  "The Probetron project directory, which holds bb.edn and build/."
  (->> (iterate fs/parent (fs/absolutize *file*))
       (take-while some?)
       (filter #(fs/regular-file? (fs/path % "bb.edn")))
       first
       str))

(def build-glob
  "How a finished build names the one image that `bb flash` writes by default."
  "build/*.img.xz")

(defn flash!
  "Write a built image to a removable disk, and return an exit status.

   A /dev path argument, of the kind `bb disks` prints, names the disk to write
   and skips the menu: the path is then the whole of the confirmation. With no
   such argument the operator picks the disk from the lettered menu instead."
  [argv]
  (if (some #{"--help" "-h"} argv)
    (do (println (usage)) op/exit-ok)
    (try
      (platform!)
      (let [[named node] (split-argv argv)
            image        (resolve-image! named)
            disks        (enumerate!)
            removable    (order-disks (selectable disks))]
        (verify-image! image)
        (if node
          (if-let [disk (find-disk node removable)]
            (write! disk image)
            (fail! (str node " is not a removable disk that flash may write"
                        (when (find-disk node (internal disks))
                          " (it is an internal disk, never written)")
                        ": run 'bb disks' to list the removable disks")))
          (do
            (menu! removable (internal disks))
            (if-let [disk (prompt-disk! removable)]
              (write! disk image)
              (do (println "flash: cancelled, no disk written") op/exit-ok)))))
      (catch clojure.lang.ExceptionInfo exception
        (fatal (ex-message exception) (:status (ex-data exception) op/exit-failure))))))

(defn list-disks!
  "Print the disks that this host can see, removable ones lettered, and return an exit status."
  [argv]
  (if (some #{"--help" "-h"} argv)
    (do (println (usage)) op/exit-ok)
    (try
      (platform!)
      (let [disks (enumerate!)]
        (menu! (order-disks (selectable disks)) (internal disks))
        op/exit-ok)
      (catch clojure.lang.ExceptionInfo exception
        (fatal (ex-message exception) (:status (ex-data exception) op/exit-failure))))))

(defn usage
  "Return what the flash commands may say, one line to a line."
  []
  (str/join
   \newline
   ["Usage: bb flash [image] [disk]   Write a built image to a removable disk."
    "       bb disks                  List the disks this host can see."
    ""
    "Runs on macOS alone and needs xz on the PATH."
    ""
    (str "The image defaults to the one " build-glob " that a build leaves,"
         " and a path argument names another.")
    "The disk is a /dev path that 'bb disks' prints, and naming it skips the menu."
    "With no disk, flash lists the removable disks as a lettered menu, and the"
    "letter you pick is the confirmation: only removable disks carry a letter."]))

;;; Pure reading of the diskutil report

(defn split-argv
  "Return [image disk] from a flash argv, telling a disk path from an image path.

   A disk argument is one that `bb disks` prints, a /dev path; anything else
   names an image. Either may be absent, and the two may come in either order."
  [argv]
  (let [disk? #(str/starts-with? % "/dev/")]
    [(first (remove disk? argv)) (first (filter disk? argv))]))

(defn find-disk
  "Return the disk in a list that a /dev path names, or nil when none matches."
  [node disks]
  (first (filter #(= node (:node %)) disks)))

(defn disk-records
  "Return one record for every whole disk in a diskutil report and its per-disk facts.

   The report is `diskutil list -plist physical` as parsed JSON, and facts maps
   each disk node to its `diskutil info -plist` as parsed JSON."
  [report facts]
  (let [by-node (into {} (for [entry (:AllDisksAndPartitions report)]
                           [(:DeviceIdentifier entry) entry]))]
    (for [node (:WholeDisks report)]
      (disk-record node (get by-node node) (get facts node)))))

(defn disk-record
  "Return the one record of a whole disk from its list entry and its info map."
  [node entry info]
  {:node      (str "/dev/" node)
   :raw-node  (str "/dev/r" node)
   :name      (or (not-empty (:MediaName info)) (:IORegistryEntryName info) node)
   :size      (:Size info)
   :internal? (true? (:Internal info))
   :mounts    (partition-mounts entry)})

(defn partition-mounts
  "Return every mount point of one whole disk, across plain and APFS volumes."
  [entry]
  (->> (concat (:Partitions entry) (:APFSVolumes entry))
       (keep :MountPoint)
       (remove str/blank?)
       vec))

(defn selectable
  "Return the disks that a flash may write, which are the removable ones alone."
  [disks]
  (remove :internal? disks))

(defn internal
  "Return the disks that a flash may never write, which a menu shows for context."
  [disks]
  (filter :internal? disks))

(defn order-disks
  "Return disks in the order a menu lists them: the smallest removable disk first.

   An SD card is the smallest disk on almost every bench, so ascending size puts
   the likely target at the top of the letters."
  [disks]
  (sort-by :size disks))

(defn letters
  "Return the first n menu letters, a b c and on."
  [n]
  (map #(str (char (+ (int \a) %))) (range n)))

(defn pick
  "Return the disk that one menu letter names, or nil when no letter matches."
  [letter disks]
  (get (zipmap (letters (count disks)) disks) letter))

(defn format-size
  "Return a byte count as whole decimal gigabytes, the way diskutil counts them."
  [bytes]
  (if bytes
    (format "%.0f GB" (/ (double bytes) 1e9))
    "unknown size"))

(defn describe-disk
  "Return the one line that names a disk in a menu, given its leading marker."
  [marker {:keys [node name size mounts]}]
  (format "%-4s %-11s %-22s %8s   %s"
          marker node name (format-size size)
          (if (seq mounts) (str/join " " mounts) "(not mounted)")))

(defn human
  "Return a byte count as MiB, or GiB once it passes a thousand of them."
  [bytes]
  (let [mib (/ (double bytes) 1048576)]
    (if (>= mib 1024)
      (format "%.1f GiB" (/ mib 1024))
      (format "%.0f MiB" mib))))

(defn clock
  "Return a whole-second duration as minutes and seconds, m:ss."
  [seconds]
  (format "%d:%02d" (quot seconds 60) (mod seconds 60)))

(defn progress-line
  "Return the one write-progress line for the bytes done against the total.

   A nil total, from an image whose index would not read, drops the percent and the estimate."
  [done total bytes-per-second]
  (str (format "\r  %s / %s" (human done) (if total (human total) "?"))
       (when (and total (pos? total)) (format "  %3.0f%%" (* 100.0 (/ (double done) total))))
       (format "  %s/s" (human bytes-per-second))
       (when (and total (pos? bytes-per-second))
         (format "  ETA %s" (clock (long (/ (- total done) bytes-per-second)))))))

;;; Imperative shell

(defn platform!
  "Fail unless this host is a macOS host that carries xz."
  []
  (let [os (str/trim (:out @(process/process ["uname" "-s"] {:out :string :err :string})))]
    (when-not (= "Darwin" os)
      (fail! (str "flash runs on macOS alone, and this host is " os
                  ": flash the card from the Mac that built it") op/exit-unavailable))
    (when-not (fs/which "xz")
      (fail! (str "flash needs xz to unpack the image, and this host has none.\n"
                  "Install it with one of:\n"
                  "  brew install xz\n"
                  "  sudo port install xz")
             op/exit-unavailable))))

(defn resolve-image!
  "Return the image to write: the named path, or the one image a build left.

   A build writes exactly one xz image under build/, so no argument means that
   image, and any other count of them asks the operator to name one."
  [named]
  (let [image (fs/path (if named
                         (fs/absolutize named)
                         (let [built (fs/glob project-root build-glob)]
                           (case (count built)
                             1 (first built)
                             0 (fail! (str "no built image under " build-glob
                                           ": run 'bb image' first, or pass an image path"))
                             (fail! (str (count built) " images under " build-glob
                                         ": pass the one to flash as an argument"))))))]
    (when-not (fs/regular-file? image)
      (fail! (str "cannot read image " image ": pass the path of a readable image file")))
    (str image)))

(defn verify-image!
  "Fail unless an image matches the digest that a build recorded beside it.

   A build writes <image>.sha256, and an operator who passes another image may
   have none: a present digest that matches is the only pass, and no digest at
   all is a warning rather than a stop."
  [image]
  (let [record (fs/path (str image ".sha256"))]
    (if-not (fs/regular-file? record)
      (binding [*out* *err*]
        (println (str "flash: no " (fs/file-name record) " beside the image, so its contents go unchecked")))
      (let [expected (first (str/split (str/trim (slurp (fs/file record))) #"\s+"))
            found    (sha-256-file image)]
        (when-not (= expected found)
          (fail! (str "the image " (fs/file-name (fs/path image)) " has digest " found
                      " and " (fs/file-name record) " records " expected
                      ": build the image again")))))))

(defn enumerate!
  "Return one record for every physical whole disk that diskutil reports."
  []
  (let [report (json! "diskutil list -plist physical" "cannot list the disks")
        facts  (into {} (for [node (:WholeDisks report)]
                          [node (json! (str "diskutil info -plist " node)
                                       (str "cannot read disk " node))]))]
    (disk-records report facts)))

(defn menu!
  "Print the removable disks as a lettered menu, and the internal disks below."
  [removable internal-disks]
  (if (empty? removable)
    (println "No removable disks. Insert the SD card and try again.")
    (do
      (println "Removable disks:")
      (doseq [[letter disk] (map vector (letters (count removable)) removable)]
        (println (describe-disk (str "  " letter) disk)))))
  (when (seq internal-disks)
    (println "\nInternal disks (never written):")
    (doseq [disk internal-disks]
      (println (describe-disk "" disk)))))

(defn prompt-disk!
  "Return the removable disk that the operator picks, or nil to write nothing."
  [removable]
  (when (seq removable)
    (loop []
      (print (str "\nPick a disk to flash [" (first (letters 1)) "-"
                  (last (letters (count removable))) "], or q to quit: "))
      (flush)
      (let [line (some-> (read-line) str/trim str/lower-case)]
        (cond
          (or (nil? line) (= "q" line)) nil
          (pick line removable) (pick line removable)
          :else (do (println "Not a listed choice.") (recur)))))))

(defn write!
  "Unmount the disk, write the image to its raw device, and eject it.

   xz decompresses to a pipe, and dd writes that pipe to the raw device.
   This function copies the pipe itself, so it counts the bytes reaching dd and paints one progress line against them.
   dd throttles the copy, so the line tracks the write, not the decompression, and reaches 100% with the write rather than the last of the compressed input.
   sudo caches its authentication first, so no password prompt breaks the line."
  [{:keys [node raw-node name size]} image]
  (println (str "\nErasing " node " (" name ", " (format-size size) ") with " (fs/file-name (fs/path image))))
  (stream! ["diskutil" "unmountDisk" node] (str "cannot unmount " node))
  (stream! ["sudo" "-v"] "flash needs administrator rights to write the disk")
  (println (str "Writing " (fs/file-name (fs/path image)) " -> " raw-node))
  (let [total (uncompressed-size! image)
        xz    (process/process ["xz" "--decompress" "--stdout" image] {:err :inherit})
        dd    (process/process ["sudo" "dd" (str "of=" raw-node) "bs=1m"] {:out :inherit :err :inherit})]
    (copy-progress! (.getInputStream ^Process (:proc xz))
                    (.getOutputStream ^Process (:proc dd))
                    total)
    (let [xz-exit (:exit @xz)
          dd-exit (:exit @dd)]
      (when-not (and (zero? xz-exit) (zero? dd-exit))
        (fail! (str "cannot write the image to " node
                    ": xz exit " xz-exit ", dd exit " dd-exit)))))
  (stream! ["diskutil" "eject" node] (str "cannot eject " node))
  (println "Done. The card is safe to remove.")
  op/exit-ok)

;;; Small shells

(defn json!
  "Run one diskutil command, turn its property list into JSON, and parse it.

   plutil ships with macOS, so a JSON conversion needs no dependency and the
   result parses without a property-list reader."
  [command message]
  (let [{:keys [exit out err]} @(process/process
                                 ["bash" "-c" (str command " | plutil -convert json -o - -")]
                                 {:out :string :err :string})]
    (when-not (zero? exit)
      (fail! (str message (when-let [text (not-empty (str/trim (str err out)))] (str ": " text)))))
    (json/parse-string out true)))

(defn stream!
  "Run one command with its streams attached, and fail on a nonzero status.

   dd needs sudo and dd needs the terminal, so the write inherits the streams
   rather than capturing them."
  [argv message]
  (let [{:keys [exit]} @(process/process argv {:out :inherit :err :inherit :in :inherit})]
    (when-not (zero? exit)
      (fail! (str message ": exit status " exit)))))

(defn uncompressed-size!
  "Return the byte count that an xz image expands to, from its own index, or nil.

   xz --robot --list reads the size from the stream footer without unpacking the image, in the fifth field of the tab-separated `file` line.
   A foreign image may lack a readable index, and a nil total then drops the percent rather than stopping the write."
  [image]
  (let [{:keys [exit out]} @(process/process ["xz" "--robot" "--list" image]
                                             {:out :string :err :string})]
    (when (zero? exit)
      (some->> (str/split-lines out)
               (map #(str/split % #"\t"))
               (some (fn [fields] (when (= "file" (first fields)) (nth fields 4 nil))))
               parse-long))))

(defn copy-progress!
  "Copy source to sink, repainting one progress line against the bytes copied.

   dd reads sink no faster than the card accepts, so the loop blocks on the write and counts the write, not the decompression that feeds it.
   The line repaints on a byte threshold, not every read, so a fast pipe cannot flood the terminal.
   The sink closes at the end to signal dd the end of the image."
  [source sink total]
  (let [buffer  (byte-array 1048576)
        started (System/currentTimeMillis)]
    (loop [done 0 painted -1]
      (let [n (.read source buffer)]
        (if (neg? n)
          (let [elapsed (max 0.001 (/ (- (System/currentTimeMillis) started) 1000.0))]
            (.flush sink)
            (.close sink)
            (println (progress-line done total (/ done elapsed))))
          (do
            (.write sink buffer 0 n)
            (let [done (+ done n)]
              (if (>= (- done painted) 8388608)
                (let [elapsed (max 0.001 (/ (- (System/currentTimeMillis) started) 1000.0))]
                  (print (progress-line done total (/ done elapsed)))
                  (flush)
                  (recur done done))
                (recur done painted)))))))))

(defn sha-256-file
  "Return the SHA-256 of one file as lower-case hexadecimal.

   An image is larger than memory holds in one piece, so the digest reads it in
   blocks."
  [path]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        buffer (byte-array 65536)]
    (with-open [input (io/input-stream (fs/file path))]
      (loop []
        (let [read (.read input buffer)]
          (when (pos? read)
            (.update digest buffer 0 read)
            (recur)))))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest digest)))))

(defn fail!
  "Stop the command with one diagnostic that names its repair."
  ([message] (fail! message op/exit-failure))
  ([message status] (throw (ex-info message {:status status}))))

(defn fatal
  "Report one diagnostic on standard error and return an exit status."
  [message status]
  (binding [*out* *err*]
    (doseq [line (str/split-lines message)] (println (str "flash: " line))))
  status)
