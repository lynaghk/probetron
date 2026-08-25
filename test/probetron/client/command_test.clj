(ns probetron.client.command-test
  (:require [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [probetron.client.command :as command]))

(declare shell-tokens ssh-argv)

(def hostile-values
  "Values that a shell would read as more than one token."
  ["plain"
   "with space"
   "it's"
   "$(touch /tmp/probetron-escape)"
   "`touch /tmp/probetron-escape`"
   "a;rm -rf /"
   "a && b"
   "a | b"
   "a > /tmp/probetron-escape"
   "a\nb"
   "$HOME"
   "*"
   "--chip=RP2 350"
   "\\"
   "\"double\""])

(deftest quoting-keeps-every-value-inside-one-remote-token
  (is (= hostile-values (shell-tokens hostile-values))
      "a POSIX shell must read each quoted value as exactly one unchanged token")
  (testing "an ordinary value stays readable"
    (is (= "RP235x" (command/shell-token "RP235x")))
    (is (= "--speed-khz" (command/shell-token "--speed-khz")))
    (is (= "/usr/local/sbin/probetron-rig" (command/shell-token "/usr/local/sbin/probetron-rig"))))
  (testing "a value that carries a shell character is quoted"
    (is (= "'a b'" (command/shell-token "a b")))
    (is (= "'it'\\''s'" (command/shell-token "it's")))))

(deftest the-remote-command-reaches-the-rig-through-sudo
  (is (= "sudo -n /usr/local/sbin/probetron-rig reset"
         (command/remote-command {:operation :reset :host "pi.lab"})))
  (is (= "sudo -n /usr/local/sbin/probetron-rig info --format edn"
         (command/remote-command {:operation :info :host "pi.lab" :format :edn})))
  (is (= "sudo -n /usr/local/sbin/probetron-rig status --format text"
         (command/remote-command {:operation :status :host "pi.lab" :format :text})))
  (is (= "sudo -n /usr/local/sbin/probetron-rig erase --chip RP235x --speed-khz 1000"
         (command/remote-command {:operation :erase :host "pi.lab" :chip "RP235x" :speed-khz 1000})))
  (testing "the flash command carries no client path, because the ELF travels on standard input"
    (let [text (command/remote-command {:operation :flash :host "pi.lab" :chip "RP235x"
                                        :speed-khz 4000 :elf "/home/bench/firmware.elf"})]
      (is (= "sudo -n /usr/local/sbin/probetron-rig flash --chip RP235x --speed-khz 4000" text))
      (is (not (str/includes? text "firmware.elf"))))))

(deftest the-ssh-argv-carries-the-cached-key-and-no-persistent-host-key
  (is (= ["/opt/ssh" "-i" "/cache/pi.lab.key" "-T"
          "-o" "BatchMode=yes"
          "-o" "IdentitiesOnly=yes"
          "-o" "StrictHostKeyChecking=no"
          "-o" "UserKnownHostsFile=/dev/null"
          "-o" "GlobalKnownHostsFile=/dev/null"
          "-o" "LogLevel=ERROR"
          "probetron@pi.lab"
          "sudo -n /usr/local/sbin/probetron-rig reset"]
         (ssh-argv "pi.lab")))
  (testing "one remote command travels as one argument"
    (is (= 1 (count (filter #(str/starts-with? % "sudo ") (ssh-argv "pi.lab")))))))

(deftest the-key-endpoint-and-its-cache-follow-the-host
  (is (= "http://pi.lab/probetron_key" (command/key-url "pi.lab")))
  (is (= "http://192.168.1.20/probetron_key" (command/key-url "192.168.1.20")))
  (testing "an IPv6 literal keeps its brackets"
    (is (= "http://[fd00::2]/probetron_key" (command/key-url "fd00::2"))))
  (testing "XDG_CACHE_HOME wins over HOME"
    (is (= "/cache/probetron/keys/pi.lab.key"
           (command/key-path {"XDG_CACHE_HOME" "/cache" "HOME" "/home/bench"} "pi.lab")))
    (is (= "/home/bench/.cache/probetron/keys/pi.lab.key"
           (command/key-path {"HOME" "/home/bench"} "pi.lab"))))
  (testing "every host keeps its own cache file"
    (is (not= (command/key-path {"HOME" "/home/bench"} "pi.lab")
              (command/key-path {"HOME" "/home/bench"} "pi2.lab")))
    (is (= "/home/bench/.cache/probetron/keys/fd00__2.key"
           (command/key-path {"HOME" "/home/bench"} "fd00::2"))))
  (testing "a client without a cache home has no key path"
    (is (nil? (command/key-path {} "pi.lab")))))

(deftest private-key-framing-is-recognised
  (is (command/private-key? (.getBytes "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----\n")))
  (is (not (command/private-key? (.getBytes "<html>404</html>"))))
  (is (not (command/private-key? (.getBytes "ssh-ed25519 AAAA probetron@probetron"))))
  (is (not (command/private-key? (.getBytes "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n"))))
  (is (not (command/private-key? (byte-array 0)))))

(defn shell-tokens
  "Return the tokens a POSIX shell reads from quoted values."
  [values]
  (let [script (str "printf '%s\\0' " (str/join " " (map command/shell-token values)))
        {:keys [out]} (process/shell {:out :string :continue true} "/bin/sh" "-c" script)]
    (vec (butlast (str/split out #"\x00" -1)))))

(defn ssh-argv
  "Return the argv of one reset operation against a host."
  [host]
  (command/ssh-argv {:ssh "/opt/ssh" :key "/cache/pi.lab.key" :host host}
                    (command/remote-command {:operation :reset :host host})))
