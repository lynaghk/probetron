(ns probetron.stand-in
  "Process, pid, and file helpers that every Probetron fixture shares.

   A stand-in is the program a fixture puts in the place of a real one: it
   records its own pid in a file and lives until a test releases it, so a test
   waits for that record, asks whether the process still runs, and kills
   whatever survived the session it belonged to."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(declare await-text! text-in)

(def poll-ms
  "How long a fixture waits between two looks at one file."
  10)

(def poll-attempts
  "How many times a fixture looks before it gives up on a file."
  1000)

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

(defn pid-in
  "Return the pid that one file carries, or nil when it carries none."
  [file]
  (some-> (text-in file) parse-long))

(defn await-pid!
  "Wait until one file carries a pid and return it."
  [file]
  (some-> (await-text! file) parse-long))

(defn await-text!
  "Wait until one file carries text and return that text, or nil when it never did."
  [file]
  (loop [attempts poll-attempts]
    (if-let [text (text-in file)]
      text
      (when (pos? attempts)
        (Thread/sleep poll-ms)
        (recur (dec attempts))))))

(defn text-in
  "Return the text that one file carries, or nil when it is missing or empty."
  [file]
  (when (and (fs/exists? file) (pos? (fs/size file)))
    (str/trim (slurp (fs/file file)))))
