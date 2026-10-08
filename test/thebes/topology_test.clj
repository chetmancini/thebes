(ns thebes.topology-test
  (:require [clojure.core.async :as a]
            [clojure.test :refer [deftest is]]
            [thebes.topology :as topology]))

(defn- run-topology [nodes inputs]
  (let [source (a/chan)
        t      (topology/start! {:source source :nodes nodes :on-error (fn [_ _])})]
    (a/onto-chan!! source inputs)
    (a/<!! (:done t))
    t))

(deftest fan-out-and-parallel-transforms
  (let [a (atom []) b (atom [])
        t (run-topology {:inc   {:from :source :xf (map inc) :parallelism 4}
                         :evens {:from :inc :xf (filter even?)}
                         :a     {:from :evens :sink #(swap! a conj %)}
                         :b     {:from :inc :sink #(swap! b conj %)}}
                        (range 100))]
    (is (= (range 1 101) @b) "parallel pipelines preserve order")
    (is (= (filter even? (range 1 101)) @a))
    (is (= {:inc 100 :evens 50 :a 50 :b 100} @(:counters t)))))

(deftest single-threaded-transforms-keep-state
  (let [out (atom [])]
    (run-topology {:dedupe {:from :source :xf (dedupe)}
                   :sink   {:from :dedupe :sink #(swap! out conj %)}}
                  [1 1 2 2 2 3 1 1])
    (is (= [1 2 3 1] @out))))

(deftest errors-drop-the-value-and-continue
  (let [out (atom [])]
    (run-topology {:div  {:from :source :xf (map #(/ 10 %))}
                   :sink {:from :div :sink #(if (= 5 %) (throw (ex-info "boom" {})) (swap! out conj %))}}
                  [1 0 2 5])
    (is (= [10 2] @out))))

(deftest transforms-without-consumers-do-not-block
  (let [out (atom [])]
    (run-topology {:unused {:from :source :xf (map inc)}
                   :sink   {:from :source :sink #(swap! out conj %)}}
                  (range 5000))
    (is (= 5000 (count @out)))))

(deftest rejects-invalid-topologies
  (is (thrown? clojure.lang.ExceptionInfo
               (topology/start! {:source (a/chan) :nodes {:x {:from :missing :sink identity}}})))
  (is (thrown? clojure.lang.ExceptionInfo
               (topology/start! {:source (a/chan) :nodes {:x {:from :source}}}))))
