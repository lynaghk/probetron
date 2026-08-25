(ns probetron.rig.information
  "Pure model of what the rig reports about itself and the target it owns.

   The record carries stable keyword keys, so a script reads the EDN form
   directly, and the text form states the same facts in one line each."
  (:require [clojure.string :as str]))

(declare render-text first-line pretty-name indent)

(defn report
  "Assemble the information record of one rig from what the shell gathered."
  [{:keys [probetron babashka probe-rs os hostname machine-id probe-selector protocol target]}]
  {:probetron probetron
   :babashka babashka
   :probe-rs (first-line probe-rs)
   :os (pretty-name os)
   :hostname (first-line hostname)
   :machine-id (first-line machine-id)
   :probe {:selector probe-selector :protocol protocol}
   :target (str/trim (or target ""))})

(defn render
  "Render an information record as human text or as EDN."
  [report format]
  (case format
    :edn (pr-str report)
    :text (render-text report)))

(defn render-text
  "Render an information record for a person, one fact to a line."
  [{:keys [probetron babashka probe-rs os hostname machine-id probe target]}]
  (str/join "\n"
            (cond-> [(str "probetron: " probetron)
                     (str "babashka: " babashka)
                     (str "probe-rs: " probe-rs)
                     (str "os: " os)
                     (str "hostname: " hostname)
                     (str "machine-id: " machine-id)
                     (str "probe: " (:selector probe) " " (:protocol probe))]
              (seq target) (conj (str "target:\n" (indent target))))))

(defn first-line
  "Return the first meaningful line of a command result or a rig file."
  [text]
  (or (first (remove str/blank? (str/split-lines (str/trim (or text "")))))
      "unknown"))

(defn pretty-name
  "Return the readable name of an os-release file."
  [text]
  (or (second (re-find #"(?m)^PRETTY_NAME=\"?([^\"\n]*)" (or text "")))
      (first-line text)))

(defn indent
  "Indent every line that a probe wrote, so it stays inside the report."
  [text]
  (str/join "\n" (map #(str "  " %) (str/split-lines text))))
