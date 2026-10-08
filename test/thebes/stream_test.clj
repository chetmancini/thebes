(ns thebes.stream-test
  (:require [clojure.test :refer [deftest is]]
            [thebes.stream :as stream]))

(deftest keeps-only-recent-items
  (let [s (stream/create :s {:capacity 5})
        seen (atom [])
        unsub (stream/subscribe! s #(swap! seen conj (:seq %)))]
    (dotimes [i 100] (stream/emit! s {:i i}))
    (unsub)
    (stream/emit! s {:i 100})
    (is (= [97 98 99 100 101] (map :seq (stream/since s 0))))
    (is (= [100 101] (map :seq (stream/since s 99))))
    (is (= (range 1 101) @seen) "subscribers see every item until they unsubscribe")
    (is (instance? clojure.lang.PersistentVector (:items @(:state s)))
        "the buffer is a fresh vector, not a subvec retaining old items")))
