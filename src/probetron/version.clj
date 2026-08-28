(ns probetron.version
  "The Probetron release version and the build stamp that names one build of it.

   `probetron-version` is the release, and it must stay equal to the VERSION
   file at the project root.
   `describe` reads the fuller stamp that names one build.
   An installed tree reads the stamp that `bb package` baked in: the commit it
   was built from, whether that tree was dirty, and when.
   A raw checkout asks git about probetron's own sources and names the path it
   runs from, not a build time, because a checkout was never built."
  (:require [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(declare from-resource git-stamp from-git project-root git)

(def probetron-version "0.1.0")

(defn now
  "Return the current instant as an ISO-8601 string truncated to the second."
  []
  (str (.truncatedTo (java.time.Instant/now) java.time.temporal.ChronoUnit/SECONDS)))

(defn describe
  "Return the build stamp of this Probetron.

   A packaged stamp is {:version v :commit sha :dirty? bool :built iso-8601}.
   A checkout stamp is {:version v :commit sha :dirty? bool :root path}.
   The baked stamp of an installed tree wins, and a checkout that carries none
   falls back to git."
  []
  (or (from-resource) (from-git)))

(defn stamp-line
  "Render one build stamp as the single line that names a build to a person.

   A packaged build reads '0.1.0 (f7749b0, 2026-08-26T12:00:00Z)', a checkout
   reads '0.1.0 (f7749b0, /home/dev/probetron)', and a dirty tree marks its
   commit 'f7749b0-dirty'."
  [{:keys [version commit dirty? built root]}]
  (str version
       " (" commit (when dirty? "-dirty")
       (when built (str ", " built))
       (when root (str ", " root))
       ")"))

(defn git-stamp
  "Return the release, commit, and dirtiness of the git checkout at root.

   A tree that git cannot read still yields a stamp, because a build names
   itself even where no repository does."
  [root]
  {:version probetron-version
   :commit  (or (git root "rev-parse" "--short" "HEAD") "unknown")
   :dirty?  (boolean (seq (git root "status" "--porcelain")))})

(defn from-resource
  "Return the stamp that bb package baked into the archive, or nil in a checkout."
  []
  (when-let [url (io/resource "probetron/build.edn")]
    (edn/read-string (slurp url))))

(defn from-git
  "Return a live stamp from probetron's own git checkout, named by its path.

   The stamp must name probetron's tree, not the directory the operator ran
   from, so it asks git about probetron's own root."
  []
  (let [root (project-root)]
    (assoc (git-stamp root) :root root)))

(defn project-root
  "Return the path of probetron's own checkout, or \".\" when it cannot be found.

   probetron finds its root from version.clj on the classpath, which sits at
   root/src/probetron/version.clj, so the answer holds wherever the operator
   ran the command from."
  []
  (let [url (io/resource "probetron/version.clj")]
    (if (and url (= "file" (.getProtocol url)))
      (-> url .getPath io/file .getParentFile .getParentFile .getParentFile str)
      ".")))

(defn git
  "Return the trimmed output of one git command in a directory, or nil when it fails."
  [root & argv]
  (let [{:keys [exit out]} @(process/process (into ["git" "-C" root] argv)
                                             {:out :string :err :string})]
    (when (zero? exit) (str/trim out))))
