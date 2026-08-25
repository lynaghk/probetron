(ns probetron.client.tunnel-fixture
  "Temporary client that the long-session tests own.

   Every program the client runs is a stand-in script that records its argv,
   its standard input, and its own pid, and then lives until a test releases
   the session, so a test observes exactly what the client started and what
   cleanup removed.
   Its -main holds one whole session in its own process, which is how a test
   reaches the handled signal path."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [probetron.client.session :as session]
            [probetron.client.tunnel :as tunnel])
  (:import (java.io StringWriter)))

(declare client! create-client! write-program! ssh-program socat-program runtime! recording-spawn!
         program-name path key-path program call-of recorded-argv recorded-stdin remote-command
         recorded-pid await-pid! await-output! alive? signal! release! stop-all! elf)

(def host
  "The rig that every temporary client reaches."
  "pi.lab")

(def rig-key
  "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAA\n-----END OPENSSH PRIVATE KEY-----\n")

(def programs
  "The client programs that a temporary client replaces with a stand-in."
  ["ssh" "socat"])

(defn -main
  "Hold one whole long session in its own client process until a signal arrives.

   A connect session asks for a pseudo-terminal, so a signal has both a local
   helper and an outer SSH process to remove, while a debug session has the
   outer SSH process alone."
  [directory command & _flags]
  (let [client (client! {:directory directory})
        operation (case command
                    "connect" {:operation :connect
                               :host host
                               :channel :uart
                               :baud 115200
                               :local-port nil
                               :rtt nil
                               :pty? true
                               :reset-on-exit? false}
                    "debug" {:operation :debug
                             :host host
                             :local-port nil
                             :reset-on-exit? false})]
    (System/exit (tunnel/open! operation (runtime! client (atom []))))))

(defn client!
  "Create a temporary client and return the handle that a test drives it with."
  [{:keys [directory remove]}]
  (let [directory (or directory (str (fs/create-temp-dir {:prefix "probetron-tunnel"})))
        client {:directory directory}]
    (create-client! client)
    (doseq [name remove] (fs/delete-if-exists (path client name)))
    client))

(defn create-client!
  "Give the temporary client its volatile home and one stand-in for every program."
  [client]
  (fs/create-dirs (path client "run"))
  (fs/create-dirs (path client "cache"))
  (write-program! client "ssh" (ssh-program client))
  (write-program! client "socat" (socat-program client)))

(defn write-program!
  "Write one stand-in program that only its owner may run."
  [client name lines]
  (let [file (path client name)]
    (spit (fs/file file) (str "#!/bin/sh\n" (str/join "\n" lines) "\n"))
    (fs/set-posix-file-permissions file "rwx------")))

(defn ssh-program
  "Return the stand-in that replaces SSH.

   It records the argv and the whole upload, answers with whatever the test
   asked the rig to say, and then holds the session open until a test releases
   it, exactly as an outer rig command does."
  [{:keys [directory]}]
  [(str "printf '%s\\0' \"$0\" \"$@\" > " directory "/ssh.argv")
   (str "echo $$ > " directory "/ssh.pid")
   (str "cat > " directory "/ssh.stdin")
   (str "[ -f " directory "/rig-out ] && cat " directory "/rig-out")
   (str "[ -f " directory "/rig-err ] && cat " directory "/rig-err >&2")
   (str "while [ ! -f " directory "/stop ]; do sleep 0.05; done")
   (str "exit $(cat " directory "/ssh.exit 2>/dev/null || echo 0)")])

(defn socat-program
  "Return the stand-in that replaces the client pseudo-terminal helper.

   It records the argv and its own pid and then waits, so a test can watch the
   helper live, kill it alone, or see cleanup remove it."
  [{:keys [directory]}]
  [(str "printf '%s\\0' \"$0\" \"$@\" > " directory "/socat.argv")
   (str "echo $$ > " directory "/socat.pid")
   (str "while [ ! -f " directory "/stop ]; do sleep 0.05; done")])

(defn runtime!
  "Return the client runtime that drives one temporary client.

   The cache home and the volatile home stay inside the temporary directory, so
   a session leaves nothing anywhere else, and every spawn is recorded."
  [client calls]
  (session/runtime
   {:env {"XDG_CACHE_HOME" (str (path client "cache"))
          "XDG_RUNTIME_DIR" (str (path client "run"))}
    :fetch! (fn [_url] {:body (.getBytes ^String rig-key)})
    :executables {:ssh (str (path client "ssh")) :socat (str (path client "socat"))}
    :spawn! (recording-spawn! client calls)}))

