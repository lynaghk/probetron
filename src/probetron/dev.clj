(ns probetron.dev
  "Development-only network deploy of the working-tree rig code.

   The rig runs interpreted Babashka from /usr/local/lib, so a dev loop needs no
   SD reflash: rig-dev-deploy tars the working-tree src, opens one SSH invocation
   with the same cached key every client operation already uses, and extracts it
   into a tmpfs mounted over the rig's code directory. probetron-rig runs from
   that directory on the next operation, so nothing needs restarting. The overlay
   is volatile, so rig-dev-reset or a reboot restores the image's own code with
   nothing ever written to the card.

   This namespace ships in the repo for the bb tasks alone; it is not part of the
   client or the rig that an installed Probetron carries."
  (:require [babashka.process :as process]
            [clojure.string :as str]
            [probetron.client.command :as command]
            [probetron.client.key :as rig-key]
            [probetron.client.session :as session]))

(declare deploy! reset-overlay! run-remote! resolve-host remote-command deploy-script reset-script
         source-classpath fail! announce!)

(def code-directory
  "The rig directory that carries the interpreted code, which probetron-rig runs
   from when no checkout shadows it. A tmpfs mounted here overlays the image's
   own code for the life of the boot and hides nothing else, because every rig
   executable lives in the sibling bin directory instead."
  "/usr/local/lib")

(def source-directory
  "The working-tree directory whose probetron namespaces become the rig code."
  "src")

(defn deploy!
  "Push the working-tree rig code to one rig over SSH and return the exit status.

   The whole src tree travels as a tar on standard input, exactly as a flash ELF
   does, so no client path reaches the rig, and the remote command mounts the
   overlay and extracts the tree as root."
  [args]
  (let [runtime (session/runtime)
        host (resolve-host args (:env runtime))]
    (if (str/blank? host)
      (fail! "no rig host: pass --host <host> or set PROBETRON_HOST")
      (let [{:keys [path error]} (rig-key/refresh! host runtime)]
        (if error
          (fail! error)
          (do
            (announce! (str "deploying " source-directory "/ to " host ":" code-directory
                            " over an ephemeral tmpfs overlay"))
            (let [tar (process/process ["tar" "czf" "-" "-C" source-directory "probetron"]
                                       {:out :pipe :err :inherit})
                  exit (run-remote! runtime path host (deploy-script) {:in (:out tar)})]
              @tar
              (if (zero? exit)
                (do (announce! (str "deployed; the next probetron operation on " host
                                    " runs this code. Reboot or `bb rig-dev-reset --host " host
                                    "` restores the image code."))
                    exit)
                (fail! (str "the rig deploy failed with exit " exit))))))))))

(defn reset-overlay!
  "Remove the dev code overlay on one rig, restoring the image code, and return the status."
  [args]
  (let [runtime (session/runtime)
        host (resolve-host args (:env runtime))]
    (if (str/blank? host)
      (fail! "no rig host: pass --host <host> or set PROBETRON_HOST")
      (let [{:keys [path error]} (rig-key/refresh! host runtime)]
        (if error
          (fail! error)
          (run-remote! runtime path host (reset-script) {:in ""}))))))

(defn run-remote!
  "Run one root shell script on the rig over SSH and return the process exit status.

   The script arrives as a single quoted argument to sudo sh -c, and the streams
   carry whatever the caller pipes in and the rig's own output back untouched."
  [runtime key-path host script streams]
  (let [request {:ssh (get-in runtime [:executables :ssh]) :key key-path :host host}
        argv (command/ssh-argv request [] (remote-command script))
        result ((:run! runtime) argv (merge {:out :inherit :err :inherit} streams))]
    (:exit result)))

(defn remote-command
  "Wrap one shell script as the single remote command that sudo runs as root."
  [script]
  (str "sudo -n sh -c " (command/shell-token script)))

(defn deploy-script
  "Return the root script that overlays the code directory and extracts the tree.

   A first deploy mounts a fresh tmpfs, which is empty, and a redeploy reuses the
   overlay after clearing it, so a removed namespace never survives under the new
   tree. The extract reads the tar the client pipes in on standard input."
  []
  (str/join
   "\n"
   ["set -eu"
    (str "lib=" code-directory)
    "mountpoint -q \"$lib\" || mount -t tmpfs tmpfs \"$lib\""
    "rm -rf \"$lib\"/* \"$lib\"/.[!.]* 2>/dev/null || true"
    "tar xzf - -C \"$lib\""]))

(defn reset-script
  "Return the root script that unmounts the dev overlay if one is mounted."
  []
  (str/join
   "\n"
   ["set -eu"
    (str "lib=" code-directory)
    "if mountpoint -q \"$lib\"; then umount \"$lib\"; echo \"removed the dev overlay at $lib\";"
    " else echo \"no dev overlay mounted at $lib\"; fi"]))

(defn resolve-host
  "Return the rig host from --host, a bare argument, or PROBETRON_HOST."
  [args env]
  (let [args (vec args)
        flagged (second (drop-while #(not= "--host" %) args))
        bare (first (remove #(str/starts-with? % "-") args))]
    (or flagged bare (get env "PROBETRON_HOST"))))

(defn announce!
  "Write one development diagnostic to standard error."
  [message]
  (binding [*out* *err*] (println (str "rig-dev: " message))))

(defn fail!
  "Report why the deploy stopped and return the failure status."
  [message]
  (announce! message)
  1)
