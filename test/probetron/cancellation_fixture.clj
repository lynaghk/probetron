(ns probetron.cancellation-fixture
  "A real client-to-rig pipe with stand-ins only for SSH and target hardware."
  (:require [babashka.fs :as fs]
            [probetron.client.cli :as cli]
            [probetron.client.main :as client]
            [probetron.client.session :as client-session]
            [probetron.rig.config :as config]
            [probetron.rig.main :as rig]
            [probetron.rig.session-fixture :as fixture]
            [probetron.rig.runner :as runner]))

(defn -main
  [side directory command]
  (if (= "client" side)
    (let [runtime (client-session/runtime
                   {:env            {"XDG_CACHE_HOME" (str (fs/path directory "cache"))}
                    :terminal-size! (constantly nil)
                    :fetch!         (fn [_] {:body (.getBytes "-----BEGIN OPENSSH PRIVATE KEY-----\ntest\n-----END OPENSSH PRIVATE KEY-----\n")})
                    :executables    {:ssh (str (fs/path directory "ssh"))}})
          argv    (cond-> [command "--host" "pi.lab" "--chip" "RP2350"]
                    (= "flash" command) (conj (str (fs/path directory "image.elf"))))]
      (System/exit (client/report! (cli/parse argv {:elf-facts client/elf-facts}) runtime)))
    (let [runtime (assoc (fixture/appliance! directory (atom []) {})
                         :stdin (constantly System/in)
                         :perform rig/perform!
                         :run! (fn [_ _] (fixture/record-reset! directory) {:exit 0}))]
      (spit (fs/file (fs/path directory "rig.pid")) (str (.pid (java.lang.ProcessHandle/current))))
      (System/exit (runner/execute! (:operation (config/parse [command "--chip" "RP2350"] {})) runtime)))))
