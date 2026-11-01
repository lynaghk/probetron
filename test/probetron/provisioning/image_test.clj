(ns probetron.provisioning.image-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [probetron.provisioning.image :as image]))

(deftest image-builds-reuse-only-unchanged-system-inputs
  (let [root      (fs/create-temp-dir {:prefix "probetron-image-cache-test"})
        work      (fs/path root "work")
        generator (fs/path root "rpi-image-gen")
        calls     (fs/path root "calls")
        manifest  {:suite {:snapshot-epoch 0} :device {:image-name "test"}}
        staged    {:babashka (str (fs/path root "bb"))
                   :probe-rs (str (fs/path root "probe-rs"))}
        resets    (atom [])
        build     (fn [& args] (with-out-str (is (zero? (image/main! args)))))]
    (try
      (doseq [directory ["image" "src/probetron/provisioning" "vendor/probe-rs"]]
        (fs/create-dirs (fs/path root directory)))
      (doseq [[path content] [["VERSION" "0.1.0"] ["bb" "babashka"] ["probe-rs" "probe-rs"]
                              ["image/config.yaml" "configuration"]
                              ["src/app.clj" "application"]
                              ["vendor/probe-rs/lib.rs" "probe source"]]]
        (spit (fs/file (fs/path root path)) content))
      (doseq [directory [root (fs/path root "vendor/probe-rs")]]
        (let [result @(process/process ["git" "init" "--quiet" (str directory)] {:out :string :err :string})]
          (is (zero? (:exit result)))))
      (spit (fs/file generator)
            (str "#!/bin/bash\nset -eu\nmode=$2\nshift 2\n"
                 "while [ \"$1\" != -B ]; do shift; done\nwork=$2\n"
                 "echo \"$mode\" >> '" calls "'\n"
                 "if [ \"$mode\" = -f ]; then\n"
                 "  mkdir -p \"$work/filesystem\" \"$work/bootstrap\"\n"
                 "  echo 'IGconf_device_user1=\"probetron\"' > \"$work/bootstrap/final.env\"\n"
                 "  echo base > \"$work/filesystem/base\"\n"
                 "else\n"
                 "  mkdir -p \"$work/image-test\"\n"
                 "  cp \"$work/filesystem/application\" \"$work/image-test/test.img\"\n"
                 "  echo image-hook > \"$work/filesystem/base\"\n"
                 "fi\n"))
      (fs/set-posix-file-permissions generator "rwxr-xr-x")
      (with-redefs [image/project-root       (str root)
                    image/checked-manifest!  (constantly manifest)
                    image/checkout!          (constantly root)
                    image/validate!          (fn [& _])
                    image/report-topology!   (fn [& _])
                    image/require-privilege! (fn [])
                    image/prepare-work!      (constantly work)
                    image/stage!             (fn [_ from-scratch] (swap! resets conj from-scratch) staged)
                    image/refresh-release!   (fn [_ directory]
                                               (is (= "base\n" (slurp (fs/file (fs/path directory "filesystem/base"))))
                                                   "each build starts before image-hook mutations")
                                               (spit (fs/file (fs/path directory "filesystem/application"))
                                                     (slurp (fs/file (fs/path root "src/app.clj")))))
                    image/patch-image!       (fn [cached raw _ _]
                                               (is (fs/regular-file? cached))
                                               (spit (fs/file raw)
                                                     (slurp (fs/file (fs/path root "src/app.clj")))))
                    image/publish!           (fn [_ raw]
                                               (is (= (slurp (fs/file (fs/path root "src/app.clj")))
                                                      (slurp (fs/file raw)))) {})
                    image/report             (constantly "built")]
        (is (str/includes? (build) "Building base filesystem"))
        (spit (fs/file (fs/path root "src/app.clj")) "updated application")
        (is (str/includes? (build) "Updating cached raw disk image"))
        (is (= "application" (slurp (fs/file (fs/path (str work "-base") "base.img"))))
            "the cached disk keeps its original application")
        (spit (fs/file (fs/path root "vendor/probe-rs/lib.rs")) "changed probe source")
        (is (str/includes? (build) "Building base filesystem"))
        (spit (fs/file (fs/path root "image/config.yaml")) "changed configuration")
        (is (str/includes? (build) "Building base filesystem"))
        (is (str/includes? (build "custom=value") "Building base filesystem"))
        (is (str/includes? (build "custom=value") "Updating cached raw disk image"))
        (is (str/includes? (build "--from-scratch" "custom=value") "Building base filesystem"))
        (is (= [nil nil nil nil nil nil true] @resets))
        (is (= ["-f" "-i" "-f" "-i" "-f" "-i" "-f" "-i" "-f" "-i"]
               (str/split-lines (slurp (fs/file calls))))))
      (finally (fs/delete-tree root)))))

(deftest install-command-runs-from-the-working-directory
  (testing "the project directory names the installer the way the README does"
    (is (= ".cache/rpi-image-gen/install_deps.sh"
           (image/install-command "/srv/probetron/.cache/rpi-image-gen" "/srv/probetron"))))
  (testing "another working directory names the installer from there"
    (is (= "../.cache/rpi-image-gen/install_deps.sh"
           (image/install-command "/srv/probetron/.cache/rpi-image-gen" "/srv/probetron/src")))
    (is (= "probetron/.cache/rpi-image-gen/install_deps.sh"
           (image/install-command "/srv/probetron/.cache/rpi-image-gen" "/srv")))))

(deftest scratch-work-happens-outside-the-checkout
  (testing "the scratch directory belongs to the system temporary directory"
    (is (= "/var/tmp/probetron-work" (str (image/work-directory "/var/tmp")))))
  (testing "a filesystem with room for one image passes the gate"
    (is (= :enough (image/space-state (* 2 image/required-space)))))
  (testing "a filesystem without room for one image fails it"
    (is (= :short (image/space-state (dec image/required-space))))))
