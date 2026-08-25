(ns probetron.rig.lifecycle
  "Pure model of rig ownership.

   It says how long each operation owns the target, what the volatile
   active-command record carries, how the status of the target reads, and which
   argv every appliance executable receives.
   Nothing here opens a file, a socket, or a process."
  (:require [clojure.string :as str]
            [probetron.operation :as op]))

(declare lock-modes render-text)

(def program-name "probetron-rig")

(def lock-modes
  "How long each rig operation owns the target lock.

   A :none operation never takes the lock, a :short operation holds it for one
   hardware operation, and a :session operation holds it until the outer SSH
   command ends."
  {:status :none
   :info :short
   :flash :short
   :erase :short
   :reset :short
   :connect :session
   :debug :session})

(defn lock-mode
  "Return how long one operation owns the target lock.

   An unknown operation takes the lock, so a new command cannot reach the
   hardware behind the back of a running one."
  [{:keys [operation]}]
  (get lock-modes operation :short))

(defn active-metadata
  "Return the volatile record that names the owner of the target lock."
  [{:keys [operation]} pid instant]
  {:command operation :pid pid :started-at (str instant)})

(defn owner
  "Return a usable owner record, or nil when the metadata is absent or malformed."
  [value]
  (when (and (map? value)
             (contains? lock-modes (:command value))
             (pos-int? (:pid value))
             (string? (:started-at value)))
    {:command (:command value) :pid (:pid value) :started-at (:started-at value)}))

(defn status-report
  "Describe the target lock and, when something holds it, its owner."
  [held? owner]
  (cond-> {:lock (if held? :held :free)}
    (and held? owner) (assoc :active owner)))

(defn render-status
  "Render a status report as human text or as EDN."
  [report format]
  (case format
    :edn (pr-str report)
    :text (render-text report)))

(defn render-text
  "Render a status report for a person."
  [{:keys [lock active]}]
  (str/join "\n"
            (cond-> [(str "lock: " (name lock))]
              (and (= :held lock) (nil? active)) (conj "active command: unknown")
              active (into [(str "active command: " (name (:command active)))
                            (str "active pid: " (:pid active))
                            (str "active since: " (:started-at active))]))))

(defn busy-message
  "Explain that another operation already owns the target."
  [owner]
  (if owner
    (str "the rig is busy with " (name (:command owner))
         " (pid " (:pid owner) ") since " (:started-at owner)
         ": wait for that operation to end")
    "the rig is busy with another operation: wait for it to end or run probetron status"))

(defn lock-holder-command
  "Return the argv of the process that owns the target lock while it lives.

   flock takes the lock without waiting, and cat holds it open until its
   standard input closes, so the lock also disappears when the rig dies."
  [{:keys [flock cat]} lock-path]
  [flock "--nonblock" "--exclusive" "--conflict-exit-code" (str op/exit-busy) lock-path cat])

(defn lock-probe-command
  "Return the argv that reports whether the target lock is free.

   The probe asks for a shared lock, so two status commands never accuse each
   other of owning the target."
  [{:keys [flock noop]} lock-path]
  [flock "--nonblock" "--shared" "--conflict-exit-code" (str op/exit-busy) lock-path noop])

(defn group-command
  "Return the argv that runs one owned helper as its own process group."
  [{:keys [setsid]} argv]
  (into [setsid] argv))

(defn group-signal-command
  "Return the argv that sends one signal to one owned process group.

   A negative operand names a process group, so the signal reaches every
   process the helper left behind."
  [{:keys [kill]} signal pgid]
  [kill (str "-" (name signal)) (str "-" pgid)])
