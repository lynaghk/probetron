(ns probetron.version
  "The Probetron release version and the build stamp that names one build of it.

   `probetron-version` is the release, and it must stay equal to the VERSION
   file at the project root.
   `describe` reads the fuller stamp that `bb package` bakes into the archive:
   the commit it was built from, whether that tree was dirty, and when.
   An installed tree reads the baked stamp, and a raw checkout asks git, so the
   same command names a release on a rig and a working tree on a bench."
  (:require [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(declare from-resource git-stamp from-git git)

(def probetron-version "0.1.0")

(defn now
  "Return the current instant as an ISO-8601 string truncated to the second."
  []
  (str (.truncatedTo (java.time.Instant/now) java.time.temporal.ChronoUnit/SECONDS)))

(defn describe
  "Return the build stamp of this Probetron.

   The stamp is {:version v :commit sha :dirty? bool :built iso-8601}.
   The baked stamp of an installed tree wins, and a checkout that carries none
   falls back to git."
  []
  (or (from-resource) (from-git)))

(defn stamp-line
  "Render one build stamp as the single line that names a build to a person.

   The form is '0.1.0 (f7749b0, 2026-08-26T12:00:00Z)', and a dirty tree marks
   its commit 'f7749b0-dirty'."
  [{:keys [version commit dirty? built]}]
  (str version
       " (" commit (when dirty? "-dirty")
       (when built (str ", " built)) ")"))

(defn git-stamp
  "Return the build stamp of the git checkout at root, timed at built.

   A tree that git cannot read still yields a stamp, because a build names
   itself even where no repository does."
  [root built]
  {:version probetron-version
   :commit (or (git root "rev-parse" "--short" "HEAD") "unknown")
   :dirty? (boolean (seq (git root "status" "--porcelain")))
   :built built})

(defn from-resource
  "Return the stamp that bb package baked into the archive, or nil in a checkout."
  []
  (when-let [url (io/resource "probetron/build.edn")]
    (edn/read-string (slurp url))))

(defn from-git
  "Return a live stamp from the git checkout of the current directory."
  []
  (git-stamp "." (now)))

(defn git
  "Return the trimmed output of one git command in a directory, or nil when it fails."
  [root & argv]
  (let [{:keys [exit out]} @(process/process (into ["git" "-C" root] argv)
                                             {:out :string :err :string})]
    (when (zero? exit) (str/trim out))))
