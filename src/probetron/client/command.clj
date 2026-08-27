(ns probetron.client.command
  "Pure construction of everything the client sends to one rig.

   It turns a validated operation into the single remote command that sudo runs
   on the rig, wraps that command in an SSH argv that trusts no persistent host
   key, and names the HTTP key endpoint and the host-specific cache file.
   Nothing here opens a socket, a file, or a process."
  (:require [clojure.string :as str]
            [probetron.operation :as op]))

(declare shell-token cache-directory key-file-name)

(def client-loopback
  "The only address that a forwarded rig service and its helpers ever bind."
  "127.0.0.1")

(def rig-executable
  "The immutable rig entry point, the only program the client runs remotely."
  "/usr/local/sbin/probetron-rig")

(def rig-user
  "The ordinary rig account that the image-generated key authenticates."
  "probetron")

(def key-resource
  "The path of the private key that the rig image serves over plain HTTP."
  "/probetron_key")

(def pty-link-name
  "The name of the pseudo-terminal link inside its own private directory."
  "tty")

(def pty-retry-seconds
  "How long the pseudo-terminal helper keeps reaching for the forwarded endpoint.

   The rig opens its own listener while the client is already forwarding, so
   the first connection may arrive before the rig byte service exists."
  30)

(def cache-segments
  "The directories that carry the cached key inside the client cache home."
  ["probetron" "keys"])

(def ssh-options
  "The options that every Probetron SSH invocation carries.

   Batch mode never prompts, only the cached identity is offered, and both
   known-host files are /dev/null, so a replaced Pi at the same address needs no
   cache cleanup.
   LogLevel=ERROR drops the new-host and changed-host warnings that those
   options provoke while every real SSH error and all remote stderr still reach
   the client."
  ["-o" "BatchMode=yes"
   "-o" "IdentitiesOnly=yes"
   "-o" "StrictHostKeyChecking=no"
   "-o" "UserKnownHostsFile=/dev/null"
   "-o" "GlobalKnownHostsFile=/dev/null"
   "-o" "LogLevel=ERROR"])

(defn remote-argv
  "Return the argv that runs one operation on the rig.

   The client-only values and every client path stay behind, because each ELF
   file travels to the rig over standard input."
  [operation]
  (into ["sudo" "-n" rig-executable] (rest (op/rig-command operation))))

(defn remote-command
  "Return the one remote command that SSH hands to the login shell of the rig.

   Every token is quoted for a POSIX shell, so no public value can become a
   second token however it passed validation."
  [operation]
  (str/join " " (map shell-token (remote-argv operation))))

(def mux-options
  "How the short operations reuse one authenticated connection.

   Every short operation is its own SSH invocation, so a flurry of them would
   otherwise open, authenticate, and tear down one connection each, and a burst
   of half-open connections is what stalls the rig's login. ControlMaster shares
   one connection instead: the first operation opens it, the next reuse it, and
   ControlPersist keeps it a little longer so a sequence pays the handshake once.
   The socket lives under the local user alone, keyed by a hash of the
   destination, so two rigs and two users never share one path.
   Only the short operations carry this: a long session already owns one
   connection for its whole life, and its lock rides on that connection ending."
  ["-o" "ControlMaster=auto"
   "-o" "ControlPath=/tmp/probetron-mux-%i-%C"
   "-o" "ControlPersist=30"])

(defn ssh-argv
  "Return the argv of one SSH invocation without a TTY.

   The request is {:ssh executable :key path :host host}, and the extra options
   carry whatever one long session forwards. A short operation reuses one shared
   connection, while a long session forwards over its own."
  ([request remote-command] (ssh-argv request mux-options remote-command))
  ([{:keys [ssh key host]} extra remote-command]
   (-> [ssh "-i" key "-T"]
       (into ssh-options)
       (into extra)
       (conj (str rig-user "@" host))
       (conj remote-command))))

(defn shell-argv
  "Return the argv of one interactive login on the rig.

   It asks for a terminal and names no remote command, so the login shell of
   the rig account answers instead of one quoted operation."
  [{:keys [ssh key host]}]
  (-> [ssh "-i" key "-t"]
      (into ssh-options)
      (conj (str rig-user "@" host))))

(defn session-argv
  "Return the argv of one SSH invocation that publishes a rig service on the client.

   The forward listens on the client loopback alone, and a forward that fails
   ends the session at once, so the client never prints an endpoint that
   reaches nothing."
  [request local-port rig-port remote-command]
  (ssh-argv request
            ["-L" (str client-loopback ":" local-port ":" op/rig-loopback ":" rig-port)
             "-o" "ExitOnForwardFailure=yes"]
            remote-command))

(defn service-endpoint
  "Return the address that a host program of the client opens."
  [local-port]
  (str "tcp://" client-loopback ":" local-port))

(defn pty-bridge-command
  "Return the argv that presents one forwarded endpoint as a pseudo-terminal.

   socat creates the link and connects the forwarded endpoint at once, so the
   pseudo-terminal is already live when a host program opens it and the byte
   path is established before the first write rather than racing it, exactly as
   a direct serial device behaves. The DUT's own bytes then wait in the terminal
   buffer until the program reads them, and retry lets the client open before
   the rig listener it forwards has finished coming up."
  [socat link local-port]
  [socat
   (str "PTY,link=" link ",raw,echo=0")
   (str "TCP:" client-loopback ":" local-port ",retry=" pty-retry-seconds ",interval=1")])

(defn pty-link
  "Return the pseudo-terminal link inside one private directory."
  [directory]
  (str directory "/" pty-link-name))

(defn volatile-directory
  "Return the volatile base that carries a private link, or nil for the client default."
  [env]
  (let [base (get env "XDG_RUNTIME_DIR")]
    (when-not (str/blank? base) base)))

(defn shell-token
  "Quote one value so a POSIX shell reads it as exactly one unchanged token."
  [value]
  (if (re-matches #"[A-Za-z0-9._/:=@%+,-]+" value)
    value
    (str "'" (str/replace value "'" "'\\''") "'")))

(defn key-url
  "Return the HTTP endpoint that serves the private key of one rig."
  [host]
  (str "http://" (if (str/includes? host ":") (str "[" host "]") host) key-resource))

(defn key-path
  "Return the cache file that carries the key of one host, or nil without a cache home."
  [env host]
  (when-let [directory (cache-directory env)]
    (str directory "/" (key-file-name host))))

(defn cache-directory
  "Return the directory that carries every cached rig key, or nil without a cache home."
  [env]
  (let [blank->nil (fn [value] (when-not (str/blank? value) value))
        base (or (blank->nil (get env "XDG_CACHE_HOME"))
                 (some-> (blank->nil (get env "HOME")) (str "/.cache")))]
    (when base (str/join "/" (cons base cache-segments)))))

(defn key-file-name
  "Return the cache file name of one host.

   Only characters that every client filesystem accepts survive, so an IPv6
   literal keeps its own file next to a host name."
  [host]
  (str (str/replace host #"[^A-Za-z0-9._-]" "_") ".key"))

(defn private-key?
  "Tell whether fetched bytes carry the framing of one private key."
  [content]
  (boolean (re-find #"(?s)-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*-----END [A-Z0-9 ]*PRIVATE KEY-----"
                    (String. ^bytes content "UTF-8"))))
