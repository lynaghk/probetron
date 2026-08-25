(ns probetron.provisioning.package
  "Release packaging of Probetron.

   `plan` is the pure release model: it reconciles the version, names the two
   build outputs, and lays out every archive member.
   `package!` is the imperative shell that reads the sources, encodes the
   archive, and writes it under the build directory.
   The archive holds the whole client and rig installation and no platform
   Babashka binary, so one archive serves macOS and Linux alike."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [probetron.provisioning.archive :as archive]
            [probetron.operation :as op]
            [probetron.version :as version]))

(declare usage package! plan release-version library-sources library-members members directories
         write-file! archive-name checksum-line report unsafe-member inside? fatal failure)

(def build-directory
  "The only directory a release archive may appear in, relative to the project root."
  "build")

(def file-mode
  "The permissions of an ordinary archive member."
  0644)

(def program-mode
  "The permissions of an archive member that a client runs."
  0755)

(def directory-mode
  "The permissions of an archive directory."
  0755)

(def programs
  "The entry points that the archive installs, in archive order."
  ["bin/probetron" "bin/probetron-rig"])

(def documents
  "The version record and the user documentation that travel beside a release."
  ["VERSION" "README.md"])

(def library-root
  "Where the relocatable source tree sits inside the archive."
  "lib")

(def unshipped-directory
  "The one source directory that builds releases and images rather than running them.

   A client installs neither, so the archive carries no part of it."
  "provisioning")

