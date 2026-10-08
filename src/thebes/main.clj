(ns thebes.main
  "Command line entry point: `clojure -M:run [all|ingest|processor] [options]`."
  (:require [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [thebes.system :as system]))

(def cli-options
  [["-p" "--port PORT" "HTTP port"
    :default 8080 :parse-fn parse-long :validate [#(and % (<= 0 % 65535)) "Must be 0-65535"]]
   ["-d" "--data-dir DIR" "Directory for the master dataset"
    :default "data"]
   ["-a" "--app SYMBOL" "Var holding the app definition"
    :default "thebes.examples.nile/app"]
   ["-t" "--transport TYPE" "memory or rabbitmq"
    :default :memory :parse-fn keyword :validate [#{:memory :rabbitmq} "Must be memory or rabbitmq"]]
   [nil "--rabbitmq-uri URI" "RabbitMQ URI (default: $THEBES_RABBITMQ_URI or amqp://localhost:5672)"]
   [nil "--queue NAME" "RabbitMQ queue name"
    :default "thebes.events"]
   [nil "--batch-interval SECONDS" "Seconds between batch recomputes"
    :default 10 :parse-fn parse-double :validate [#(and % (pos? %)) "Must be positive"]]
   ["-h" "--help"]])

(defn- usage [summary]
  (str/join \newline
            ["Thebes: a lambda architecture for realtime data in Clojure."
             ""
             "Usage: clojure -M:run [role] [options]"
             ""
             "Roles:"
             "  all        ingest + processor in one process (default)"
             "  ingest     accept events over HTTP, publish them to the transport"
             "  processor  consume the transport, compute and serve views"
             ""
             "Options:"
             summary]))

(defn- exit! [code message]
  (println message)
  (System/exit code))

(defn -main [& args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-options)
        role (keyword (or (first arguments) "all"))]
    (cond
      (:help options)                      (exit! 0 (usage summary))
      errors                               (exit! 1 (str/join \newline errors))
      (not (#{:all :ingest :processor} role)) (exit! 1 (str "Unknown role: " (name role) "\n\n" (usage summary))))
    (let [sys (try
                (system/start!
                 {:role              role
                  :app               (:app options)
                  :port              (:port options)
                  :data-dir          (:data-dir options)
                  :batch-interval-ms (long (* 1000 (:batch-interval options)))
                  :transport         (cond-> {:type (:transport options)}
                                       (= :rabbitmq (:transport options))
                                       (assoc :uri (:rabbitmq-uri options) :queue (:queue options)))})
                (catch Exception e
                  (exit! 1 (str "Failed to start: " (or (ex-message e) e)))))]
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. ^Runnable (fn [] (system/stop! sys))))
      (println (format "thebes %s node listening on http://localhost:%d (app %s, %s transport)"
                       (name role) (:port sys) (:name (:app sys)) (name (:transport options))))
      @(promise))))
