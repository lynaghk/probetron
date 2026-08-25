(ns probetron.rig.main
  "Imperative shell of the rig entry point.
   It owns standard streams, the production runtime, and the exit status of one SSH command."
  (:require [probetron.rig.config :as rig]
            [probetron.rig.lifecycle :as lifecycle]
            [probetron.rig.runner :as runner]
            [probetron.rig.session :as session]
            [probetron.rig.target :as target]
            [probetron.operation :as op]))

(declare report! perform! reset-target!)

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
    :error (do (binding [*out* *err*] (println (str lifecycle/program-name ": " message)))
               exit)
    :run (runner/execute! operation (runner/runtime {:perform perform!
                                                     :reset-target! reset-target!}))))

(defn perform!
  "Carry out one operation that already owns the target.

   A long session holds the target until the outer SSH command ends, and every
   other operation opens the hardware once."
  [operation handle]
  (if (contains? session/operations (:operation operation))
    (session/perform! operation handle)
    (target/perform! operation handle)))

(def reset-target!
  "Pulse the RUN line of the target after a session that asked for it."
  target/pulse-reset!)
