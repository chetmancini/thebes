(ns thebes.examples.nile-test
  (:require [clojure.test :refer [deftest is]]
            [thebes.examples.nile :as nile]
            [thebes.examples.nile-simulation :as sim]))

(defn- reading [level] (nile/prepare {:station "luxor" :level level :ts 0}))

(deftest flood-detector-uses-hysteresis
  (let [alerts (into [] (nile/flood-detector)
                     (map reading [6.0 6.6 6.4 6.6 6.2 6.0 6.7]))]
    (is (= ["flood" "receded" "flood"] (map :type alerts)))
    (is (= [6.6 6.0 6.7] (map :level alerts)))))

(deftest validation
  (is (nil? (nile/validate {:station "cairo" :level 3.2 :ts 1})))
  (is (= "unknown station: rome" (nile/validate {:station "rome" :level 1 :ts 1})))
  (is (= "level must be a number" (nile/validate {:station "cairo" :level "high" :ts 1})))
  (is (= "ts must be epoch milliseconds" (nile/validate {:station "cairo" :level 1}))))

(deftest simulation-is-deterministic-and-floods-every-station
  (let [season (mapcat (sim/create 1) (range (* 24 30)))
        alerts (into [] (comp (map nile/prepare) (nile/flood-detector)) season)]
    (is (= (take 50 season) (take 50 (mapcat (sim/create 1) (range 3)))))
    (is (= (set (map :id nile/stations))
           (set (map :station (filter #(= "flood" (:type %)) alerts)))))
    (is (every? #(nil? (nile/validate %)) season))))
