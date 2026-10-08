(ns thebes.view
  "Views: named, grouped aggregations over the event stream.

  A view is a map:

    :name      keyword identifying the view
    :doc       optional description
    :group-by  map of key name to (fn [event]) -> key part; omit for a single
               global row
    :where     optional predicate selecting which events count
    :agg       an aggregation from `thebes.agg`

  A view's *state* is a map of group key to accumulator. The functions here
  are pure; the batch and speed layers use the same ones, which is how they
  stay consistent with each other."
  (:require [clojure.core.reducers :as r]))

(defn group-key [{:keys [group-by]} event]
  (when group-by
    (persistent!
     (reduce-kv (fn [k part f] (assoc! k part (f event))) (transient {}) group-by))))

(defn add-event
  "Folds `event` into `state`, a map of group key to accumulator. If the
  view's functions throw on an event, the event is skipped for this view.
  Both layers share this function, so they skip exactly the same events."
  [{:keys [where agg] :as view} state event]
  (try
    (if (or (nil? where) (where event))
      (let [k (group-key view event)]
        (assoc state k ((:add agg) (get state k (:zero agg)) event)))
      state)
    (catch Exception _ state)))

(defn merge-states [{:keys [agg]} a b]
  (merge-with (:merge agg) a b))

(defn- compare-parts
  "Orders key parts of possibly different types: nils first, then values of
  the same class by their natural order, otherwise by their string form."
  [a b]
  (cond
    (and (nil? a) (nil? b)) 0
    (nil? a) -1
    (nil? b) 1
    (or (= (class a) (class b)) (and (number? a) (number? b))) (compare a b)
    :else (compare (str a) (str b))))

(defn present
  "Turns a view state into rows of `{:key {...} :value ...}`, sorted by key."
  [{:keys [agg group-by]} state]
  (let [parts (vec (keys group-by))
        sort-key (fn [row] (mapv #(get (:key row) %) parts))]
    (->> state
         (map (fn [[k acc]] {:key (or k {}) :value ((:present agg) acc)}))
         (sort (fn [a b]
                 (or (first (remove zero? (map compare-parts (sort-key a) (sort-key b))))
                     0)))
         vec)))

(defn add-to-all
  "Folds `event` into the state of every view in `views`. `states` is a map of
  view name to view state."
  [views states event]
  (reduce (fn [states view]
            (update states (:name view) #(add-event view (or % {}) event)))
          states
          views))

(defn merge-all [views a b]
  (reduce (fn [acc view]
            (let [k (:name view)]
              (assoc acc k (merge-states view (get a k {}) (get b k {})))))
          {}
          views))

(defn compute
  "Computes the state of every view over `events`, sequentially."
  [views events]
  (reduce (partial add-to-all views) {} events))

(defn fold
  "Computes the state of every view over `events` in parallel using
  `clojure.core.reducers/fold`. Each fork/join chunk builds its own partial
  states, which are then merged. `events` should be a vector (or another
  foldable collection) for the work to actually be split."
  ([views events] (fold views 512 events))
  ([views chunk-size events]
   (r/fold chunk-size
           (fn
             ([] {})
             ([a b] (merge-all views a b)))
           (partial add-to-all views)
           events)))

(def ^:private bucket-millis
  {:second 1000
   :minute (* 60 1000)
   :hour   (* 60 60 1000)
   :day    (* 24 60 60 1000)})

(defn time-bucket
  "Returns a function of an event that truncates the epoch-millisecond value
  of `(f event)` to the start of its `unit` (:second, :minute, :hour, :day).
  Handy in `:group-by` for time-series views."
  [f unit]
  (let [ms (or (bucket-millis unit)
               (throw (ex-info "Unknown time bucket" {:unit unit})))]
    (fn [event]
      (when-let [t (f event)]
        (* ms (quot (long t) ms))))))
