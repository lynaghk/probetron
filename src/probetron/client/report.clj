(ns probetron.client.report
  "Pure model of what the client reports about itself and about the rig it reached.

   The record keeps the two sides apart, so a script reads the client facts and
   the rig facts under stable keys, and a person reads the rig report exactly
   as the rig wrote it."
  (:require [clojure.string :as str]))

(defn information
  "Assemble the information record of one client and the rig it asked."
  [{:keys [probetron babashka key rig]}]
  {:client {:probetron probetron :babashka babashka :key key}
   :rig rig})

(defn render-edn
  "Render one whole information record as EDN."
  [report]
  (pr-str report))

(defn render-client-text
  "Render the client facts for a person, one fact to a line.

   These print before the rig answers, so the rig report that follows joins
   them into the same text the combined form once wrote."
  [{:keys [probetron babashka key]}]
  (str/join "\n" [(str "client probetron: " probetron)
                  (str "client babashka: " babashka)
                  (str "client key: " key)]))
