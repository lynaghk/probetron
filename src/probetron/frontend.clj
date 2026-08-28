(ns probetron.frontend
  "Pure command-line front end that the public client and the rig entry point share.

   A front end is
   {:program \"probetron\" :help-text fn :command-specs map :command-usage map
    :build fn :use-env? bool :refused fn},
   and every Probetron command line reaches its operation through the same
   dispatch, so a new command or a changed option is one edit rather than two.
   Nothing here touches the filesystem, the network, or the hardware."
  (:require [clojure.string :as str]
            [probetron.operation :as op]))

(declare front-end-commands command-help parse-command option-message)

(def commands
  "The commands that both front ends carry, in help order."
  [:info :status :log :flash :erase :reset :connect :debug])

(defn command-names
  "Return the command names of one front end, as a command line types them."
  [front-end]
  (into {} (map (juxt name identity)) (front-end-commands front-end)))

(def value-option {:coerce :string})
(def flag-option {:coerce :boolean})

(defn parse
  "Turn one argv into an action map for one front end.

   Return {:action :run :operation operation}, {:action :help :text text :exit status},
   {:action :version :program name :exit status}, or {:action :error :message text :exit status}.
   The version action names only the front end, because the build stamp it reads
   belongs to the imperative shell and not to this pure parse."
  [{:keys [program help-text] :as front-end} argv context]
  (let [[head & remaining] argv]
    (cond
      (nil? head)
      {:action :help :text (help-text) :exit op/exit-usage}

      (#{"--help" "-h" "help"} head)
      {:action :help :text (help-text) :exit op/exit-ok}

      (#{"--version" "-V" "version"} head)
      {:action :version :program program :exit op/exit-ok}

      :else
      (if-let [command ((command-names front-end) head)]
        (if (some #{"--help" "-h"} remaining)
          {:action :help :text (command-help front-end command) :exit op/exit-ok}
          (parse-command front-end command (vec remaining) context))
        {:action :error
         :message (str "unknown command " (pr-str head) ": run " program " --help")
         :exit op/exit-usage}))))

(defn usage-lines
  "Return one usage line for every command of one front end."
  [{:keys [command-usage] :as front-end}]
  (map command-usage (front-end-commands front-end)))

(defn front-end-commands
  "Return the commands of one front end, in help order.

   A front end that carries a command of its own states its whole list, and
   every other one takes the commands that both entry points share."
  [front-end]
  (:commands front-end commands))

(defn command-help
  "Return the help of one command."
  [{:keys [command-usage]} command]
  (str/join "\n" ["Usage:" (command-usage command)]))

(defn parse-command
  "Parse the options of one command and build its operation."
  [{:keys [command-specs build use-env?] :as front-end} command argv context]
  (let [{:keys [opts args option-error]} (op/parse-options argv (command-specs command))]
    (if option-error
      {:action :error :message (option-message front-end command option-error) :exit op/exit-usage}
      (let [{:keys [operation errors]} (build command (assoc context
                                                             :opts opts
                                                             :args args
                                                             :use-env? use-env?))]
        (if errors
          {:action :error :message (str/join "\n" errors) :exit op/exit-usage}
          {:action :run :operation operation})))))

(defn option-message
  "Explain an option that a front end refused.

   A front end that refuses one option for a reason of its own supplies
   :refused, and every other unknown option names the help of its command."
  [{:keys [program refused]} command {:keys [option cause message]}]
  (if (= :restrict cause)
    (or (when refused (refused option))
        (str "unknown option --" (name option) " for " program " " (name command)
             ": run " program " " (name command) " --help"))
    message))
