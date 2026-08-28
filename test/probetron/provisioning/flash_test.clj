(ns probetron.provisioning.flash-test
  (:require [clojure.test :refer [deftest is testing]]
            [probetron.provisioning.flash :as flash]))

(deftest an-argument-names-either-an-image-or-a-disk
  (testing "no argument names neither"
    (is (= [nil nil] (flash/split-argv []))))
  (testing "a plain path names the image and leaves the disk to the menu"
    (is (= ["build/probetron.img.xz" nil]
           (flash/split-argv ["build/probetron.img.xz"]))))
  (testing "a /dev path names the disk and leaves the image to the build"
    (is (= [nil "/dev/disk4"] (flash/split-argv ["/dev/disk4"]))))
  (testing "both may be given in either order"
    (is (= ["build/probetron.img.xz" "/dev/disk4"]
           (flash/split-argv ["build/probetron.img.xz" "/dev/disk4"])))
    (is (= ["build/probetron.img.xz" "/dev/disk4"]
           (flash/split-argv ["/dev/disk4" "build/probetron.img.xz"])))))

(deftest a-disk-path-picks-the-listed-disk-it-names
  (let [disks [{:node "/dev/disk4" :name "SD"} {:node "/dev/disk5" :name "USB"}]]
    (testing "a path that a disk carries picks that disk"
      (is (= {:node "/dev/disk5" :name "USB"} (flash/find-disk "/dev/disk5" disks))))
    (testing "a path that no disk carries picks nothing"
      (is (nil? (flash/find-disk "/dev/disk9" disks))))))
