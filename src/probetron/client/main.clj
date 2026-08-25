(ns probetron.client.main
  "Imperative shell of the public probetron command.
   It owns the environment, the filesystem, standard streams, and the exit status."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [probetron.client.cli :as cli]
            [probetron.client.session :as session]
            [probetron.client.tunnel :as tunnel]
            [probetron.operation :as op]))

(declare elf-facts file-header report! execute!)

(defn -main
  "Parse the public command line and carry out the requested operation.

   The runtime owns the environment that the parser reads, so the defaults a
   command line falls back on and the client that carries the operation out
   see the same one."
  [& argv]
  (let [runtime (session/runtime)
        result (cli/parse (vec argv) {:env (:env runtime) :elf-facts elf-facts})]
    (System/exit (report! result runtime))))

(defn report!
  "Write the result of a parse and return the exit status."
  [{:keys [action text message operation exit]} runtime]
  (case action
    (:help :version) (do (binding [*out* (if (= op/exit-ok exit) *out* *err*)]
                           (println text))
                         exit)
    :error (do (binding [*out* *err*] (println (str "probetron: " message)))
               exit)
    :run (execute! operation runtime)))

(defn execute!
  "Carry out one validated operation.

   A long session holds the rig target until the client lets go, and every
   short operation finishes inside one remote command."
  [operation runtime]
  (if (contains? tunnel/operations (:operation operation))
    (tunnel/open! operation runtime)
    (session/execute! operation runtime)))

(defn elf-facts
  "Probe a local ELF path for the pure validators."
  [path]
  (let [file (fs/file path)]
    (if-not (fs/exists? file)
      {:exists? false}
      (let [regular? (fs/regular-file? file)]
        {:exists? true
         :regular-file? regular?
         :size (fs/size file)
         :elf-header? (and regular? (op/elf-header? (file-header file 4)))}))))

(defn file-header
  "Return the leading bytes of a file."
  [file length]
  (with-open [stream (io/input-stream file)]
    (let [buffer (byte-array length)
          read (.read stream buffer)]
      (vec (take (max read 0) buffer)))))
