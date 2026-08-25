(ns probetron.rig.main
  "Imperative shell of the rig entry point.
   It owns standard streams and the exit status of one SSH command."
  (:require [probetron.rig.config :as rig]
            [probetron.operation :as op]))

(declare report! execute!)

(defn -main
  "Parse the rig command line and carry out the requested operation."
  [& argv]
  (let [result (rig/parse (vec argv) {})]
    (System/exit (report! result))))

(defn report!
  "Write the result of a parse and return the exit status."
  [{:keys [action text message operation exit]}]
  (case action
    (:help :version) (do (binding [*out* (if (= op/exit-ok exit) *out* *err*)]
                           (println text))
                         exit)
    :error (do (binding [*out* *err*] (println (str rig/program-name ": " message)))
               exit)
    :run (execute! operation)))

(defn execute!
  "Carry out one validated rig operation."
  [operation]
  (binding [*out* *err*]
    (println (str rig/program-name ": this rig has no hardware backend, so "
                  (name (:operation operation)) " cannot run"))
    (println (str rig/program-name ": the operation parsed as " (pr-str operation)))
    (when (op/stdin-elf? operation)
      (println (str rig/program-name ": the operation expects one ELF file on standard input"))))
  op/exit-failure)
