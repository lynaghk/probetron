(ns probetron.rig.main
  "Imperative shell of the rig entry point.
   It owns standard streams, the production runtime, and the exit status of one SSH command."
  (:require [probetron.rig.config :as rig]
            [probetron.rig.lifecycle :as lifecycle]
            [probetron.rig.runner :as runner]
            [probetron.rig.session :as session]
            [probetron.rig.target :as target]
            [probetron.operation :as op]))

(declare report! perform! unsupported! reset-target!)

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
  "Carry out one operation that already owns the target."
  [operation handle]
  (let [command (:operation operation)]
    (cond
      (contains? target/operations command) (target/perform! operation handle)
      (contains? session/operations command) (session/perform! operation handle)
      :else (unsupported! operation))))

(defn unsupported!
  "Report a long session that this rig cannot open yet.

   The DAP session still has no backend, so the rig names the operation it
   holds the target for and gives the target back."
  [operation]
  (runner/warn! (str "this rig has no session backend, so "
                     (name (:operation operation)) " cannot run"))
  (runner/warn! (str "the operation parsed as " (pr-str operation)))
  (when (op/stdin-elf? operation)
    (runner/warn! "the operation expects one ELF file on standard input"))
  op/exit-failure)

(def reset-target!
  "Pulse the RUN line of the target after a session that asked for it."
  target/pulse-reset!)
