(ns probetron.rig.elf
  "Pure validator of one uploaded firmware image.

   It reads only the ELF32 little-endian structures that decide whether
   probe-rs can flash the image on an RP2350, and it names the first structure
   it refuses.
   Every offset is a long, so no declared size can overflow into a range that
   looks like it fits.")

(declare header-error table-error load-segment-error machines u8 u16 u32)

(def header-size
  "The ELF32 header size that the specification defines."
  52)

(def program-entry-size
  "The ELF32 program header entry size that the specification defines."
  32)

(def section-entry-size
  "The ELF32 section header entry size that the specification defines."
  40)

(def machine-arm 0x28)
(def machine-riscv 0xf3)
(def program-type-load 1)

(defn error
  "Return why an upload is not a flashable RP2350 ELF, or nil when it is one."
  [bytes]
  (let [size (alength ^bytes bytes)]
    (if (< size header-size)
      (str "expected at least the " header-size "-byte ELF32 header, but the upload carries "
           size " bytes")
      (or (header-error bytes)
          (table-error "program header" (u32 bytes 28) (u16 bytes 44) (u16 bytes 42)
                       program-entry-size size)
          (table-error "section header" (u32 bytes 32) (u16 bytes 48) (u16 bytes 46)
                       section-entry-size size)
          (load-segment-error bytes size)))))

(defn header-error
  "Return why the ELF32 header does not describe an RP2350 image, or nil."
  [bytes]
  (let [machine (u16 bytes 18)
        declared (u16 bytes 40)]
    (cond
      (not= [0x7f 0x45 0x4c 0x46] (mapv #(u8 bytes %) (range 4)))
      "expected the ELF magic number"

      (not= 1 (u8 bytes 4)) "expected a 32-bit ELF file"
      (not= 1 (u8 bytes 5)) "expected a little-endian ELF file"
      (not= 1 (u8 bytes 6)) "expected ELF identification version 1"
      (not= 1 (u32 bytes 20)) "expected ELF object version 1"

      (not (machines machine))
      (str "expected an ARM or RISC-V machine, but the header names machine " machine)

      (not= header-size declared)
      (str "expected a " header-size "-byte ELF32 header, but the header declares " declared))))

(defn table-error
  "Return why one header table does not fit the upload, or nil when it does."
  [label offset entries entry-size expected-size size]
  (cond
    (zero? entries) nil

    (not= expected-size entry-size)
    (str "expected a " expected-size "-byte " label " entry, but the header declares " entry-size)

    (> (+ offset (* (long entries) entry-size)) size)
    (str "the " label " table at " offset " needs " (* (long entries) entry-size)
         " bytes, which lie outside the " size "-byte upload")))

(defn load-segment-error
  "Return why one load segment reads outside the upload, or nil when all of them fit."
  [bytes size]
  (let [offset (u32 bytes 28)]
    (first (keep (fn [index]
                   (let [entry (+ offset (* index program-entry-size))
                         start (u32 bytes (+ entry 4))
                         length (u32 bytes (+ entry 16))]
                     (when (and (= program-type-load (u32 bytes entry))
                                (> (+ start length) size))
                       (str "load segment " index " reads " length " bytes at " start
                            ", which lie outside the " size "-byte upload"))))
                 (range (u16 bytes 44))))))

(def machines
  "The machine values of the two RP2350 cores."
  #{machine-arm machine-riscv})

(defn u8
  "Read one unsigned byte."
  [bytes offset]
  (bit-and (aget ^bytes bytes (int offset)) 0xff))

(defn u16
  "Read one unsigned little-endian halfword."
  [bytes offset]
  (+ (u8 bytes offset) (* 256 (u8 bytes (inc offset)))))

(defn u32
  "Read one unsigned little-endian word."
  [bytes offset]
  (+ (u16 bytes offset) (* 65536 (long (u16 bytes (+ offset 2))))))
