(ns probetron.client.tunnel
  "Imperative shell of the long client sessions.

   It refreshes the same cached rig key that a short operation uses, opens one
   SSH invocation that forwards the rig loopback service to a client loopback
   port, prints that endpoint, presents it as a pseudo-terminal when the client
   asked for one, and holds the session until the rig command ends or a
   handled signal arrives.
   connect forwards the rig byte service and debug forwards the rig DAP
   server, so an editor connects to the printed endpoint and disconnects from
   it as often as it likes while the outer command keeps the rig target.
   Cleanup stops the local helpers alone, because only the rig ever touches
   the DUT: --reset-on-exit travels to the rig and never becomes a client
   action."
  (:require [babashka.fs :as fs]
            [probetron.client.command :as command]
            [probetron.client.key :as rig-key]
            [probetron.client.session :as session]
            [probetron.operation :as op])
  (:import (java.io DataOutputStream OutputStream)
           (java.util.concurrent TimeUnit)))

(declare hold! start-ssh! send-framed-elf! start-pty! watch-pty! await-session! session-port
         rig-port register-cleanup! clean-up! stop-process! refuse-pty!)

(def operations
  "The client operations that hold the rig target until the client lets go."
  #{:connect :debug})

(def terminate-grace-ms
  "How long a local helper has to answer the first ask before the client insists."
  2000)

(defn open!
  "Open one long client session and return the status that the rig gave it."
  [operation runtime]
  (let [{:keys [path error]} (rig-key/refresh! (:host operation) runtime)]
    (if error
      (session/fail! error)
      (hold! operation path runtime))))

(defn hold!
  "Publish the rig service on the client and hold it until the outer command ends.

   The endpoint appears as soon as SSH starts, so a host program has somewhere
   to go while the forward and the rig target are still opening, and a
   forward that never appeared ends the whole session."
  [operation key-path runtime]
  (let [port (session-port operation runtime)
        state (atom {:cleaned? false})]
    (try
      (let [ssh (start-ssh! operation key-path port runtime)]
        (swap! state assoc :ssh ssh)
        (register-cleanup! state runtime)
        (println (command/service-endpoint port))
        (flush)
        (when (:pty? operation) (start-pty! state port runtime))
        (await-session! operation ssh))
      (finally (clean-up! state runtime)))))

(defn start-ssh!
  "Start the outer SSH command that owns the rig target.

   Both rig streams stay attached to the client, so probe-rs diagnostics and
   decoded RTT text reach the operator unchanged, while an optional RTT ELF
   travels the other way on standard input."
  [operation key-path port {:keys [executables spawn!]}]
  ;; Standard input stays open for the whole session and carries nothing but the
  ;; optional upload, so the rig sees end of input exactly when this client dies
  ;; — however it dies — and releases the target then. An upload is framed with
  ;; its length rather than closing the stream, so the tether outlives it.
  (let [ssh (spawn! (command/session-argv {:ssh (:ssh executables) :key key-path :host (:host operation)}
                                          port
                                          (rig-port operation)
                                          (command/remote-command operation))
                    {:in :stream :out :inherit :err :inherit})]
    (when-let [elf (op/stdin-elf operation)]
      (send-framed-elf! (:in ssh) elf))
    ssh))

(defn send-framed-elf!
  "Write one ELF to the open SSH standard input, framed by a four-byte length.

   The stream stays open afterward, so the same standard input the rig read the
   ELF from goes on serving as the client tether."
  [out elf-path]
  (let [bytes (fs/read-all-bytes elf-path)
        framed (DataOutputStream. ^OutputStream out)]
    (.writeInt framed (alength ^bytes bytes))
    (.write framed ^bytes bytes)
    (.flush framed)))

(defn start-pty!
  "Present the forwarded endpoint as a pseudo-terminal, or say why the client cannot.

   The pseudo-terminal is a presentation of the printed endpoint alone, so a
   client without socat keeps the whole session and every byte of it."
  [state port {:keys [env executables which make-link-directory! spawn!]}]
  (if-let [socat (which (:socat executables))]
    (let [directory (make-link-directory! (command/volatile-directory env))
          link (command/pty-link directory)
          helper (spawn! (command/pty-bridge-command socat link port)
                         {:out :inherit :err :inherit})]
      (swap! state assoc :pty helper :pty-directory directory)
      (println link)
      (flush)
      (watch-pty! state port link helper))
    (refuse-pty! (:socat executables))))

(defn watch-pty!
  "Say when the pseudo-terminal helper stopped before the session did.

   Only the presentation is gone: the session keeps the target, and a host
   program still reaches the DUT over the TCP endpoint."
  [state port link helper]
  (future
    (.waitFor ^Process (:proc helper))
    (when-not (:cleaned? @state)
      (session/warn! (str "the pseudo-terminal " link " closed"
                          ": " (command/service-endpoint port) " stays usable")))))

(defn await-session!
  "Wait for the outer rig command and return the status it gave."
  [operation ssh]
  (session/report-transport-failure! (:host operation) (.waitFor ^Process (:proc ssh))))

(defn session-port
  "Return the client loopback port that publishes the rig service.

   An explicit --local-port wins, and the happy path reserves a free ephemeral
   port, so an ordinary session needs no port bookkeeping at all."
  [operation {:keys [reserve-port!]}]
  (or (:local-port operation) (reserve-port!)))

(defn rig-port
  "Return the fixed rig loopback port that one long operation publishes."
  [{:keys [operation]}]
  (case operation
    :connect op/rig-byte-port
    :debug op/rig-dap-port))

(defn register-cleanup!
  "Register the cleanup that a handled client signal runs.

   A shutdown hook answers SIGINT, SIGTERM, and SIGHUP, and it is registered as
   soon as the session owns the outer SSH process, so no helper of a session
   that reached the rig outlives the client."
  [state runtime]
  (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable #(clean-up! state runtime))))

(defn clean-up!
  "Stop the local helpers of one session and remove what the client created.

   It runs once, whether the rig command ended, a host program left, or a
   handled signal arrived.
   Stopping the outer SSH process hangs the remote command up, which is what
   removes the rig listeners and gives the target lock back, and the client
   itself never touches the DUT."
  [state {:keys [delete-link-directory!]}]
  (let [[before] (swap-vals! state assoc :cleaned? true)]
    (when-not (:cleaned? before)
      (stop-process! (:pty before))
      (when-let [directory (:pty-directory before)] (delete-link-directory! directory))
      (stop-process! (:ssh before)))))

(defn stop-process!
  "End one local helper and wait for it, forcibly when it ignores the first ask."
  [helper]
  (when-let [process ^Process (:proc helper)]
    (.destroy process)
    (when-not (.waitFor process terminate-grace-ms TimeUnit/MILLISECONDS)
      (.destroyForcibly process)
      (.waitFor process terminate-grace-ms TimeUnit/MILLISECONDS))))

(defn refuse-pty!
  "Say why this client cannot present a pseudo-terminal and what repairs it."
  [socat]
  (session/warn! (str "cannot find " socat " on this client"
                      ": install socat with brew install socat or apt install socat"
                      " to use --pty"))
  (session/warn! "the session continues, and the TCP endpoint above stays usable"))
