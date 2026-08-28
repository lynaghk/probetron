(ns probetron.version-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [probetron.version :as version]))

(deftest stamp-line-names-a-build-in-one-line
  (testing "a packaged build names its release, commit, and build time"
    (is (= "0.1.0 (abc1234, 2026-01-01T00:00:00Z)"
           (version/stamp-line {:version "0.1.0"
                                :commit  "abc1234"
                                :dirty?  false
                                :built   "2026-01-01T00:00:00Z"}))))
  (testing "a dirty packaged build marks its commit"
    (is (= "0.1.0 (abc1234-dirty, 2026-01-01T00:00:00Z)"
           (version/stamp-line {:version "0.1.0"
                                :commit  "abc1234"
                                :dirty?  true
                                :built   "2026-01-01T00:00:00Z"}))))
  (testing "a checkout names the path it runs from instead of a build time"
    (is (= "0.1.0 (abc1234, /home/dev/probetron)"
           (version/stamp-line {:version "0.1.0"
                                :commit  "abc1234"
                                :dirty?  false
                                :root    "/home/dev/probetron"})))))

(deftest describe-stamps-probetrons-own-checkout
  (let [stamp (version/describe)]
    (is (= version/probetron-version (:version stamp)))
    (is (string? (:commit stamp)))
    (is (contains? stamp :dirty?))
    (testing "the checkout stamp names probetron's path, not a build time"
      (is (str/ends-with? (:root stamp) "probetron"))
      (is (not (contains? stamp :built))))))
