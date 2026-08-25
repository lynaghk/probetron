(ns probetron.client.key
  "Imperative shell that keeps one cached copy of the private key of one rig.

   The rig image serves its generated key over plain HTTP on the lab LAN, so
   every operation fetches that key again and replaces a changed cache copy
   before it opens SSH.
   A rebuilt or replaced Pi at the same address therefore needs no manual
   cleanup, and no operation ever runs with a key that the rig no longer
   accepts."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [probetron.client.command :as command]))

(declare store! failure http-client)

(def safe-mode
  "The only permissions that an SSH identity may carry."
  "rw-------")

(def connect-timeout-ms 5000)
(def request-timeout-ms 10000)

(defn refresh!
  "Fetch the key of one host and return where the client cached it.

   Return {:path path} or {:error message}.
   Every refusal is an explicit failure, even when an older cache copy exists,
   because a stale key hides a rig that no longer answers."
  [host {:keys [env fetch! filesystem]}]
  (let [url (command/key-url host)
        path (command/key-path env host)
        answer (when path (fetch! url))]
    (cond
      (nil? path)
      (failure "cannot find the client key cache: set XDG_CACHE_HOME or HOME")

      (:error answer)
      (failure (str "cannot fetch the rig key from " url " (" (:error answer) ")"
                    ": check that the rig is powered, wired, and reachable"))

      (empty? (:body answer))
      (failure (str "the rig key from " url " is empty"
                    ": reflash the rig image, which generates the key at build time"))

      (not (command/private-key? (:body answer)))
      (failure (str "the answer from " url " is not a private key"
                    ": check that the address belongs to a Probetron rig"))

      :else
      (store! path (:body answer) filesystem))))

(defn store!
  "Keep exactly one cache copy of the fetched key and return where it is.

   Unchanged bytes leave the cache file alone, and changed bytes arrive through
   a temporary neighbour, so SSH never reads half a key."
  [path bytes {:keys [exists? read-bytes permissions make-directory! write-key!]}]
  (try
    (make-directory! (str (fs/parent path)))
    (let [mode (when (exists? path) (permissions path))]
      (cond
        (and mode (not= safe-mode mode))
        (failure (str "the cached rig key " path " has unsafe permissions " mode
                      ": remove it or run chmod 600 " path))

        (and mode (java.util.Arrays/equals ^bytes bytes ^bytes (read-bytes path)))
        {:path path}

        :else
        (do (write-key! path bytes) {:path path})))
    (catch Exception exception
      (failure (str "cannot store the rig key in " path ": "
                    (or (ex-message exception) (str exception)))))))

(defn http-fetch!
  "Fetch one URL over plain HTTP and return {:body bytes} or {:error message}."
  [url]
  (try
    (let [{:keys [status body]} (http/get url {:client @http-client
                                               :as :bytes
                                               :throw false
                                               :timeout request-timeout-ms})]
      (if (= 200 status)
        {:body body}
        {:error (str "HTTP status " status)}))
    (catch Exception exception
      {:error (or (ex-message exception) (.getSimpleName (class exception)))})))

(def http-client
  "The bounded HTTP client that fetches a key from the lab LAN alone."
  (delay (http/client {:connect-timeout connect-timeout-ms :follow-redirects :never})))

(defn failure
  "Wrap the reason the client refuses to run an operation."
  [message]
  {:error message})
