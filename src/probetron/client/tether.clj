(ns probetron.client.tether
  "Client-held standard input ties a remote operation to the client's lifetime."
  (:require [babashka.fs :as fs])
  (:import (java.io DataOutputStream OutputStream)
           (java.util.concurrent TimeUnit)))

(declare send-framed-elf! stop-process!)

(defn hold!
  "Hold SSH input open through an optional framed upload until the rig exits.

   Closing the stream on every exit also releases the rig when an SSH master
   outlives the local client. A shutdown hook covers handled client signals."
  [argv elf-path spawn! streams]
  (let [!helper  (atom nil)
        cleanup! (fn []
                   (when-let [helper @!helper]
                     (try (.close ^OutputStream (:in helper))
                          (catch java.io.IOException _ nil))
                     (stop-process! helper)))
        hook     (Thread. ^Runnable cleanup!)]
    (.addShutdownHook (Runtime/getRuntime) hook)
    (try
      (let [helper (spawn! argv (assoc streams :in :stream))]
        (reset! !helper helper)
        (when elf-path (send-framed-elf! (:in helper) elf-path))
        (.waitFor ^Process (:proc helper)))
      (finally
        (cleanup!)
        (try (.removeShutdownHook (Runtime/getRuntime) hook)
             (catch IllegalStateException _ nil))))))

(defn send-framed-elf!
  "Write a four-byte big-endian length and ELF without closing the tether."
  [out elf-path]
  (let [bytes  (fs/read-all-bytes elf-path)
        framed (DataOutputStream. ^OutputStream out)]
    (.writeInt framed (alength ^bytes bytes))
    (.write framed ^bytes bytes)
    (.flush framed)))

(defn stop-process!
  "Terminate a local helper and reap it, forcibly after a bounded grace period."
  [helper]
  (when-let [process ^Process (:proc helper)]
    (.destroy process)
    (when-not (.waitFor process 2000 TimeUnit/MILLISECONDS)
      (.destroyForcibly process)
      (.waitFor process 2000 TimeUnit/MILLISECONDS))))
