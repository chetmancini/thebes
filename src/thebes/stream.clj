(ns thebes.stream
  "Realtime output streams produced by the speed layer.

  A stream keeps a bounded buffer of its most recent items, each tagged with
  a sequence number so clients can poll for what they haven't seen, and
  pushes new items to live subscribers (used for Server-Sent Events).
  Streams are ephemeral: unlike views they are not rebuilt from the master
  dataset.")

(defn create [stream-name {:keys [capacity] :or {capacity 500}}]
  {:name        stream-name
   :capacity    capacity
   :state       (atom {:seq 0 :items []})
   :subscribers (atom {})})

(defn emit! [{:keys [capacity state subscribers]} item]
  (let [{:keys [seq]} (swap! state
                             (fn [{:keys [seq items]}]
                               (let [items (conj items (assoc item :seq (inc seq)))]
                                 {:seq   (inc seq)
                                  ;; Copy rather than subvec, which would keep
                                  ;; every past item reachable.
                                  :items (if (> (count items) capacity)
                                           (into [] (subvec items (- (count items) capacity)))
                                           items)})))
        tagged (assoc item :seq seq)]
    (doseq [f (vals @subscribers)]
      (try (f tagged) (catch Throwable _)))))

(defn since
  "Items with a sequence number greater than `n`, oldest first."
  [{:keys [state]} n]
  (filterv #(> (:seq %) n) (:items @state)))

(defn subscribe!
  "Calls `(f item)` for each new item. Returns a function that unsubscribes."
  [{:keys [subscribers]} f]
  (let [k (Object.)]
    (swap! subscribers assoc k f)
    #(swap! subscribers dissoc k)))
