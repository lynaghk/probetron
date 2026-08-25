(ns probetron.test-runner
  "Dependency-free entry point that discovers and runs every Probetron test namespace."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as test]))

(declare test-namespaces path->namespace)

(defn run-tests!
  "Run every test namespace under test/ and exit non-zero when anything fails."
  []
  (let [namespaces (test-namespaces "test")]
    (doseq [namespace namespaces]
      (require namespace))
    (let [{:keys [fail error]} (apply test/run-tests namespaces)]
      (System/exit (if (pos? (+ fail error)) 1 0)))))

(defn test-namespaces
  "Return the sorted test namespace symbols found under a source root."
  [root]
  (->> (fs/glob root "**/*_test.clj")
       (map #(path->namespace root %))
       sort))

(defn path->namespace
  "Convert a test file path into its namespace symbol."
  [root path]
  (-> (str (fs/relativize (fs/path root) path))
      (str/replace #"\.clj$" "")
      (str/replace "/" ".")
      (str/replace "_" "-")
      symbol))