(def version-pattern
  "The versions that may name an archive, so no version can reach outside the build directory."
  #"\d+\.\d+\.\d+(?:-[0-9A-Za-z.]+)?")

(defn main!
  "Build a release archive from a command line and return an exit status."
  [argv]
  (let [argv (vec argv)
        {:keys [args option-error] :as parsed} (op/parse-options argv {:tag {:coerce :string}})]
    (cond
      (some #{"--help" "-h"} argv) (do (println usage) op/exit-ok)
      option-error (fatal (:message option-error) op/exit-usage)
      (seq args) (fatal (str "unexpected argument " (first args)) op/exit-usage)
      :else
      (let [result (package! {:tag (:tag (:opts parsed))})]
        (if-let [message (:error result)]
          (fatal message op/exit-failure)
          (do (println (report result)) op/exit-ok))))))

(defn package!
  "Write the release archive and its checksum, and return where they are.

   The request takes {:root project :build-dir directory :tag tag
   :archive-name name}, and each part defaults to the project directory, its
   build directory, no tag, and the archive name of the release version.
   Return {:version v :archive path :checksum path :sha256 hex :members [...]}
   or {:error message}.
   Two runs over the same sources write the same bytes, because every member
   carries a fixed mode, owner, and time and arrives in one sorted order."
  [{:keys [root build-dir tag] :as request}]
  (let [root (or root ".")
        build-dir (or build-dir (str (fs/normalize (fs/path root build-directory))))
        version-file (str (fs/path root "VERSION"))
        outline (if (fs/regular-file? version-file)
                  (plan {:root root :tag tag
                         :declared (slurp version-file)
                         :library (library-sources root)})
                  (failure (str "cannot read " version-file
                                ": run the release from the Probetron project directory")))]
    (if (:error outline)
      outline
      (let [missing (->> (:members outline)
                         (filter #(= :file (:kind %)))
                         (remove #(fs/regular-file? (:source %))))
            archive-file (fs/path build-dir (or (:archive-name request)
                                                (archive-name (:version outline))))
            checksum-file (fs/path (str archive-file ".sha256"))]
        (cond
          (seq missing)
          (failure (str "cannot read " (:source (first missing))
                        ": run the release from the Probetron project directory"))

          (not (inside? build-dir archive-file))
          (failure (str "the archive " archive-file " lies outside the build directory " build-dir))

          :else
          (let [members (for [member (:members outline)]
                          (cond-> member
                            (= :file (:kind member)) (assoc :content (fs/read-all-bytes (:source member)))))
                content (archive/gzip (archive/tar members))
                digest (archive/sha-256-hex content)]
            (fs/create-dirs build-dir)
            (write-file! archive-file content)
            (write-file! checksum-file (.getBytes (checksum-line digest (fs/file-name archive-file)) "UTF-8"))
            {:version (:version outline)
             :archive (str archive-file)
             :checksum (str checksum-file)
             :sha256 digest
             :members (mapv :path (:members outline))}))))))

(defn plan
  "Return the pure release plan of one source tree.

   The plan reads nothing: it takes the text of the VERSION file, the project
   root that every source path starts with, an optional release tag, and the
   source paths of the library.
   Return {:version v :members members} or {:error message}, where members are
   the sorted directory and file entries of the archive."
  [{:keys [root tag declared library]}]
  (let [chosen (release-version (str/trim declared) version/probetron-version tag)]
    (if (:error chosen)
      chosen
      (let [entries (members root (library-members root library))
            unsafe (some unsafe-member entries)]
        (if unsafe
          (failure unsafe)
          {:version (:version chosen) :members entries})))))

(defn release-version
  "Reconcile the VERSION file, the version namespace, and an optional release tag.

   Return {:version v} or {:error message}.
   A tag names the release it belongs to alone, so `v0.1.0` and `0.1.0` both
   pass for version 0.1.0 and nothing else does."
  [declared coded tag]
  (let [wanted (some-> tag str/trim (str/replace #"^v" ""))]
    (cond
      (not (re-matches version-pattern declared))
      (failure (str "the VERSION file holds " (pr-str declared)
                    ", which is not a release version such as 0.1.0"))

      (not= declared coded)
      (failure (str "VERSION holds " declared " while probetron.version holds " coded
                    ": make probetron/VERSION and src/probetron/version.clj agree"))

      (and wanted (not= wanted declared))
      (failure (str "the release tag " tag " does not name version " declared
                    ": tag the release v" declared " or raise VERSION first"))

      :else {:version declared})))

(defn library-sources
  "Return every source file that a release ships, in sorted order."
  [root]
  (let [source-root (fs/path root "src")
        unshipped (str (fs/path "probetron" unshipped-directory) "/")]
    (->> (fs/glob source-root "**/*.clj")
         (remove #(str/starts-with? (str (fs/relativize source-root %)) unshipped))
         (map str)
         sort)))

(defn library-members
  "Turn absolute source paths of the source tree into sorted archive members."
  [root paths]
  (let [source-root (fs/path root "src")]
    (->> paths
         (map (fn [path]
                {:path (str (fs/path library-root (str (fs/relativize source-root path))))
                 :source (str path)
                 :kind :file
                 :mode file-mode}))
         (sort-by :path)
         vec)))

(defn members
  "Return every archive member in one sorted order: directories before their contents."
  [root library]
  (let [files (concat (for [program programs]
                        {:path program :source (str (fs/path root program)) :kind :file :mode program-mode})
                      (for [document documents]
                        {:path document :source (str (fs/path root document)) :kind :file :mode file-mode})
                      library)]
    (vec (sort-by :path (concat (directories (map :path files)) files)))))

(defn directories
  "Return the directory members that hold a set of file paths."
  [paths]
  (->> paths
       (mapcat (fn [path]
                 (let [segments (butlast (str/split path #"/"))]
                   (map #(str (str/join "/" (take (inc %) segments)) "/")
                        (range (count segments))))))
       distinct
       (map (fn [path] {:path path :kind :directory :mode directory-mode}))
       (sort-by :path)
       vec))

(defn unsafe-member
  "Return the reason an archive member may not be written, or nil."
  [{:keys [path]}]
  (cond
    (str/starts-with? path "/")
    (str "the archive member " path " is an absolute path")

    (some #{".." "."} (str/split path #"/"))
    (str "the archive member " path " leaves the archive root")

    (> (count (.getBytes ^String path "UTF-8")) archive/max-path-length)
    (str "the archive member " path " is longer than "
         archive/max-path-length " bytes, which a tar name field cannot hold")))

(defn archive-name
  "Return the file name that the release archive of one version carries."
  [version]
  (str "probetron-" version ".tar.gz"))

(defn checksum-line
  "Format a digest the way sha256sum reads and writes it."
  [digest name]
  (str digest "  " name "\n"))

(defn inside?
  "Tell whether a path is a direct child of a directory."
  [directory path]
  (= (str (fs/normalize (fs/absolutize directory)))
     (str (fs/parent (fs/normalize (fs/absolutize path))))))

(defn write-file!
  "Write bytes to a path, replacing whatever was there."
  [path content]
  (with-open [stream (io/output-stream (fs/file path))]
    (.write stream ^bytes content)))

(defn report
  "Describe a finished release for a human."
  [{:keys [version archive checksum sha256]}]
  (str/join "\n" [(str "probetron: " version)
                  (str "archive: " archive)
                  (str "checksum: " checksum)
                  (str "sha256: " sha256)]))

(defn fatal
  "Report one diagnostic on standard error and return an exit status."
  [message status]
  (binding [*out* *err*] (println (str "package: " message)))
  status)

(defn failure
  "Wrap the reason a release cannot be packaged."
  [message]
  {:error message})

(def usage
  "What the release entry point does."
  (str/join "\n"
            ["Usage: bb package [--tag <tag>]"
             ""
             "Write build/probetron-<version>.tar.gz and its .sha256 companion."
             "The version comes from the VERSION file, and --tag passes only when it names that version."]))