(defn recording-spawn!
  "Return a spawn function that records every client helper it starts.

   The stand-ins keep their output out of the test suite, while the recorded
   options are the ones the session really asked for."
  [client calls]
  (fn [argv opts]
    (swap! calls conj {:program (program-name client argv) :argv (vec argv) :opts opts})
    (process/process argv (merge opts {:out :string :err :string}))))

(defn open!
  "Start one client session against the temporary rig and return the running client."
  [client operation]
  (let [out (StringWriter.)
        err (StringWriter.)
        calls (atom [])
        runtime (runtime! client calls)]
    {:result (binding [*out* out *err* err] (future (tunnel/open! operation runtime)))
     :out out
     :err err
     :calls calls}))

(defn answer!
  "Tell the stand-in rig what to say and which status to give the client."
  [client {:keys [out err exit]}]
  (when out (spit (str (path client "rig-out")) out))
  (when err (spit (str (path client "rig-err")) err))
  (when exit (spit (str (path client "ssh.exit")) (str exit))))

(defn program-name
  "Name the client program that one argv starts, or nil when it starts none."
  [client argv]
  (first (filter #(= (str (path client %)) (first argv)) programs)))

(defn path
  "Return one path inside the temporary client."
  [{:keys [directory]} name]
  (fs/path directory name))

(defn program
  "Return the stand-in that replaces one client program."
  [client name]
  (str (path client name)))

(defn key-path
  "Return the cache file that carries the rig key of the temporary client."
  [client]
  (str (path client (str "cache/probetron/keys/" host ".key"))))

(defn call-of
  "Return the recorded spawn of one client program."
  [calls program]
  (first (filter #(= program (:program %)) calls)))

(defn recorded-argv
  "Return the argv that one stand-in received, or nil when it never ran."
  [client name]
  (let [file (path client (str name ".argv"))]
    (when (fs/exists? file)
      (vec (butlast (str/split (String. (fs/read-all-bytes file)) #"\x00" -1))))))

(defn recorded-stdin
  "Return the bytes that the SSH stand-in read from standard input."
  [client]
  (fs/read-all-bytes (path client "ssh.stdin")))

(defn remote-command
  "Return the one remote command that the SSH stand-in received."
  [client]
  (last (recorded-argv client "ssh")))

(defn recorded-pid
  "Return the pid that one stand-in recorded, or nil when it recorded none."
  [client name]
  (let [file (path client (str name ".pid"))]
    (when (and (fs/exists? file) (pos? (fs/size file)))
      (parse-long (str/trim (slurp (fs/file file)))))))

(defn await-pid!
  "Wait until one stand-in has recorded its pid and return it."
  [client name]
  (loop [attempts 1000]
    (if-let [pid (recorded-pid client name)]
      pid
      (when (pos? attempts)
        (Thread/sleep 10)
        (recur (dec attempts))))))

(defn await-output!
  "Wait until a stream of the client carries an expected fragment and return the stream."
  [writer fragment]
  (loop [attempts 500]
    (let [text (str writer)]
      (if (or (str/includes? text fragment) (zero? attempts))
        text
        (do (Thread/sleep 10)
            (recur (dec attempts)))))))

(defn alive?
  "Tell whether a pid still runs. A zombie waits for its parent, so it counts as gone."
  [pid]
  (let [status (fs/path "/proc" (str pid) "status")]
    (boolean (and pid
                  (fs/exists? status)
                  (not (re-find #"(?m)^State:\s+Z" (String. (fs/read-all-bytes status))))))))

(defn signal!
  "Send one signal to one process."
  [signal pid]
  (process/shell {:continue true :out :string :err :string} "/bin/kill" signal (str pid)))

(defn release!
  "Let the stand-in rig end the outer command of one session."
  [client]
  (when-not (fs/exists? (path client "stop"))
    (fs/create-file (path client "stop"))))

(defn stop-all!
  "Make sure no stand-in survives a test, even one that never cleaned up."
  [client]
  (release! client)
  (doseq [name programs]
    (when-let [pid (recorded-pid client name)]
      (signal! "-KILL" pid))))

(defn elf
  "Write a small ELF stand-in inside the temporary client and return its path."
  [client]
  (let [file (path client "rtt.elf")]
    (fs/write-bytes file (byte-array (map unchecked-byte (range 0 200))))
    (str file)))
