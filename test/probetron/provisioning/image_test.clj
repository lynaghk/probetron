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

(deftest scratch-work-happens-outside-the-checkout
  (testing "the scratch directory belongs to the system temporary directory"
    (is (= "/var/tmp/probetron-work" (str (image/work-directory "/var/tmp")))))
  (testing "a filesystem with room for one image passes the gate"
    (is (= :enough (image/space-state (* 2 image/required-space)))))
  (testing "a filesystem without room for one image fails it"
    (is (= :short (image/space-state (dec image/required-space))))))
