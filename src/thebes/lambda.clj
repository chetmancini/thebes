(ns thebes.lambda
  "The serving state of a lambda architecture, as pure functions.

  The state combines two layers:

    :batch   views precomputed by the batch layer over every event whose log
             offset is below `:watermark`
    :epochs  views maintained incrementally by the speed layer, keyed by the
             offset at which each epoch starts

  Each event is routed to the speed-layer epoch covering its offset. When a
  batch run begins at watermark W, a new epoch is opened at W, so events at or
  past W accumulate separately from the events the batch is about to cover.
  When the run completes, its views replace the old batch views and every
  epoch below W is discarded in the same step. A query merges the batch views
  with every live epoch, so at all times every processed event is counted
  exactly once.

  Events can reach the speed layer after a batch has already covered them
  (the log is written before events are streamed). Those events fall below
  the batch watermark and are ignored, because the batch already counts them."
  (:require [thebes.view :as view]))

(defn initial-state []
  {:batch     {:watermark 0 :views {} :runs 0}
   :epochs    (sorted-map 0 {:count 0 :views {}})
   :processed 0})

(defn- epoch-for [epochs offset]
  (first (keys (rsubseq epochs <= offset))))

(defn add-event
  "Folds the event at log `offset` into the speed layer. A nil event (one
  the app couldn't prepare) counts as processed but changes no view."
  [state views offset event]
  (let [state (update state :processed inc)]
    (if-let [start (and (some? event)
                        (>= offset (get-in state [:batch :watermark]))
                        (epoch-for (:epochs state) offset))]
      (update-in state [:epochs start]
                 (fn [epoch]
                   (-> epoch
                       (update :count inc)
                       (update :views #(view/add-to-all views % event)))))
      state)))

(defn begin-batch
  "Opens a speed-layer epoch at `watermark`, the offset a batch run is about
  to compute up to (exclusive). No event at or past `watermark` may have
  been added yet; the processor guarantees this by choosing the watermark
  while holding the log's append lock."
  [state watermark]
  (update state :epochs
          (fn [epochs]
            (if (contains? epochs watermark)
              epochs
              (assoc epochs watermark {:count 0 :views {}})))))

(defn complete-batch
  "Installs batch views computed over all events below `watermark` and
  retires the speed-layer epochs those events were counted in."
  [state watermark views-state info]
  (-> state
      (update :batch (fn [batch]
                       (merge batch info {:watermark watermark
                                          :views     views-state
                                          :runs      (inc (:runs batch 0))})))
      (update :epochs (fn [epochs]
                        (into (sorted-map) (subseq epochs >= watermark))))))

(defn view-state
  "The complete state of `view`: batch views merged with every live epoch."
  [state view]
  (let [k (:name view)]
    (reduce (fn [acc epoch] (view/merge-states view acc (get-in epoch [:views k] {})))
            (get-in state [:batch :views k] {})
            (vals (:epochs state)))))

(defn speed-count
  "Number of events currently held only by the speed layer."
  [state]
  (transduce (map :count) + (vals (:epochs state))))
