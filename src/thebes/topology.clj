(ns thebes.topology
  "A small dataflow topology built on core.async, in the spirit of Storm's
  spouts and bolts but running in-process.

  A topology is data:

    {:source  a core.async channel feeding the topology
     :nodes   {node-id node-spec}}

  Each node reads from `:from` (`:source` or another node id) and is either

    a *transform*  {:from id :xf transducer :parallelism n}
                   emitting the transduced values to downstream nodes, or
    a *sink*       {:from id :sink (fn [x])}
                   consuming values for side effects.

  Transforms with `:parallelism` above 1 run on `core.async/pipeline`, which
  keeps output order but gives every element a fresh transducer, so they
  must be stateless. Single-threaded transforms keep their transducer's state
  across elements, which makes stateful transducers (deduplication,
  windowing, edge detection) work as you'd expect.

  When a node has several consumers its output is broadcast to all of them.
  Back-pressure flows upstream: a slow sink slows the whole topology rather
  than buffering without bound. Closing the source drains and shuts down
  every node."
  (:require [clojure.core.async :as a]))

(def ^:private buffer-size 1024)

(defn- order-nodes
  "Returns node ids so that every node comes after the node it reads from."
  [nodes]
  (loop [placed #{:source} ordered [] pending (set (keys nodes))]
    (if (empty? pending)
      ordered
      (let [ready (filter #(placed (:from (nodes %))) (sort-by str pending))]
        (when (empty? ready)
          (throw (ex-info "Topology has a cycle or an unknown :from"
                          {:nodes (select-keys nodes pending)})))
        (recur (into placed ready) (into ordered ready) (reduce disj pending ready))))))

(defn- reporter [on-error node-id]
  (fn [^Throwable t]
    (on-error node-id t)
    nil))

(defn- counted [counters node-id]
  (map (fn [x] (swap! counters update node-id (fnil inc 0)) x)))

(defn start!
  "Starts the topology described by `spec`. Returns a map of

    :done      channel that closes once the source is closed and every sink
               has drained
    :counters  atom of node id to number of values each node has emitted

  Options:
    :on-error  (fn [node-id throwable]) called when a transform or sink
               throws; the offending value is dropped and processing
               continues. Defaults to printing to *err*."
  [{:keys [source nodes on-error]
    :or   {on-error (fn [id ^Throwable t]
                      (binding [*out* *err*]
                        (println "thebes.topology:" id "failed:" (.getMessage t))))}}]
  (let [order     (order-nodes nodes)
        consumers (group-by (comp :from nodes) order)
        counters  (atom {})
        ;; Wire every channel before any value flows: a mult with no taps
        ;; would silently drop values. Each output is [channel parallel-xf];
        ;; single-threaded transforms carry their transducer on the channel.
        outputs   (into {:source [source nil]}
                        (for [id order
                              :let [{:keys [xf parallelism] :or {parallelism 1}} (nodes id)]
                              :when xf
                              :let [xf (comp xf (counted counters id))]]
                          [id (if (> parallelism 1)
                                [(a/chan buffer-size) xf]
                                [(a/chan buffer-size xf (reporter on-error id)) nil])]))
        inputs    (into {}
                        (mapcat (fn [[from ids]]
                                  (let [[out] (outputs from)]
                                    (if (= 1 (count ids))
                                      [[(first ids) out]]
                                      (let [m (a/mult out)]
                                        (for [id ids]
                                          [id (a/tap m (a/chan buffer-size))]))))))
                        consumers)
        sinks     (doall
                   (for [id order
                         :let [{:keys [xf sink parallelism] :or {parallelism 1}} (nodes id)
                               in  (inputs id)
                               [out parallel-xf] (outputs id)]]
                     (cond
                       sink
                       (a/thread
                         (loop []
                           (when-some [x (a/<!! in)]
                             (try
                               (sink x)
                               (swap! counters update id (fnil inc 0))
                               (catch Throwable t (on-error id t)))
                             (recur))))

                       xf
                       (do
                         (if parallel-xf
                           (a/pipeline parallelism out parallel-xf in true (reporter on-error id))
                           (a/pipe in out))
                         (when-not (seq (consumers id))
                           ;; Nothing reads this node; drain it so it never
                           ;; blocks upstream.
                           (a/go-loop [] (when (some? (a/<! out)) (recur))))
                         nil)

                       :else
                       (throw (ex-info "Topology node needs :xf or :sink" {:node id})))))]
    {:done     (a/go (doseq [s (remove nil? sinks)] (a/<! s)))
     :counters counters}))
