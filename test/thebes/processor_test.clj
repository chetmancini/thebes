(ns thebes.processor-test
  "The two layers must agree even about events the app can't handle."
  (:require [clojure.test :refer [deftest is]]
            [thebes.agg :as agg]
            [thebes.processor :as processor]
            [thebes.transport :as transport]
            [thebes.test-util :refer [eventually temp-dir]]))

(def picky-app
  {:name    "picky"
   :prepare (fn [{:keys [x] :as e}] (if (= 2 x) (throw (ex-info "bad" {})) e))
   :views   [{:name :total :agg (agg/sum :x)}
             {:name :inverse :agg (agg/multi {:n (agg/count)
                                              :sum (assoc (agg/sum :x) :add (fn [acc e] (+ acc (/ 1 (- (:x e) 3)))))})}]
   :streams {:all (map #(select-keys % [:x]))}})

(deftest bad-events-are-skipped-by-both-layers
  (let [t (transport/create {:type :memory})
        p (processor/start! {:app picky-app :transport t :data-dir (temp-dir) :batch-interval-ms 60000})
        result (fn [] (mapv #(:rows (processor/query p %)) [:total :inverse]))]
    (try
      (transport/publish! t (for [x [1 2 3 4]] {:x x}))
      (is (eventually #(= 4 (:processed (processor/status p)))))
      (let [speed (result)]
        (is (= [{:key {} :value 8}] (first speed)) "x=2 fails prepare and is skipped")
        (is (= 4 (:watermark (processor/run-batch! p))) "the batch layer still completes")
        (is (= 0 (:speed-events (processor/status p))))
        (is (= speed (result)) "and agrees with the speed layer"))
      (finally
        (processor/stop! p)
        (transport/close! t)))))
