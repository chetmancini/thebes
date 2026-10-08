(ns thebes.system
  "Starts and stops a Thebes node.

  Roles:
    :all        ingest + processor in one process (works with any transport)
    :ingest     accepts events and publishes them to the transport
    :processor  consumes the transport and serves views and streams

  Splitting roles across processes needs a shared transport like RabbitMQ."
  (:require [thebes.http :as http]
            [thebes.processor :as processor]
            [thebes.transport :as transport]))

(defn resolve-app
  "Resolves an app given as a map or as a fully qualified symbol (or string)
  naming a var that holds one."
  [app]
  (if (map? app)
    app
    (let [sym (symbol app)]
      (or (some-> (requiring-resolve sym) deref)
          (throw (ex-info (str "Cannot resolve app " sym) {:app app}))))))

(defn start!
  "Starts a node. Config keys:

    :role               :all (default), :ingest or :processor
    :app                app map or symbol (default thebes.examples.nile/app)
    :transport          transport config, e.g. {:type :memory}
    :port               HTTP port, 0 for any free port (default 8080)
    :data-dir           master dataset directory (default \"data\")
    :batch-interval-ms  how often the batch layer recomputes (default 10000)

  Returns the system map; pass it to `stop!`."
  [{:keys [role app transport port data-dir batch-interval-ms]
    :or   {role              :all
           app               'thebes.examples.nile/app
           transport         {:type :memory}
           port              8080
           data-dir          "data"
           batch-interval-ms 10000}}]
  (when (and (= :memory (:type transport)) (not= :all role))
    (throw (ex-info "The memory transport only connects roles within one process; use role :all or a RabbitMQ transport"
                    {:role role})))
  (let [app       (resolve-app app)
        transport (transport/create transport)
        processor (when (#{:all :processor} role)
                    (processor/start! {:app               app
                                       :transport         transport
                                       :data-dir          data-dir
                                       :batch-interval-ms batch-interval-ms}))
        server    (http/start! {:role      role
                                :app       app
                                :transport transport
                                :processor processor}
                               {:port port})]
    {:role      role
     :app       app
     :transport transport
     :processor processor
     :server    server
     :port      (http/port server)}))

(defn stop!
  "Stops the node: HTTP first so no new events arrive, then the processor
  (draining in-flight events), then the transport."
  [{:keys [server processor transport]}]
  (http/stop! server)
  (when processor (processor/stop! processor))
  (transport/close! transport))
