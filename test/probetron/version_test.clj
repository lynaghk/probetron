(ns probetron.version-test
  (:require [clojure.test :refer [deftest is testing]]
            [probetron.version :as version]))

(deftest stamp-line-names-a-build-in-one-line
  (testing "a clean build names its release, commit, and time"
    (is (= "0.1.0 (abc1234, 2026-01-01T00:00:00Z)"
           (version/stamp-line {:version "0.1.0"
                                :commit "abc1234"
                                :dirty? false
                                :built "2026-01-01T00:00:00Z"}))))
  (testing "a dirty tree marks its commit"
    (is (= "0.1.0 (abc1234-dirty, 2026-01-01T00:00:00Z)"
           (version/stamp-line {:version "0.1.0"
                                :commit "abc1234"
                                :dirty? true
                                :built "2026-01-01T00:00:00Z"})))))

(deftest describe-stamps-the-running-tree
  (let [stamp (version/describe)]
    (is (= version/probetron-version (:version stamp)))
    (is (string? (:commit stamp)))
    (is (contains? stamp :dirty?))
    (is (string? (:built stamp)))))
