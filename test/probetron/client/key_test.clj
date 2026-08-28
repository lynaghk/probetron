(ns probetron.client.key-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as server]
            [probetron.client.key :as key]
            [probetron.client.session :as session]))

(declare with-rig-key! refresh! cached-text permissions freeze! frozen? fixture-fetch!)

(def first-key
  "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAA\n-----END OPENSSH PRIVATE KEY-----\n")

(def second-key
  "-----BEGIN OPENSSH PRIVATE KEY-----\nUk9UQVRFRC1LRVktVEVYVA==\n-----END OPENSSH PRIVATE KEY-----\n")

(deftest the-first-operation-caches-the-key-of-its-host
  (with-rig-key! {:body first-key}
    (fn [rig]
      (let [{:keys [path error]} (refresh! rig "pi.lab")]
        (is (nil? error))
        (is (= (str (:cache rig) "/probetron/keys/pi.lab.key") path))
        (is (= first-key (cached-text path)))
        (is (= "rw-------" (permissions path)) "only the client may read the private key")
        (is (= ["http://pi.lab/probetron_key"] @(:requested rig)))))))

(deftest an-unchanged-key-leaves-the-cache-file-alone
  (with-rig-key! {:body first-key}
    (fn [rig]
      (let [path (:path (refresh! rig "pi.lab"))]
        (freeze! path)
        (is (= path (:path (refresh! rig "pi.lab"))))
        (is (frozen? path) "unchanged bytes must not replace the cache file")
        (is (= first-key (cached-text path)))
        (is (= 2 (count @(:requested rig))) "every operation refreshes the key")))))

(deftest a-changed-key-replaces-the-cache-file
  (with-rig-key! {:body first-key}
    (fn [rig]
      (let [path (:path (refresh! rig "pi.lab"))]
        (freeze! path)
        (reset! (:response rig) {:body second-key})
        (is (= path (:path (refresh! rig "pi.lab"))))
        (is (= second-key (cached-text path)))
        (is (not (frozen? path)))
        (is (= "rw-------" (permissions path)))))))

(deftest every-host-keeps-its-own-cached-key
  (with-rig-key! {:body first-key}
    (fn [rig]
      (let [one   (:path (refresh! rig "pi.lab"))
            other (:path (refresh! rig "127.0.0.1"))]
        (is (not= one other))
        (is (fs/exists? one))
        (is (fs/exists? other))
        (is (= ["http://pi.lab/probetron_key" "http://127.0.0.1/probetron_key"]
               @(:requested rig)))))))

(deftest a-failed-refresh-fails-the-operation-even-with-a-cached-key
  (with-rig-key! {:body first-key}
    (fn [rig]
      (let [path (:path (refresh! rig "pi.lab"))]
        (freeze! path)
        (testing "an HTTP failure names the endpoint"
          (reset! (:response rig) {:status 503 :body "unavailable"})
          (let [{:keys [error]} (refresh! rig "pi.lab")]
            (is (str/includes? (str error) "http://pi.lab/probetron_key"))
            (is (str/includes? (str error) "503"))))
        (testing "empty content is a failure"
          (reset! (:response rig) {:body ""})
          (is (str/includes? (str (:error (refresh! rig "pi.lab"))) "empty")))
        (testing "content that is not a private key is a failure"
          (reset! (:response rig) {:body "<html>not a key</html>"})
          (is (str/includes? (str (:error (refresh! rig "pi.lab"))) "private key")))
        (is (frozen? path) "a failed refresh must not touch the cached key")
        (is (= first-key (cached-text path)))))))

(deftest an-unsafe-cache-file-stops-the-operation
  (with-rig-key! {:body first-key}
    (fn [rig]
      (let [path (:path (refresh! rig "pi.lab"))]
        (fs/set-posix-file-permissions path "rw-r--r--")
        (let [{:keys [error]} (refresh! rig "pi.lab")]
          (is (str/includes? (str error) path))
          (is (str/includes? (str error) "rw-r--r--")))))))

(deftest a-failed-replacement-stops-the-operation
  (with-rig-key! {:body first-key}
    (fn [rig]
      (let [runtime         (session/runtime
                             {:env        {"XDG_CACHE_HOME" (:cache rig)}
                              :fetch!     (:fetch! rig)
                              :filesystem {:write-key! (fn [_path _bytes]
                                                         (throw (ex-info "read-only file system" {})))}})
            {:keys [error]} (key/refresh! "pi.lab" runtime)]
        (is (str/includes? (str error) "read-only file system"))
        (is (not (fs/exists? (str (:cache rig) "/probetron/keys/pi.lab.key"))))))))

(deftest a-client-without-a-cache-home-explains-itself
  (with-rig-key! {:body first-key}
    (fn [rig]
      (let [runtime         (session/runtime {:env {} :fetch! (:fetch! rig)})
            {:keys [error]} (key/refresh! "pi.lab" runtime)]
        (is (str/includes? (str error) "XDG_CACHE_HOME"))
        (is (str/includes? (str error) "HOME"))))))

(deftest the-http-adapter-reports-what-the-rig-answered
  (with-rig-key! {:body first-key}
    (fn [rig]
      (let [url (str "http://127.0.0.1:" (:port rig) "/probetron_key")]
        (is (= first-key (String. ^bytes (:body (key/http-fetch! url)))))
        (reset! (:response rig) {:status 404 :body "no key"})
        (is (str/includes? (str (:error (key/http-fetch! url))) "404"))
        (is (some? (:error (key/http-fetch! "http://127.0.0.1:1/probetron_key")))
            "a rig that never answers is a failure, not an empty key")))))

(defn with-rig-key!
  "Serve one key over a local HTTP fixture and give the body a temporary cache home."
  [response body]
  (let [state     (atom (merge {:status 200} response))
        stop!     (server/run-server (fn [_request] @state) {:port 0 :ip "127.0.0.1"})
        port      (:local-port (meta stop!))
        cache     (str (fs/create-temp-dir {:prefix "probetron-client"}))
        requested (atom [])]
    (try
      (body {:response  state
             :port      port
             :cache     cache
             :requested requested
             :fetch!    (fixture-fetch! port requested)})
      (finally (stop!) (fs/delete-tree cache)))))

(defn fixture-fetch!
  "Return a fetch adapter that sends the client request to the local fixture."
  [port requested]
  (fn [url]
    (swap! requested conj url)
    (key/http-fetch! (str/replace url #"^http://[^/]+" (str "http://127.0.0.1:" port)))))

(defn refresh!
  "Refresh the cached key of one host through the fixture."
  [rig host]
  (key/refresh! host (session/runtime {:env    {"XDG_CACHE_HOME" (:cache rig)}
                                       :fetch! (:fetch! rig)})))

(defn cached-text
  "Return the text of a cached key."
  [path]
  (String. (fs/read-all-bytes path)))

(defn permissions
  "Return the POSIX permissions of a cached key."
  [path]
  (fs/posix->str (fs/posix-file-permissions path)))

(defn freeze!
  "Mark a cache file, so a later write is visible."
  [path]
  (fs/set-last-modified-time path 1000))

(defn frozen?
  "Tell whether a cache file still carries its mark."
  [path]
  (= 1000 (.toMillis (fs/last-modified-time path))))
