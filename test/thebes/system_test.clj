(ns thebes.system-test
  "End to end: HTTP ingest through the transport, log, both layers and the
  query API, plus restarting a node over an existing master dataset."
  (:require [clojure.test :refer [deftest is testing]]
            [thebes.examples.nile-simulation :as sim]
            [thebes.system :as system]
            [thebes.test-util :refer [eventually http temp-dir]]))

(defn- processed [port]
  (:processed (second (http port "GET" "/api/v1/status"))))

(defn- rows [port view]
  (:rows (second (http port "GET" (str "/api/v1/views/" view)))))

(defn- ingest-hours [port gen hours]
  (reduce + (for [h hours]
              (let [readings (gen h)
                    [status body] (http port "POST" "/api/v1/events" readings)]
                (assert (= 202 status) (pr-str body))
                (count readings)))))

(deftest ingest-query-and-restart
  (let [dir (temp-dir)
        gen (sim/create 7)
        sys (system/start! {:port 0 :data-dir dir :batch-interval-ms 60000})
        port (:port sys)]
    (try
      (let [n (ingest-hours port gen (range 48))]
        (is (eventually #(= n (processed port))))
        (testing "speed layer serves events before any batch run"
          (let [[_ body] (http port "GET" "/api/v1/views/stations")]
            (is (= 0 (:watermark body)))
            (is (= n (:speed-events body)))
            (is (= n (reduce + (map (comp :readings :value) (:rows body)))))))
        (testing "a batch run takes over without changing answers"
          (let [before (rows port "stations")
                [status batch] (http port "POST" "/api/v1/batch")]
            (is (= 200 status))
            (is (= n (:watermark batch)))
            (is (= (map :key before) (map :key (rows port "stations"))))
            (is (= (map (comp :readings :value) before)
                   (map (comp :readings :value) (rows port "stations"))))))
        (testing "filtering rows by key part"
          (let [[_ body] (http port "GET" "/api/v1/views/stations?station=luxor&_=123")]
            (is (= [{:station "luxor"}] (map :key (:rows body)))
                "unknown parameters are ignored"))))
      (finally (system/stop! sys)))
    (testing "a restarted node rebuilds its views from the master dataset"
      (let [sys (system/start! {:port 0 :data-dir dir :batch-interval-ms 60000})]
        (try
          (let [[_ body] (http (:port sys) "GET" "/api/v1/views/stations")]
            (is (pos? (:watermark body)))
            (is (= (:watermark body) (reduce + (map (comp :readings :value) (:rows body))))))
          (finally (system/stop! sys)))))))

(deftest api-errors
  (let [sys (system/start! {:port 0 :data-dir (temp-dir)})
        port (:port sys)]
    (try
      (is (= 400 (first (http port "POST" "/api/v1/events" "{not json"))))
      (is (= 400 (first (http port "POST" "/api/v1/events" ""))))
      (let [[status body] (http port "POST" "/api/v1/events"
                                [{:station "luxor" :level 1.0 :ts 1} {:station "rome" :level 1 :ts 1}])]
        (is (= 422 status))
        (is (= [{:index 1 :error "unknown station: rome"}] (:problems body))))
      (is (= 404 (first (http port "GET" "/api/v1/views/nope"))))
      (is (= 404 (first (http port "GET" "/api/v1/streams/nope"))))
      (is (= 405 (first (http port "DELETE" "/api/v1/views"))))
      (is (= 200 (first (http port "GET" "/health"))))
      (finally (system/stop! sys)))))

(deftest roles-need-a-shared-transport
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"memory transport"
                        (system/start! {:role :ingest :port 0 :data-dir (temp-dir)}))))
