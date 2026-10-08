(ns thebes.agg
  "Mergeable aggregations.

  An aggregation is a plain map:

    :zero     identity accumulator
    :add      (fn [acc event]) folds a single event into an accumulator
    :merge    (fn [a b]) combines two accumulators
    :present  (fn [acc]) turns an accumulator into a client-facing value

  Every aggregation here is a commutative monoid: `merge` is associative and
  commutative with `zero` as its identity, and adding events one at a time
  gives the same result as merging the accumulators of any partition of those
  events. That property is what makes the lambda architecture work: the batch
  layer can fold the master dataset in parallel chunks, and the serving layer
  can merge a batch view with the speed layer's view of recent events, and
  both arrive at exactly the same answer."
  (:refer-clojure :exclude [count max min]))

(defn- value-of
  "Applies `f` to `event`, returning nil for non-numeric values so that
  aggregations over numbers skip missing or malformed fields."
  [f event]
  (let [v (f event)]
    (when (number? v) v)))

(defn count
  "Number of events."
  []
  {:zero    0
   :add     (fn [n _] (inc n))
   :merge   +
   :present identity})

(defn sum
  "Sum of the numeric values of `(f event)`."
  [f]
  {:zero    0
   :add     (fn [acc e] (if-let [v (value-of f e)] (+ acc v) acc))
   :merge   +
   :present identity})

(defn- pick [better?]
  (fn [a b]
    (cond (nil? a) b
          (nil? b) a
          (better? b a) b
          :else a)))

(defn max
  "Largest numeric value of `(f event)`, or nil if there were none."
  [f]
  (let [merge (pick >)]
    {:zero    nil
     :add     (fn [acc e] (merge acc (value-of f e)))
     :merge   merge
     :present identity}))

(defn min
  "Smallest numeric value of `(f event)`, or nil if there were none."
  [f]
  (let [merge (pick <)]
    {:zero    nil
     :add     (fn [acc e] (merge acc (value-of f e)))
     :merge   merge
     :present identity}))

(defn mean
  "Arithmetic mean of the numeric values of `(f event)`."
  [f]
  {:zero    [0 0]
   :add     (fn [[n s :as acc] e]
              (if-let [v (value-of f e)] [(inc n) (+ s v)] acc))
   :merge   (fn [[n1 s1] [n2 s2]] [(+ n1 n2) (+ s1 s2)])
   :present (fn [[n s]] (when (pos? n) (double (/ s n))))})

(def ^:private empty-stats {:n 0 :mean 0.0 :m2 0.0 :min nil :max nil})

(defn- merge-stats
  "Combines two partial summaries using Chan et al.'s parallel variance
  algorithm, which is exact (up to floating point) for any partitioning."
  [a b]
  (let [na (:n a) nb (:n b)]
    (cond
      (zero? na) b
      (zero? nb) a
      :else
      (let [n     (+ na nb)
            delta (- (:mean b) (:mean a))]
        {:n    n
         :mean (+ (:mean a) (* delta (/ (double nb) n)))
         :m2   (+ (:m2 a) (:m2 b) (* delta delta (/ (* (double na) nb) n)))
         :min  ((pick <) (:min a) (:min b))
         :max  ((pick >) (:max a) (:max b))}))))

(defn stats
  "Count, mean, population standard deviation, min and max of the numeric
  values of `(f event)`, computed in a single mergeable pass."
  [f]
  {:zero    empty-stats
   :add     (fn [acc e]
              (if-let [v (value-of f e)]
                (merge-stats acc {:n 1 :mean (double v) :m2 0.0 :min v :max v})
                acc))
   :merge   merge-stats
   :present (fn [{:keys [n mean m2 min max]}]
              (when (pos? n)
                {:count  n
                 :mean   mean
                 :stddev (Math/sqrt (/ m2 n))
                 :min    min
                 :max    max}))})

