(ns probetron.provisioning.image-test
  (:require [clojure.test :refer [deftest is testing]]
            [probetron.provisioning.image :as image]))

(deftest install-command-runs-from-the-working-directory
  (testing "the project directory names the installer the way the README does"
    (is (= ".cache/rpi-image-gen/install_deps.sh"
           (image/install-command "/srv/probetron/.cache/rpi-image-gen" "/srv/probetron"))))
  (testing "another working directory names the installer from there"
    (is (= "../.cache/rpi-image-gen/install_deps.sh"
           (image/install-command "/srv/probetron/.cache/rpi-image-gen" "/srv/probetron/src")))
    (is (= "probetron/.cache/rpi-image-gen/install_deps.sh"
           (image/install-command "/srv/probetron/.cache/rpi-image-gen" "/srv")))))
