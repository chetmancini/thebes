(ns thebes.transport.rabbitmq-test
  "Integration tests against a real broker. They run only when
  THEBES_RABBITMQ_URI is set, e.g. after `docker compose up -d rabbitmq`:

    THEBES_RABBITMQ_URI=amqp://localhost:5672 clojure -X:test"
  (:require [clojure.test :refer [deftest is]]
            [thebes.system :as system]
            [thebes.test-util :refer [eventually http temp-dir]]))

(def ^:private uri (System/getenv "THEBES_RABBITMQ_URI"))

(deftest ingest-and-processor-as-separate-nodes
  (if-not uri
    (println "Skipping RabbitMQ integration test: THEBES_RABBITMQ_URI is not set")
    (let [transport {:type :rabbitmq :uri uri :queue (str "thebes.test-" (random-uuid))
                     :auto-delete? true}
          processor (system/start! {:role :processor :port 0 :data-dir (temp-dir) :transport transport})
          ingest    (system/start! {:role :ingest :port 0 :transport transport})]
      (try
        (is (= 202 (first (http (:port ingest) "POST" "/api/v1/events"
                                (for [i (range 50)] {:station "aswan" :level (+ 3.0 (/ i 10.0)) :ts i})))))
        (is (eventually #(= 50 (:processed (second (http (:port processor) "GET" "/api/v1/status"))))))
        (is (= 404 (first (http (:port ingest) "GET" "/api/v1/views/stations")))
            "ingest nodes do not serve views")
        (is (= 404 (first (http (:port processor) "POST" "/api/v1/events" [])))
            "processor nodes do not ingest")
        (finally
          (system/stop! ingest)
          (system/stop! processor))))))
