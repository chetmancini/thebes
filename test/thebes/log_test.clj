(ns thebes.log-test
  (:require [clojure.test :refer [deftest is]]
            [thebes.log :as log]
            [thebes.test-util :refer [temp-dir]]))

(deftest append-read-and-reopen
  (let [dir (temp-dir)
        l   (log/open dir)]
    (is (= 0 (log/size l)))
    (is (= [0 1 2] (mapv #(log/append! l {:n %}) [1 2 3])))
    (is (= [{:n 1} {:n 2}] (log/read-events l 2)))
    (is (= 3 (log/with-watermark l identity)))
    (log/close l)
    (let [reopened (log/open dir)]
      (is (= 3 (log/size reopened)))
      (is (= 3 (log/append! reopened {:n 4})))
      (is (= [{:n 1} {:n 2} {:n 3} {:n 4}] (log/read-events reopened 10)))
      (log/close reopened))))

(deftest heals-a-torn-final-line
  (let [dir (temp-dir)
        l   (log/open dir)]
    (log/append! l {:n 1})
    (log/close l)
    (spit (str dir "/events.jsonl") "{\"n\": 2, \"tor" :append true)
    (let [l (log/open dir)]
      (is (= 2 (log/size l)) "the torn line keeps its offset")
      (is (= 2 (log/append! l {:n 3})))
      (is (= [{:n 1} nil {:n 3}] (log/read-events l 10)))
      (log/close l))))
