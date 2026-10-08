(ns thebes.view-test
  (:require [clojure.test :refer [deftest is]]
            [thebes.agg :as agg]
            [thebes.view :as view]))

(def by-color
  {:name :by-color :group-by {:color :color} :where :ok? :agg (agg/sum :n)})

(def events
  [{:color "red" :n 1 :ok? true}
   {:color "blue" :n 2 :ok? true}
   {:color "red" :n 3 :ok? true}
   {:color "red" :n 100 :ok? false}])

(deftest grouping-filtering-and-presentation
  (is (= [{:key {:color "blue"} :value 2} {:key {:color "red"} :value 4}]
         (view/present by-color (:by-color (view/compute [by-color] events))))))

(deftest parallel-fold-matches-sequential-compute
  (let [many (vec (for [i (range 20000)] {:color (["red" "green" "blue"] (mod i 3)) :n i :ok? (odd? i)}))
        total {:name :total :agg (agg/count)}]
    (is (= (view/compute [by-color total] many)
           (view/fold [by-color total] 64 many)))))

(deftest rows-sort-by-key-parts-in-order
  (let [v {:name :v :group-by {:a :a :b :b} :agg (agg/count)}
        rows (view/present v (:v (view/compute [v] [{:a 2 :b 1} {:a 10 :b 1} {:a 2 :b 0} {:a nil :b 5}])))]
    (is (= [{:a nil :b 5} {:a 2 :b 0} {:a 2 :b 1} {:a 10 :b 1}] (map :key rows)))))

(deftest time-buckets
  (let [hour (view/time-bucket :ts :hour)]
    (is (= 3600000 (hour {:ts 3600000})))
    (is (= 3600000 (hour {:ts 7199999})))
    (is (nil? (hour {})))))
