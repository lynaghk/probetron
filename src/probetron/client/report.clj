(ns probetron.client.report
  "Pure model of what the client reports about itself and about the rig it reached.

   The record keeps the two sides apart, so a script reads the client facts and
   the rig facts under stable keys, and a person reads the rig report exactly
   as the rig wrote it."
  (:require [clojure.string :as str]))

(declare render-text)

(defn information
  "Assemble the information record of one client and the rig it asked."
  [{:keys [probetron babashka key rig]}]
  {:client {:probetron probetron :babashka babashka :key key}
   :rig rig})

(defn render
  "Render an information record as human text or as EDN."
  [report format]
  (case format
    :edn (pr-str report)
    :text (render-text report)))

(defn render-text
  "Render an information record for a person, client facts first."
  [{:keys [client rig]}]
  (str/join "\n" [(str "client probetron: " (:probetron client))
                  (str "client babashka: " (:babashka client))
                  (str "client key: " (:key client))
                  rig]))
