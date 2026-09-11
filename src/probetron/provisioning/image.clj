(ns probetron.provisioning.image
  "Build the pinned immutable Probetron rig image.

   `main!` is the whole build, in the order that makes a failure cheap: the
   platform gate, the pinned rpi-image-gen checkout, and rpi-image-gen's own
   configuration and layer validation all run before one byte is downloaded,
   one key is generated, or one image is constructed.
   `--validate-only` stops right after that validation.
   Everything below `main!` is either a pure reading of image/manifest.edn or the
   imperative shell that owns files, subprocesses, and the network."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [probetron.operation :as op]
            [probetron.provisioning.archive :as archive]
            [probetron.provisioning.package :as package]
            [probetron.version :as version]))

(declare usage build! validate! checked-manifest! read-manifest! check-host! check-toolchain! checkout!
         report-topology! require-privilege! prepare-work! temporary-directory work-directory
         space-state gibibytes stage! generate-image! publish! report
         layer-files pipeline-layers canonical-env overrides ig-program dependency-state
         install-hint install-command
         capture! stream! download! extract! build-probe-rs! sha-256-file os-release environment-file
         fail! fatal move!)

(def project-root
  "The Probetron project directory, which holds bb.edn, image/, and build/.

   The nearest ancestor of this source file that holds bb.edn is that
   directory, so moving this namespace needs no edit here."
  (->> (iterate fs/parent (fs/absolutize *file*))
       (take-while some?)
       (filter #(fs/regular-file? (fs/path % "bb.edn")))
       first
       str))

(def manifest-file
  "The file that pins every downloaded input by revision, archive, and checksum, except probe-rs, which the vendor/probe-rs submodule pins."
  "image/manifest.edn")

(def probe-rs-source
  "The vendored probe-rs fork that the image compiles into its probe-rs binary."
  "vendor/probe-rs")

(def source-root
  "The rpi-image-gen source tree of Probetron: image/config and image/layer."
  "image")

(def config-file
  "The one image configuration, resolved against the source root."
  "probetron.yaml")

(def layer-names
  "Every named layer, in the order that the README maps to runtime invariants."
  ["probetron-runtime" "probetron-access" "probetron-hardware"
   "probetron-immutable" "probetron-offline" "probetron-console" "probetron-boot"])

(def build-directory
  "The only directory that a finished image and its checksum appear in."
  "build")

(def cache-directory
  "Where the pinned checkout and the fetched archives live."
  ".cache")

(def scratch-name
  "The one directory of the system temporary directory that a build works in."
  "probetron-work")

(def required-space
  "How much free space one build needs where it does that work.

   The chroot, the package cache, and the raw image are the whole of it, and
   ten gibibytes covers every image that this configuration can produce."
  (* 10 1024 1024 1024))

(def validation-message
  "What a valid configuration prints, and the whole output of --validate-only."
  "Valid Probetron image configuration.")

(defn main!
  "Build the rig image from a command line and return an exit status."
  [argv]
  (let [argv                             (vec argv)
        parsed                           (op/parse-options argv {:validate-only {:coerce :boolean}})
        {:keys [option-error opts args]} parsed]
    (cond
      (some #{"--help" "-h"} argv) (do (println (usage)) op/exit-ok)
      option-error (fatal (:message option-error) op/exit-usage)
      :else (try
              (build! {:validate-only (:validate-only opts) :overrides args})
              (catch clojure.lang.ExceptionInfo exception
                (fatal (ex-message exception) (:status (ex-data exception) op/exit-failure)))))))

(defn build!
  "Run the whole build, or stop after validation, and return an exit status."
  [{:keys [validate-only overrides]}]
  (let [manifest (checked-manifest!)
        checkout (checkout! manifest)]
    (validate! manifest checkout)
    (println validation-message)
    (if validate-only
      op/exit-ok
      (do
        (report-topology! manifest)
        (require-privilege!)
        (let [work   (prepare-work!)
              staged (stage! manifest)
              raw    (generate-image! manifest checkout staged work overrides)]
          (println (report (publish! manifest raw)))
          op/exit-ok)))))

(defn usage
  "Return what a command line may say, one line to a line."
  []
  (str/join
   \newline
   ["Usage: bb image [--validate-only] [key=value ...]"
    ""
    "Build the pinned immutable Probetron rig image on a Debian 13 arm64 host."
    ""
    "  --validate-only  Check the image configuration and its layers, then stop."
    "  key=value        One rpi-image-gen variable override, passed through."
    ""
    (str "Every pinned input lives in " manifest-file ".")
    (str "The build works in " (work-directory (temporary-directory)) ", which TMPDIR moves,"
         " and needs " (gibibytes required-space) " of free space there.")
    (str "A finished build writes the compressed image and its checksum under " build-directory "/.")]))

;;; Manifest

(defn checked-manifest!
  "Return the pinned inputs, and fail before any download unless this host can build them."
  []
  (doto (read-manifest!)
    check-host!
    check-toolchain!))

(defn read-manifest!
  "Return the pinned inputs of the image, or fail when nobody recorded them."
  []
  (let [file (fs/path project-root manifest-file)]
    (when-not (fs/regular-file? file)
      (fail! (str "cannot read " file ": run the image build from the Probetron project directory")))
    (edn/read-string (slurp (fs/file file)))))

;;; Gates that run before any download

(defn check-host!
  "Fail unless the build host is the one distribution and machine that is pinned.

   rpi-image-gen supports native Debian arm64 alone, and the image compiles
   probe-rs here into an aarch64 binary, so no other host can produce this image."
  [manifest]
  (let [{:keys [id version-id machine]} (:host manifest)
        release                         (os-release)
        found                           (str/trim (capture! {} ["uname" "-m"] "cannot read the machine type"))]
    (when-not (and (= id (get release "ID")) (= version-id (get release "VERSION_ID")))
      (fail! (str "the image builds on Debian " version-id " " machine " alone, and this host is "
                  (get release "PRETTY_NAME" "unknown") ": run the build on a Debian "
                  version-id " " machine " host")))
    (when-not (= machine found)
      (fail! (str "the image builds on " machine " alone, and this host is " found
                  ": run the build on a Debian " version-id " " machine " host")))))

(defn check-toolchain!
  "Fail unless this host carries the Rust toolchain that compiles probe-rs.

   The image builds probe-rs from vendor/probe-rs rather than fetching a binary,
   so cargo must be on PATH; the fork's rust-toolchain.toml then pins the exact
   compiler. Building on the pinned Debian host also settles GLIBC, because the
   binary links against the very base the image ships."
  [_manifest]
  (when-not (fs/which "cargo")
    (fail! (str "cannot find cargo on PATH: install Rust before building the image,"
                " and rust-toolchain.toml pins the version it uses"))))

(defn checkout!
  "Return the pinned rpi-image-gen checkout, cloning or moving it only if needed.

   The checkout is an ordinary ignored clone under the cache directory and not
   a submodule of the repository, and a checkout already at the pinned revision
   needs no network at all."
  [manifest]
  (let [{:keys [url revision tag]} (:rpi-image-gen manifest)
        directory                  (fs/path project-root (get-in manifest [:rpi-image-gen :checkout]))
        git                        (fn [message & argv] (capture! {} (into ["git" "-C" (str directory)] argv) message))]
    (when-not (fs/directory? (fs/path directory ".git"))
      (fs/create-dirs (fs/parent directory))
      (fs/delete-tree directory)
      (capture! {} ["git" "clone" "--quiet" url (str directory)]
                (str "cannot clone rpi-image-gen from " url ": check the lab network")))
    (when-not (= revision (str/trim (git "cannot read the rpi-image-gen revision" "rev-parse" "HEAD")))
      (git (str "cannot fetch rpi-image-gen " tag ": check the lab network") "fetch" "--quiet" "origin")
      (git (str "cannot check out rpi-image-gen " revision " (" tag ")") "checkout" "--quiet" "--detach" revision))
    directory))

;;; rpi-image-gen's own validation

(defn validate!
  "Run rpi-image-gen's layer lint, configuration parse, and layer resolution.

   Nothing here writes to the project, opens the network, or needs privilege,
   so it is both the --validate-only check and the first step of a real build."
  [manifest checkout]
  (let [ig          (ig-program checkout)
        work        (fs/create-temp-dir {:prefix "probetron-image-validate"})
        dynamic     (fs/path work "dynamic")
        environment {"PATH"              (str/join ":" [(str (fs/path checkout "bin"))
                                                        (str (fs/path checkout "bin" "generators"))
                                                        (System/getenv "PATH")])
                     "DYNROOT"           (str dynamic)
                     "SOURCE_DATE_EPOCH" (str (get-in manifest [:suite :snapshot-epoch]))}
        registry    (fs/path work "registry.env")
        user        (fs/path work "user.env")
        settings    (fs/path work "config.env")]
    (try
      (fs/create-dirs (fs/path dynamic "layer"))
      (doseq [file (layer-files)]
        (capture! {} [ig "metadata" "--lint" (str file)]
                  (str "layer " (fs/file-name file) " has invalid metadata")))
      (spit (fs/file registry)
            (capture! {:extra-env environment} [ig "metadata" "--emit" (str (fs/path checkout "registry.defs"))]
                      "the rpi-image-gen variable registry does not parse"))
      (capture! {:extra-env environment}
                [ig "config"
                 "--path" (str/join ":" [(str (fs/path project-root source-root "config"))
                                         (str (fs/path checkout "config"))])
                 config-file "--write-to" (str user)]
                (str "the image configuration " config-file " does not parse"))
      (spit (fs/file settings) (str (slurp (fs/file registry))
                                    (slurp (fs/file user))
                                    (canonical-env checkout dynamic)))
      (capture! {:extra-env environment}
                (into [ig "pipeline" "--env-in" (str settings)
                       "--path" (str/join ":" [(str "DYNlayer=" (fs/path dynamic "layer"))
                                               (str "SRClayer=" (fs/path project-root source-root "layer"))
                                               (str "IGlayer=" (fs/path checkout "layer"))
                                               (str "IGdevice=" (fs/path checkout "device"))
                                               (str "IGimage=" (fs/path checkout "image"))])
                       "--env-out" (str (fs/path work "env.out"))
                       "--plan-out" (str (fs/path work "layer.plan"))
                       "--layers"]
                      (pipeline-layers (slurp (fs/file user))))
                "the image layers do not resolve")
      (finally (fs/delete-tree work)))))

(defn layer-files
  "Return the path of every named layer, and fail when one of them is missing."
  []
  (for [name layer-names
        :let [file (fs/path project-root source-root "layer" (str name ".yaml"))]]
    (if (fs/regular-file? file)
      file
      (fail! (str "missing layer " file ": restore it from the Probetron repository")))))

(defn pipeline-layers
  "Return every layer that the configuration selects, as rpi-image-gen collects them.

   A layer reaches the build through the device key, the image key, or any key
   of the layer section, so an added layer needs no change here."
  [settings]
  (into ["essential"]
        (for [line  (str/split-lines settings)
              :let  [[_ key value] (re-matches #"(IGconf_device_layer|IGconf_image_layer|IGconf_layer_[A-Za-z0-9_]+)=\"(.*)\"" line)]
              :when (and key (not (str/blank? value)))]
          value)))

(defn canonical-env
  "Return the build variables that rpi-image-gen always supplies to its pipeline."
  [checkout dynamic]
  (let [architecture (fn [query] (str/trim (capture! {} ["dpkg-architecture" query]
                                                     "cannot read the Debian architecture: install dpkg-dev")))]
    (->> {"DEB_BUILD_ARCH"          (architecture "-qDEB_BUILD_ARCH")
          "DEB_BUILD_GNU_TYPE"      (architecture "-qDEB_BUILD_GNU_TYPE")
          "DEB_HOST_ARCH"           (architecture "-qDEB_BUILD_ARCH")
          "DEB_HOST_GNU_TYPE"       (architecture "-qDEB_BUILD_GNU_TYPE")
          "TOOLCHAIN_MODE"          "native"
          "IGTOP"                   (str checkout)
          "IGROOT"                  (str checkout)
          "LAYER_HOOKS"             (str (fs/path checkout "layer-hooks"))
          "RPI_TEMPLATES"           (str (fs/path checkout "templates" "rpi"))
          "DYNROOT"                 (str dynamic)
          "SRCROOT"                 (str (fs/path project-root source-root))
          "IGconf_artefact_version" version/probetron-version}
         (map (fn [[key value]] (str key "=\"" value "\"\n")))
         (str/join))))

;;; Gates that run only for a real build

(defn report-topology!
  "Say how this image will pick the DUT out of whatever is plugged into the rig.

   A rig owns one DUT and carries no console, so an unrecorded receptacle is
   the ordinary state: the rule then matches the one CDC serial device that the
   rig can see. A bench that really does present a second one records the
   receptacle and narrows the rule to that physical socket."
  [manifest]
  (when (str/blank? (get-in manifest [:dut :usb-kernels]))
    (binding [*out* *err*]
      (println (str "image: no receptacle recorded in " manifest-file
                    ", so any CDC serial device is the DUT"))
      (println (str "image: to narrow the rule to one physical socket, boot this image,"
                    " plug the DUT in, read"
                    " 'udevadm info --attribute-walk --name=/dev/ttyACM0' on the rig,"
                    " record :usb-port-label and :usb-kernels, and build again")))))

(defn require-privilege!
  "Fail unless this host can give the build the user namespace that it needs.

   rpi-image-gen creates a chroot and mounts pseudo-filesystems inside a private
   mount namespace, which root has and which podman gives an ordinary account."
  []
  (let [root? (= "0" (str/trim (capture! {} ["id" "-u"] "cannot read the current account")))]
    (when-not (or root? (fs/which "podman"))
      (fail! (str "the image build needs a private mount namespace: install podman, or run"
                  " the build as root")))))

(defn prepare-work!
  "Return the scratch directory of the build, and fail unless it has room.

   The chroot carries device nodes and files that only root may open, and the
   project directory may sit on a share that grants neither, so every build
   works in the system temporary directory rather than in the checkout."
  []
  (let [temporary (temporary-directory)
        free      (.getUsableSpace (fs/file temporary))]
    (when (= :short (space-state free))
      (fail! (str "the image build needs " (gibibytes required-space) " of free space in " temporary
                  ", which holds " (gibibytes free)
                  ": free space there, or name a roomier directory in TMPDIR")))
    (doto (work-directory temporary) fs/create-dirs)))

(defn temporary-directory
  "Return the system temporary directory that this build may work in.

   TMPDIR names it when the operator sets one. The default is /var/tmp and not
   /tmp, because a build works for hours and wants ten gibibytes, and /tmp is
   both memory on many hosts and emptied under the feet of a long build."
  []
  (or (not-empty (System/getenv "TMPDIR")) "/var/tmp"))

(defn work-directory
  "Return the scratch directory that one temporary directory holds."
  [temporary]
  (fs/path temporary scratch-name))

(defn space-state
  "Return whether one count of free bytes covers a whole image build."
  [free]
  (if (< free required-space) :short :enough))

(defn gibibytes
  "Return one count of bytes as gibibytes, the way a prerequisite reads."
  [bytes]
  (format "%.1f GiB" (/ (double bytes) 1024 1024 1024)))

;;; Staging

(defn stage!
  "Build, fetch, and verify every external input, and return where each one waits.

   Every artefact arrives on the build host and nothing is ever fetched from the
   target image, at first boot or later."
  [manifest]
  (let [stage          (fs/path project-root cache-directory "stage")
        keys-directory (fs/path stage "keys")
        rig-key        (fs/path keys-directory "probetron_key")
        release        (package/package! {:root project-root})]
    (when-let [message (:error release)]
      (fail! (str "cannot build the release archive: " message)))
    (fs/create-dirs stage)
    (fs/delete-tree keys-directory)
    (fs/create-dirs keys-directory)
    (capture! {} ["ssh-keygen" "-q" "-t" "ed25519" "-N" "" "-C"
                  (str "probetron-" version/probetron-version) "-f" (str rig-key)]
              "cannot generate the image SSH keypair: install openssh-client")
    {:release        (str (:archive release))
     :babashka       (str (extract! manifest :babashka))
     :probe-rs       (str (build-probe-rs!))
     :rig-key        (str rig-key)
     :authorized-key (str rig-key ".pub")}))

(defn extract!
  "Fetch one pinned archive, check it against its pinned digest, and unpack it.

   An archive that is already in the cache with the pinned digest needs no
   request, and a digest that does not match stops the build before use."
  [manifest key]
  (let [{:keys [url sha256 member]} (get manifest key)
        downloads                   (fs/path project-root cache-directory "downloads")
        archive                     (fs/path downloads (fs/file-name url))
        unpacked                    (fs/path project-root cache-directory "stage" (fs/file-name member))]
    (fs/create-dirs downloads)
    (when-not (and (fs/regular-file? archive) (= sha256 (sha-256-file archive)))
      (download! url archive))
    (let [found (sha-256-file archive)]
      (when-not (= sha256 found)
        (fail! (str "the archive " url " has digest " found " and " manifest-file " records " sha256
                    ": pin the digest that the release publishes, or fetch the archive again"))))
    (fs/create-dirs (fs/parent unpacked))
    ;; The unpacked member and its temporary neighbour share one filesystem, so
    ;; the rename that publishes it is one step.
    (let [work (fs/create-temp-dir {:dir (fs/path project-root cache-directory) :prefix "unpack"})]
      (try
        (capture! {} ["tar" (if (str/ends-with? (str archive) ".xz") "-xJf" "-xzf") (str archive)
                      "-C" (str work) member]
                  (str "cannot unpack " member " from " archive))
        (move! (fs/path work member) unpacked)
        (fs/set-posix-file-permissions unpacked "rwxr-xr-x")
        (finally (fs/delete-tree work))))
    unpacked))

(defn build-probe-rs!
  "Compile the probe-rs CLI from the vendored fork and return its staged path.

   The image builds probe-rs from vendor/probe-rs rather than fetching a binary,
   because the fork carries an SWD reset-and-halt fix that no stock release
   ships yet. Building on the pinned Debian host links the binary against the base
   image's own GLIBC, and the release profile strips it so it stays as small as a
   published one. A warm cargo cache makes a repeat build cheap."
  []
  (let [source (fs/path project-root probe-rs-source)
        binary (fs/path source "target" "release" "probe-rs")
        staged (fs/path project-root cache-directory "stage" "probe-rs")]
    (when-not (fs/regular-file? (fs/path source "Cargo.toml"))
      (fail! (str "the probe-rs submodule is empty: run 'git submodule update --init " probe-rs-source "'")))
    (stream! {:dir (str source)}
             ["cargo" "build" "--release" "--locked"
              "--config" "profile.release.strip=true"
              "--package" "probe-rs-tools" "--bin" "probe-rs"]
             "cannot build probe-rs from vendor/probe-rs")
    (fs/create-dirs (fs/parent staged))
    (fs/copy binary staged {:replace-existing true})
    (fs/set-posix-file-permissions staged "rwxr-xr-x")
    staged))

(defn download!
  "Fetch one URL to one path, and leave no half-written file behind."
  [url path]
  (let [partial (fs/path (str path ".part"))]
    (stream! {} ["curl" "--fail" "--silent" "--show-error" "--location"
                 "--output" (str partial) url]
             (str "cannot fetch " url ": check the lab network"))
    (move! partial path)))

;;; Image construction and publication

(defn generate-image!
  "Run the pinned image generator and return the raw image that it wrote."
  [manifest checkout staged work extra]
  (let [name  (get-in manifest [:device :image-name])
        image (fs/path work (str "image-" name) (str name ".img"))]
    (fs/create-dirs work)
    (fs/delete-if-exists image)
    (try
      (stream! {:extra-env {"SOURCE_DATE_EPOCH" (str (get-in manifest [:suite :snapshot-epoch]))}}
               (into [(str (fs/path checkout "rpi-image-gen")) "build"
                      "-S" (str (fs/path project-root source-root))
                      "-c" config-file
                      "-B" (str work)
                      "--"]
                     (into (overrides manifest staged) extra))
               "the image build failed")
      (catch clojure.lang.ExceptionInfo exception
        (fail! (cond-> (ex-message exception)
                 (= :missing (dependency-state checkout))
                 (str \newline (install-hint checkout))))))
    (when-not (fs/regular-file? image)
      (fail! (str "the image build wrote no " image ": read the build output above")))
    image))

(defn overrides
  "Return every variable that the layers read but the configuration cannot state."
  [manifest staged]
  [(str "IGconf_artefact_version=" version/probetron-version)
   (str "IGconf_ssh_pubkey_user1=" (:authorized-key staged))
   (str "IGconf_probetron_release=" (:release staged))
   (str "IGconf_probetron_babashka=" (:babashka staged))
   (str "IGconf_probetron_probe_rs=" (:probe-rs staged))
   (str "IGconf_probetron_rig_key=" (:rig-key staged))
   (str "IGconf_probetron_usb_port_label=" (get-in manifest [:dut :usb-port-label]))
   (str "IGconf_probetron_usb_kernels=" (get-in manifest [:dut :usb-kernels]))])

(defn publish!
  "Compress the raw image under the build directory and record its digest.

   Both outputs arrive through a temporary neighbour and one rename each, so an
   interrupted build replaces neither a valid image nor a valid checksum."
  [manifest image]
  (let [name       (str (get-in manifest [:device :image-name]) ".img.xz")
        directory  (fs/path project-root build-directory)
        compressed (fs/path directory name)
        checksum   (fs/path directory (str name ".sha256"))
        partial    (fs/path directory (str name ".part"))]
    (fs/create-dirs directory)
    (stream! {:out (fs/file partial)}
             ["xz" "--compress" "--threads=0" "-6" "--stdout" (str image)]
             (str "cannot compress " image))
    (let [digest (sha-256-file partial)
          record (fs/path directory (str name ".sha256.part"))]
      (spit (fs/file record) (package/checksum-line digest name))
      (move! partial compressed)
      (move! record checksum)
      {:image compressed :checksum checksum :sha256 digest})))

(defn report
  "Return what one finished build wrote, one fact to a line."
  [{:keys [image checksum sha256]}]
  (str/join \newline
            [(str "probetron: " version/probetron-version)
             (str "image: " (fs/relativize project-root image))
             (str "checksum: " (fs/relativize project-root checksum))
             (str "sha256: " sha256)]))

;;; Small shells

(defn ig-program
  "Return the rpi-image-gen engine helper of one checkout."
  [checkout]
  (str (fs/path checkout "bin" "ig")))

(defn dependency-state
  "Return whether this host carries every build dependency of one checkout.

   rpi-image-gen owns the list and the test, so asking it is the only answer
   that cannot drift from the one the build itself applies."
  [checkout]
  (let [{:keys [exit]} @(process/process
                         ["bash" "-c" ". \"$1/lib/dependencies.sh\"; dependencies_check --category all \"$1/depends\""
                          "bash" (str checkout)]
                         {:out :string :err :string})]
    (if (zero? exit) :installed :missing)))

(defn install-hint
  "Return the repair line of a build that stopped on a missing dependency.

   rpi-image-gen prints the bare name of its installer, which sits in the
   pinned checkout and not in the working directory, so that name is no
   command that anybody can run."
  [checkout]
  (str "install the rpi-image-gen build dependencies: run "
       (install-command checkout (fs/cwd)) " once, then build again"))

(defn install-command
  "Return the dependency installer of one checkout, named from one directory."
  [checkout directory]
  (str (fs/relativize (fs/absolutize directory) (fs/absolutize (fs/path checkout "install_deps.sh")))))

(defn capture!
  "Run one command, return its standard output, and fail with its diagnostics."
  [options argv message]
  (let [{:keys [exit out err]} @(process/process argv (merge {:out :string :err :string} options))]
    (when-not (zero? exit)
      (fail! (str message (when-let [text (not-empty (str/trim (str err out)))] (str ": " text)))))
    out))

(defn stream!
  "Run one command with its output attached, and fail on a nonzero status."
  [options argv message]
  (let [{:keys [exit]} @(process/process argv (merge {:out :inherit :err :inherit} options))]
    (when-not (zero? exit)
      (fail! (str message ": exit status " exit)))))

(defn sha-256-file
  "Return the SHA-256 of one file as lower-case hexadecimal.

   An image is larger than memory allows in one piece, so the digest reads it
   in blocks rather than as bytes the way a release archive does."
  [path]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        buffer (byte-array 65536)]
    (with-open [input (io/input-stream (fs/file path))]
      (loop []
        (let [read (.read input buffer)]
          (when (pos? read)
            (.update digest buffer 0 read)
            (recur)))))
    (archive/hex (.digest digest))))

(defn os-release
  "Return /etc/os-release as a map of its unquoted keys and values."
  []
  (environment-file "/etc/os-release"))

(defn environment-file
  "Return one shell-style key and value file as a map, or an empty map."
  [path]
  (if (fs/regular-file? path)
    (into {} (for [line  (str/split-lines (slurp path))
                   :let  [[_ key value] (re-matches #"([A-Za-z_][A-Za-z0-9_]*)=\"?([^\"]*)\"?" line)]
                   :when key]
               [key value]))
    {}))

(defn move!
  "Replace one path with another in one step."
  [from to]
  (fs/move from to {:replace-existing true :atomic-move true}))

(defn fail!
  "Stop the build with one diagnostic that names its repair."
  [message]
  (throw (ex-info message {:status op/exit-failure})))

(defn fatal
  "Report one diagnostic on standard error and return an exit status."
  [message status]
  (binding [*out* *err*]
    (doseq [line (str/split-lines message)] (println (str "image: " line))))
  status)
