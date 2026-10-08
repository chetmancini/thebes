(ns thebes.lambda-test
  "The key invariant of the architecture: at every moment, querying the
  serving state gives exactly the views of every event that has been either
  processed by the speed layer or covered by a batch run, each counted once,
  however batch runs interleave with in-flight events."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [thebes.agg :as agg]
            [thebes.lambda :as lambda]
            [thebes.view :as view]))

(def views
  [{:name :by-key :group-by {:k :k} :agg (agg/multi {:n (agg/count) :sum (agg/sum :v)})}
   {:name :total :agg (agg/count)}
   {:name :evens :where #(even? (:v %)) :agg (agg/sum :v)}])

(def gen-event (gen/hash-map :k (gen/elements [:a :b :c]) :v gen/small-integer))

(def gen-op
  "One step of a simulated processor: append the next event to the log,
  stream the next appended event through the speed layer, start a batch run
  at the current log size, or complete the oldest batch run in progress."
  (gen/frequency [[4 (gen/return :append)]
                  [4 (gen/return :process)]
                  [1 (gen/return :begin-batch)]
                  [1 (gen/return :complete-batch)]]))

(defn- present-all [state]
  (into {} (for [v views] [(:name v) (view/present v (lambda/view-state state v))])))

(defn- truth
  "Views over every event the serving state should count: those covered by
  the batch watermark plus those the speed layer has processed."
  [events processed watermark]
  (let [counted (keep-indexed (fn [offset e] (when (or (< offset watermark) (processed offset)) e))
                              events)]
    (into {} (for [v views] [(:name v) (view/present v (get (view/compute [v] counted) (:name v) {}))]))))

(defspec serving-counts-every-event-exactly-once 300
  (prop/for-all [events (gen/vector gen-event 0 40)
                 ops    (gen/vector gen-op 0 150)]
    (loop [ops       ops
           state     (lambda/initial-state)
           appended  0      ; log size
           streamed  0      ; next offset the speed layer will process
           processed #{}
           pending   []     ; watermarks of batch runs in progress, oldest first
           ok?       true]
      (let [ok? (and ok? (= (truth events processed (get-in state [:batch :watermark]))
                            (present-all state)))]
        (if-let [[op & more] (seq ops)]
          (case op
            :append
            (recur more state (min (count events) (inc appended)) streamed processed pending ok?)

            :process
            (if (< streamed appended)
              (recur more (lambda/add-event state views streamed (events streamed))
                     appended (inc streamed) (conj processed streamed) pending ok?)
              (recur more state appended streamed processed pending ok?))

            :begin-batch
            (recur more (lambda/begin-batch state appended)
                   appended streamed processed (conj pending appended) ok?)

            :complete-batch
            (if-let [w (first pending)]
              (recur more (lambda/complete-batch state w (view/compute views (subvec events 0 w)) {})
                     appended streamed processed (subvec pending 1) ok?)
              (recur more state appended streamed processed pending ok?)))
          ok?)))))

(deftest speed-events-are-retired-by-batch
  (let [events [{:k :a :v 1} {:k :b :v 2} {:k :a :v 3}]
        total  (second views)
        s (-> (lambda/initial-state)
              (lambda/add-event views 0 (events 0))
              (lambda/add-event views 1 (events 1))
              (lambda/begin-batch 2)
              (lambda/add-event views 2 (events 2)))]
    (is (= 3 (lambda/speed-count s)))
    (let [s (lambda/complete-batch s 2 (view/compute views (take 2 events)) {})]
      (is (= 2 (get-in s [:batch :watermark])))
      (is (= 1 (lambda/speed-count s)))
      (is (= [{:key {} :value 3}] (view/present total (lambda/view-state s total)))))
    (testing "late events already covered by the batch are ignored"
      (let [s (-> (lambda/initial-state)
                  (lambda/begin-batch 3)
                  (lambda/complete-batch 3 (view/compute views events) {})
                  (lambda/add-event views 1 (events 1)))]
        (is (= 0 (lambda/speed-count s)))
        (is (= [{:key {} :value 3}] (view/present total (lambda/view-state s total))))))))
