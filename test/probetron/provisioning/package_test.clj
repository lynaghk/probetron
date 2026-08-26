(ns probetron.provisioning.package-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [probetron.operation :as operation]
            [probetron.provisioning.archive :as archive]
            [probetron.provisioning.package :as package]
            [probetron.version :as version]))

(defn with-build-dir
  "Package the project into a temporary build directory and pass the result to a body."
  [body]
  (let [build (fs/create-temp-dir {:prefix "probetron-package"})]
    (try
      (body (str build))
      (finally (fs/delete-tree build)))))

(defn listing
  "Return the member paths that tar reads out of one archive."
  [archive]
  (->> (:out (process/shell {:out :string} "tar" "-tzf" archive))
       str/split-lines
       (remove str/blank?)
       vec))

(defn extract-member
  "Return the text of one member that tar reads out of an archive."
  [archive member]
  (:out (process/shell {:out :string} "tar" "-xzOf" archive member)))

(deftest packages-a-deterministic-release-archive
  (with-build-dir
    (fn [build]
      (let [built "2026-01-01T00:00:00Z"
            first-run (package/package! {:root "." :build-dir build :built built})
            second-run (package/package! {:root "." :build-dir build :built built})]
        (testing "the release names itself after the VERSION file"
          (is (nil? (:error first-run)))
          (is (= version/probetron-version (:version first-run)))
          (is (= (str (fs/path build (str "probetron-" version/probetron-version ".tar.gz")))
                 (:archive first-run)))
          (is (= (str (:archive first-run) ".sha256") (:checksum first-run))))
        (testing "two runs over the same sources and build time write the same bytes"
          (is (= (:sha256 first-run) (:sha256 second-run)))
          (is (= (:sha256 first-run)
                 (archive/sha-256-hex (fs/read-all-bytes (:archive first-run))))))
        (testing "the archive bakes a build stamp that names the release and its build time"
          (let [stamp (read-string (extract-member (:archive first-run) "lib/probetron/build.edn"))]
            (is (= version/probetron-version (:version stamp)))
            (is (= built (:built stamp)))
            (is (string? (:commit stamp)))
            (is (contains? stamp :dirty?))))
        (testing "the checksum file is what sha256sum reads"
          (is (= (str (:sha256 first-run) "  probetron-" version/probetron-version ".tar.gz\n")
                 (slurp (:checksum first-run)))))
        (testing "the archive holds both entry points, the library, and the release documents"
          (let [paths (listing (:archive first-run))]
            (is (= (remove #(str/ends-with? % "/") (:members first-run))
                   (remove #(str/ends-with? % "/") paths)))
            (doseq [expected ["bin/probetron" "bin/probetron-rig" "VERSION" "README.md"
                              "lib/probetron/build.edn" "lib/probetron/operation.clj"
                              "lib/probetron/client/main.clj" "lib/probetron/rig/main.clj"]]
              (is (some #{expected} paths) (str "the archive holds " expected)))
            (is (not-any? #(str/starts-with? % "/") paths))
            (is (not-any? #(str/includes? % "..") paths))
            (is (not-any? #(str/starts-with? % "test/") paths)
                "the archive carries no test tree")
            (is (not-any? #(str/includes? % "/provisioning/") paths)
                "the archive carries no release or image build code")))))))

(deftest an-extracted-archive-runs-both-entry-points
  (with-build-dir
    (fn [build]
      (let [{:keys [archive]} (package/package! {:root "." :build-dir build})
            install (fs/path build "install")]
        (fs/create-dirs install)
        (process/shell "tar" "-xzf" archive "-C" (str install))
        (doseq [name ["probetron" "probetron-rig"]]
          (let [program (fs/path install "bin" name)]
            (is (fs/executable? program) (str name " keeps its executable mode"))
            (let [{:keys [exit out]} (process/shell {:out :string :err :string :continue true}
                                                   (str program) "--help")]
              (is (= operation/exit-ok exit))
              (is (str/includes? out "Usage:")))))))))

(deftest a-release-refuses-a-tag-that-names-another-version
  (with-build-dir
    (fn [build]
      (testing "a matching tag packages, with or without its v"
        (doseq [tag [version/probetron-version (str "v" version/probetron-version)]]
          (is (nil? (:error (package/package! {:root "." :build-dir build :tag tag}))))))
      (testing "a tag of another release names the repair"
        (let [{:keys [error]} (package/package! {:root "." :build-dir build :tag "v9.9.9"})]
          (is (str/includes? error "9.9.9"))
          (is (str/includes? error version/probetron-version)))))))

(deftest a-release-refuses-a-command-line-without-a-tag-value
  (let [complaint (java.io.StringWriter.)
        status (binding [*err* complaint] (package/main! ["--tag"]))]
    (is (= operation/exit-usage status))
    (is (str/includes? (str complaint) "--tag"))))

(deftest a-release-refuses-an-archive-outside-the-build-directory
  (with-build-dir
    (fn [build]
      (let [{:keys [error]} (package/package! {:root "." :build-dir (str (fs/path build "elsewhere"))
                                               :archive-name "../escape.tar.gz"})]
        (is (str/includes? error "outside the build directory"))
        (is (not (fs/exists? (fs/path build "escape.tar.gz"))))))))
