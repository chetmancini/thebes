(ns thebes.transport
  "Transports carry events from the ingest API to the processor.

  A transport is a work queue: every published event is delivered to one
  subscriber, at least once. The in-memory transport connects both roles
  inside one JVM; the RabbitMQ transport lets ingest nodes and the processor
  run as separate processes, on separate machines.")

(defprotocol Transport
  (publish! [transport events]
    "Enqueues `events`, a sequence of maps, returning once the transport has
    taken responsibility for all of them. May block to apply back-pressure.")
  (subscribe! [transport handler]
    "Calls `(handler event)` for each delivered event. The event counts as
    handled once `handler` returns. If it throws, a durable transport
    redelivers the event later. Returns a no-argument function that stops
    the subscription, waiting for deliveries already in progress.")
  (close! [transport]
    "Releases the transport's resources."))

(defn create
  "Creates a transport from a config map whose `:type` is `:memory` or
  `:rabbitmq`. Implementations are loaded on demand, so RabbitMQ classes are
  only touched when they are used."
  [{:keys [type] :as config}]
  (let [ctor (case type
               :memory   'thebes.transport.memory/create
               :rabbitmq 'thebes.transport.rabbitmq/create
               (throw (ex-info (str "Unknown transport: " type) {:config config})))]
    ((requiring-resolve ctor) config)))