(defn latest
  "The most recent value of `(f event)`, where recency is decided by
  `(by event)`, typically a timestamp. Ties are broken deterministically,
  by hash and then by printed form, so that merging stays commutative."
  [f by]
  (let [newer? (fn [a b]
                 (let [c (compare (:at a) (:at b))
                       c (if (zero? c) (compare (hash (:value a)) (hash (:value b))) c)
                       c (if (zero? c) (compare (pr-str (:value a)) (pr-str (:value b))) c)]
                   (pos? c)))
        merge  (pick newer?)]
    {:zero    nil
     :add     (fn [acc e]
                (let [at (by e) v (f e)]
                  (if (and (some? at) (some? v))
                    (merge acc {:at at :value v})
                    acc)))
     :merge   merge
     :present :value}))

;; HyperLogLog ---------------------------------------------------------------

(def ^:private hll-precision 10)
(def ^:private hll-registers (bit-shift-left 1 hll-precision))
(def ^:private hll-zero (vec (repeat hll-registers 0)))

(defn- mix64
  "MurmurHash3's 64-bit finalizer. Spreads Clojure's 32-bit `hash` across 64
  bits so we can take a register index and a rank from independent bits."
  ^long [^long h]
  (let [h (bit-xor h (unsigned-bit-shift-right h 33))
        h (unchecked-multiply h (unchecked-long 0xff51afd7ed558ccd))
        h (bit-xor h (unsigned-bit-shift-right h 33))
        h (unchecked-multiply h (unchecked-long 0xc4ceb9fe1a85ec53))]
    (bit-xor h (unsigned-bit-shift-right h 33))))

(defn- hll-add [registers x]
  (let [h    (mix64 (hash x))
        idx  (unsigned-bit-shift-right h (- 64 hll-precision))
        rest (bit-shift-left h hll-precision)
        rank (clojure.core/min (inc (Long/numberOfLeadingZeros rest))
                               (- 65 hll-precision))]
    (if (> rank (nth registers idx))
      (assoc registers idx rank)
      registers)))

(defn- hll-estimate [registers]
  (let [m     (double hll-registers)
        alpha (/ 0.7213 (+ 1 (/ 1.079 m)))
        raw   (/ (* alpha m m)
                 (reduce (fn [acc r] (+ acc (Math/pow 2.0 (- r)))) 0.0 registers))
        zeros (clojure.core/count (filter zero? registers))]
    (Math/round
     (double (if (and (<= raw (* 2.5 m)) (pos? zeros))
               (* m (Math/log (/ m zeros)))
               raw)))))

(defn distinct-approx
  "Approximate number of distinct values of `(f event)`, using a HyperLogLog
  sketch with 1024 registers (about 3% standard error). Sketches merge by
  taking the register-wise max, so distinct counts combine across batch and
  speed layers without storing the values themselves."
  [f]
  {:zero    hll-zero
   :add     (fn [regs e] (let [v (f e)] (if (some? v) (hll-add regs v) regs)))
   :merge   (fn [a b] (mapv clojure.core/max a b))
   :present hll-estimate})

;; Combinators ---------------------------------------------------------------

(defn filtered
  "Applies `agg` only to events matching `pred`."
  [pred agg]
  (let [add (:add agg)]
    (assoc agg :add (fn [acc e] (if (pred e) (add acc e) acc)))))

(defn multi
  "Combines named aggregations into one that computes all of them in a single
  pass, presenting a map of name to result.

    (multi {:readings (count) :level (stats :level)})"
  [aggs]
  (let [ks (vec (keys aggs))]
    {:zero    (into {} (map (fn [k] [k (:zero (aggs k))])) ks)
     :add     (fn [acc e]
                (reduce (fn [acc k] (assoc acc k ((:add (aggs k)) (acc k) e))) acc ks))
     :merge   (fn [a b]
                (into {} (map (fn [k] [k ((:merge (aggs k)) (a k) (b k))])) ks))
     :present (fn [acc]
                (into {} (map (fn [k] [k ((:present (aggs k)) (acc k))])) ks))}))
