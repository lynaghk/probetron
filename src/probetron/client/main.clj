(ns probetron.client.main
  "Imperative shell of the public probetron command.
   It owns the environment, the filesystem, standard streams, and the exit status."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [probetron.client.cli :as cli]
            [probetron.client.command :as command]
            [probetron.client.session :as session]
            [probetron.client.tunnel :as tunnel]
            [probetron.operation :as op]))

(declare environment elf-facts file-header report! execute! unsupported!)

(defn -main
  "Parse the public command line and carry out the requested operation."
  [& argv]
  (let [result (cli/parse (vec argv) {:env (environment) :elf-facts elf-facts})]
    (System/exit (report! result))))

(defn report!
  "Write the result of a parse and return the exit status."
  [{:keys [action text message operation exit]}]
  (case action
    (:help :version) (do (binding [*out* (if (= op/exit-ok exit) *out* *err*)]
                           (println text))
                         exit)
    :error (do (binding [*out* *err*] (println (str "probetron: " message)))
               exit)
    :run (execute! operation)))

(defn execute!
  "Carry out one validated operation."
  [operation]
  (let [command (:operation operation)
        runtime (session/runtime)]
    (cond
      (contains? session/operations command) (session/execute! operation runtime)
      (contains? tunnel/operations command) (tunnel/open! operation runtime)
      :else (unsupported! operation))))

(defn unsupported!
  "Report a long session that this client cannot open yet.

   The DAP session still has no client transport, so the client names the
   remote command that the operation would have run."
  [operation]
  (binding [*out* *err*]
    (println (str "probetron: this client has no session transport, so "
                  (name (:operation operation)) " cannot reach " (:host operation)))
    (println (str "probetron: the operation would run: " (command/remote-command operation))))
  op/exit-failure)

(defn environment
  "Return the process environment as a plain map."
  []
  (into {} (map (fn [entry] [(key entry) (val entry)])) (System/getenv)))

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
