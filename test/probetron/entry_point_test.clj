(ns probetron.entry-point-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [probetron.operation :as operation]))

(defn run
  "Run an entry point and return {:exit status :out text :err text}."
  [command argv env]
  (apply process/shell {:out :string :err :string :continue true :extra-env env} command argv))

(deftest public-entry-point
  (testing "an empty command line explains itself and fails with the usage status"
    (let [{:keys [exit err]} (run "bin/probetron" [] {})]
      (is (= operation/exit-usage exit))
      (is (str/includes? err "probetron flash"))))
  (testing "help succeeds"
    (let [{:keys [exit out]} (run "bin/probetron" ["--help"] {})]
      (is (= operation/exit-ok exit))
      (is (str/includes? out "PROBETRON_HOST"))))
  (testing "the version names Probetron"
    (let [{:keys [exit out]} (run "bin/probetron" ["--version"] {})]
      (is (= operation/exit-ok exit))
      (is (str/includes? out "probetron"))))
  (testing "an unknown option fails with the usage status"
    (let [{:keys [exit err]} (run "bin/probetron" ["info" "--host" "pi" "--nozzle" "2"] {})]
      (is (= operation/exit-usage exit))
      (is (str/includes? err "--nozzle"))))
  (testing "a valid operation leaves validation and reaches the client shell"
    (let [{:keys [exit err]} (run "bin/probetron" ["reset"] {"PROBETRON_HOST" "pi.lab"
                                                             "HOME" ""
                                                             "XDG_CACHE_HOME" ""})]
      (is (not= operation/exit-usage exit))
      (is (= operation/exit-failure exit))
      (is (str/includes? err "XDG_CACHE_HOME")
          "a client without a cache home stops before it asks any host for a key"))))

(deftest rig-entry-point
  (let [{:keys [exit out]} (run "bin/probetron-rig" ["--help"] {})]
    (is (= operation/exit-ok exit))
    (is (str/includes? out "probetron-rig connect")))
  (testing "a client-only option stops at the rig boundary"
    (let [{:keys [exit err]} (run "bin/probetron-rig" ["reset" "--host" "pi"] {})]
      (is (= operation/exit-usage exit))
      (is (str/includes? err "client-only"))))
  (testing "the rig ignores client environment defaults"
    (let [{:keys [exit err]} (run "bin/probetron-rig" ["flash"] {"PROBETRON_CHIP" "RP2350"})]
      (is (= operation/exit-usage exit))
      (is (str/includes? err "--chip")))))

(deftest version-names-probetrons-own-checkout-not-the-callers
  (testing "run from another git repo, the version still names probetron's commit and path"
    (let [launcher (str (fs/absolutize (fs/path "bin" "probetron")))
          head (str/trim (:out (process/shell {:out :string} "git" "rev-parse" "--short" "HEAD")))
          foreign (fs/create-temp-dir {:prefix "foreign-repo"})]
      (try
        (process/shell {:dir (str foreign)} "git" "init" "-q")
        (process/shell {:dir (str foreign)}
                       "git" "-c" "user.email=t@t" "-c" "user.name=t"
                       "commit" "--allow-empty" "-q" "-m" "foreign")
        (let [{:keys [exit out]} (process/shell {:out :string :err :string :continue true
                                                 :dir (str foreign)}
                                                launcher "--version")]
          (is (= operation/exit-ok exit))
          (is (str/includes? out head)
              "the stamp names probetron's HEAD, not the caller's repository")
          (is (str/includes? out "probetron")
              "the stamp names the probetron checkout it runs from"))
        (finally (fs/delete-tree foreign))))))

(deftest entry-points-run-from-an-installed-tree
  (let [root (fs/create-temp-dir {:prefix "probetron-install"})]
    (try
      (fs/create-dirs (fs/path root "bin"))
      (fs/create-dirs (fs/path root "lib"))
      (doseq [name ["probetron" "probetron-rig"]]
        (fs/copy (fs/path "bin" name) (fs/path root "bin" name) {:replace-existing true})
        (fs/set-posix-file-permissions (fs/path root "bin" name) "rwxr-xr-x"))
      (fs/copy-tree (fs/path "src" "probetron") (fs/path root "lib" "probetron"))
      (doseq [name ["probetron" "probetron-rig"]]
        (let [{:keys [exit out]} (run (str (fs/path root "bin" name)) ["--help"] {})]
          (is (= operation/exit-ok exit))
          (is (str/includes? out "Usage:"))))
      (finally (fs/delete-tree root)))))
