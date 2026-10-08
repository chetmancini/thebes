(ns thebes.transport.rabbitmq
  "A transport over a RabbitMQ queue, using Langohr.

  Delivery is at least once. Events are published as persistent JSON
  messages to a durable queue, with publisher confirms, so `publish!`
  returns only once the broker has them. The subscriber acknowledges each
  message after its handler returns and requeues it if the handler throws,
  so an event that was not yet written to the master dataset is delivered
  again."
  (:require [cheshire.core :as json]
            [langohr.basic :as lb]
            [langohr.channel :as lch]
            [langohr.confirm :as lcf]
            [langohr.consumers :as lc]
            [langohr.core :as rmq]
            [langohr.queue :as lq]
            [thebes.transport :as transport]))

(def ^:private confirm-timeout-ms 10000)

(defrecord RabbitTransport [conn publish-ch queue prefetch]
  transport/Transport
  (publish! [_ events]
    ;; Channels must not be shared between threads that publish concurrently.
    (locking publish-ch
      (doseq [event events]
        (lb/publish publish-ch "" queue (json/generate-string event)
                    {:content-type "application/json"
                     :persistent   true
                     :type         "thebes.event"
                     :app-id       "thebes"}))
      (lcf/wait-for-confirms-or-die publish-ch confirm-timeout-ms)))

  (subscribe! [_ handler]
    (let [ch        (lch/open conn)
          stopping? (atom false)
          in-flight (atom 0)
          _         (lb/qos ch prefetch)
          tag       (lc/subscribe
                     ch queue
                     (fn [ch {:keys [delivery-tag]} ^bytes payload]
                       (swap! in-flight inc)
                       (try
                         (if @stopping?
                           (lb/nack ch delivery-tag false true)
                           (do (handler (json/parse-string (String. payload "UTF-8") true))
                               (lb/ack ch delivery-tag)))
                         (catch Throwable t
                           (binding [*out* *err*]
                             (println "thebes.transport.rabbitmq: requeueing message:" (.getMessage t)))
                           (when (rmq/open? ch)
                             (lb/nack ch delivery-tag false true)))
                         (finally (swap! in-flight dec))))
                     {:auto-ack false})]
      (fn stop []
        (reset! stopping? true)
        (lb/cancel ch tag)
        ;; Let deliveries already inside the handler finish and ack, so
        ;; nothing written to the log is redelivered after a clean stop.
        (let [deadline (+ (System/currentTimeMillis) 10000)]
          (while (and (pos? @in-flight) (< (System/currentTimeMillis) deadline))
            (Thread/sleep 10)))
        (when (rmq/open? ch) (rmq/close ch)))))

  (close! [_]
    (when (rmq/open? publish-ch) (rmq/close publish-ch))
    (when (rmq/open? conn) (rmq/close conn))))

(defn default-uri
  "The broker URI from THEBES_RABBITMQ_URI, or a local broker. Without
  credentials in the URI the client uses RabbitMQ's local defaults."
  []
  (or (System/getenv "THEBES_RABBITMQ_URI") "amqp://localhost:5672"))

(defn create
  "Connects to RabbitMQ and declares the queue. Options:

    :uri          amqp:// URI (default: see `default-uri`)
    :queue        queue name (default \"thebes.events\")
    :durable?     survive broker restarts (default true)
    :auto-delete? delete when the last consumer goes away (default false)
    :prefetch     unacknowledged messages per consumer (default 256)"
  [{:keys [uri queue durable? auto-delete? prefetch]
    :or   {queue        "thebes.events"
           durable?     true
           auto-delete? false
           prefetch     256}}]
  (let [conn (rmq/connect {:uri (or uri (default-uri))})
        ch   (lch/open conn)]
    (lcf/select ch)
    (lq/declare ch queue {:durable durable? :exclusive false :auto-delete auto-delete?})
    (->RabbitTransport conn ch queue prefetch)))
