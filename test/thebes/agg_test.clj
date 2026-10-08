(ns thebes.agg-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [thebes.agg :as agg]))

(def gen-event
  (gen/hash-map :x (gen/one-of [gen/small-integer (gen/return nil) (gen/return "n/a")])
                :ts gen/nat
                :tag (gen/elements [:a :b :c :d])))

(def aggregations
  {:count    (agg/count)
   :sum      (agg/sum :x)
   :min      (agg/min :x)
   :max      (agg/max :x)
   :mean     (agg/mean :x)
   :stats    (agg/stats :x)
   :latest   (agg/latest :tag :ts)
   :distinct (agg/distinct-approx :tag)
   :filtered (agg/filtered #(= :a (:tag %)) (agg/count))
   :multi    (agg/multi {:n (agg/count) :s (agg/stats :x)})})

(defn- fold-events [{:keys [zero add]} events]
  (reduce add zero events))

(defn- close?
  "Equality tolerant of floating point error, for presented results."
  [a b]
  (cond
    (and (number? a) (number? b)) (< (Math/abs (- (double a) (double b))) 1e-6)
    (and (map? a) (map? b)) (and (= (keys a) (keys b)) (every? #(close? (a %) (b %)) (keys a)))
    :else (= a b)))

(defspec merging-partitions-equals-folding-everything 200
  (prop/for-all [events (gen/vector gen-event)
                 split  gen/nat
                 k      (gen/elements (keys aggregations))]
    (let [{:keys [merge present] :as a} (aggregations k)
          split (min split (count events))
          [left right] (split-at split events)]
      (close? (present (fold-events a events))
              (present (merge (fold-events a left) (fold-events a right)))))))

(defspec merge-is-commutative-with-zero-identity 200
  (prop/for-all [xs (gen/vector gen-event)
                 ys (gen/vector gen-event)
                 k  (gen/elements (keys aggregations))]
    (let [{:keys [zero merge present] :as a} (aggregations k)
          x (fold-events a xs)
          y (fold-events a ys)]
      (and (close? (present (merge x y)) (present (merge y x)))
           (close? (present x) (present (merge zero x)))
           (close? (present x) (present (merge x zero)))))))

(deftest presentations
  (let [events [{:x 2 :ts 1 :tag :a} {:x 4 :ts 3 :tag :b} {:x nil :ts 2 :tag :c}]]
    (is (= 3 ((:present (agg/count)) (fold-events (agg/count) events))))
    (is (= 6 (fold-events (agg/sum :x) events)))
    (is (= 3.0 ((:present (agg/mean :x)) (fold-events (agg/mean :x) events))))
    (is (nil? ((:present (agg/mean :x)) (:zero (agg/mean :x)))))
    (is (= :b ((:present (agg/latest :tag :ts)) (fold-events (agg/latest :tag :ts) events))))
    (testing "stats"
      (let [s ((:present (agg/stats :x)) (fold-events (agg/stats :x) events))]
        (is (= {:count 2 :mean 3.0 :stddev 1.0 :min 2 :max 4} s))))))

(deftest distinct-approx-accuracy
  (let [a (agg/distinct-approx identity)]
    (is (= 0 ((:present a) (:zero a))))
    (is (= 5 ((:present a) (reduce (:add a) (:zero a) (concat (range 5) (range 5))))))
    (doseq [n [1000 50000]]
      (let [estimate ((:present a) (reduce (:add a) (:zero a) (map #(str "user-" %) (range n))))]
        (is (< (Math/abs (- 1.0 (/ estimate (double n)))) 0.1)
            (str "estimate " estimate " for " n " distinct values"))))))

(deftest latest-ties-are-commutative-even-when-hashes-collide
  (is (= (hash "Aa") (hash "BB")))
  (let [{:keys [add merge present zero]} (agg/latest :v :ts)
        a (add zero {:v "Aa" :ts 1})
        b (add zero {:v "BB" :ts 1})]
    (is (= (present (merge a b)) (present (merge b a))))))
