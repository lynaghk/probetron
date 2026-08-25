(ns probetron.client.shell
  "Imperative shell of the interactive login that the USB console carries.

   Every other client operation names one rig command and reads its answer.
   This one hands the terminal of the operator to the rig and takes it back
   when the login ends, because a rig that the lab network cannot reach is
   diagnosed by hand or not at all."
  (:require [probetron.client.command :as command]
            [probetron.client.key :as rig-key]
            [probetron.operation :as op]))

(declare fail!)

(defn open!
  "Open one interactive login on the rig and return the status of that login."
  [operation runtime]
  (let [{:keys [path error]} (rig-key/refresh! (:host operation) runtime)]
    (if error
      (fail! error)
      (let [{:keys [executables run!]} runtime
            argv (command/shell-argv {:ssh (:ssh executables)
                                      :key path
                                      :host (:host operation)})]
        (:exit (run! argv {:in :inherit :out :inherit :err :inherit}))))))

(defn fail!
  "Report why the client opened no login and return the failure status."
  [message]
  (binding [*out* *err*] (println (str "probetron: " message)))
  op/exit-failure)
