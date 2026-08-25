(ns probetron.provisioning.archive
  "Pure encoder that turns archive members into deterministic tar and gzip bytes.

   A member is {:path \"bin/probetron\" :kind :file :mode 0755 :bytes bytes} or
   {:path \"bin/\" :kind :directory :mode 0755}.
   Every field that an ordinary archiver takes from the machine or the clock is
   fixed here, so the same members always encode to the same bytes."
  (:import [java.io ByteArrayOutputStream]
           [java.security MessageDigest]
           [java.util.zip GZIPOutputStream]))

(declare header member-size checksum checksum-field octal write-text! zeros round-up)

(def block-size
  "The size of one tar block."
  512)

(def record-size
  "The archive size that tar rounds every archive up to."
  10240)

(def max-path-length
  "The longest member path that a USTAR name field carries on its own."
  100)

(def fixed-mtime
  "The modification time that every member carries, so no clock reaches the archive."
  0)

(def nul
  "The string terminator that a USTAR numeric field ends with."
  (str (char 0)))

(defn tar
  "Encode members as one USTAR archive and return its bytes.

   Members arrive in the order the caller wants them archived, and nothing here
   sorts, deduplicates, or validates them."
  [members]
  (let [out (ByteArrayOutputStream.)]
    (doseq [member members]
      (.write out ^bytes (header member))
      (when-let [content (:bytes member)]
        (.write out ^bytes content)
        (.write out ^bytes (zeros (- (round-up (alength ^bytes content) block-size)
                                     (alength ^bytes content))))))
    (.write out ^bytes (zeros (* 2 block-size)))
    (.write out ^bytes (zeros (- (round-up (.size out) record-size) (.size out))))
    (.toByteArray out)))

(defn gzip
  "Compress bytes with gzip and return the compressed bytes.

   The gzip header of the platform carries no name and no timestamp, so the
   same input always compresses to the same output."
  [^bytes data]
  (let [out (ByteArrayOutputStream.)]
    (with-open [stream (GZIPOutputStream. out)]
      (.write stream data))
    (.toByteArray out)))

(defn sha-256-hex
  "Return the lowercase hexadecimal SHA-256 digest of bytes."
  [^bytes data]
  (->> (.digest (MessageDigest/getInstance "SHA-256") data)
       (map #(format "%02x" (bit-and % 0xff)))
       (apply str)))

(defn header
  "Return the 512-byte USTAR header block of one member."
  [{:keys [path kind mode] :as member}]
  (let [block (byte-array block-size)]
    (write-text! block 0 path)
    (write-text! block 100 (octal mode 8))
    (write-text! block 108 (octal 0 8))
    (write-text! block 116 (octal 0 8))
    (write-text! block 124 (octal (member-size member) 12))
    (write-text! block 136 (octal fixed-mtime 12))
    (write-text! block 148 "        ")
    (write-text! block 156 (if (= :directory kind) "5" "0"))
    (write-text! block 257 "ustar")
    (write-text! block 263 "00")
    (write-text! block 148 (checksum-field (checksum block)))
    block))

(defn member-size
  "Return the payload size that the header of one member declares."
  [{:keys [kind bytes]}]
  (if (= :directory kind) 0 (alength ^bytes bytes)))

(defn checksum
  "Sum the unsigned bytes of a header block whose checksum field holds spaces."
  [^bytes block]
  (reduce (fn [total index] (+ total (bit-and (aget block index) 0xff)))
          0
          (range (alength block))))

(defn checksum-field
  "Format a header checksum as the six octal digits, NUL, and space that tar reads."
  [sum]
  (str (format "%06o" sum) nul " "))

(defn octal
  "Format a number as the zero-padded octal digits and NUL of a header field."
  [value width]
  (str (format (str "%0" (dec width) "o") value) nul))

(defn write-text!
  "Copy the ASCII bytes of text into a block at an offset and return the block."
  [^bytes block offset ^String text]
  (let [source (.getBytes text "US-ASCII")]
    (System/arraycopy source 0 block offset (alength source))
    block))

(defn zeros
  "Return a run of zero bytes of some length."
  [length]
  (byte-array length))

(defn round-up
  "Round a size up to whole units."
  [size unit]
  (* unit (quot (+ size unit -1) unit)))
