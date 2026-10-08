(ns thebes.processor
  "The processor: master dataset, batch layer, speed layer and serving layer.

  Events arrive from a transport. Each one is appended to the log, which
  assigns its offset, and then streamed through the speed-layer topology:

      transport ─▶ log ─▶ source ─▶ prepare ─┬─▶ views      (speed layer)
                   │                         └─▶ streams    (realtime alerts)
                   └──────▶ batch layer (periodic parallel recompute)

  An *app* describes what to compute:

    :prepare   (fn [event]) -> event, applied before views and streams in
               both layers (derive fields here, never in only one layer)
    :views     a sequence of views (see `thebes.view`)
    :streams   map of stream name to transducer over prepared events; may be
               stateful, as streams run single-threaded and in log order"
  (:require [clojure.core.async :as a]
            [clojure.core.reducers :as r]
            [thebes.lambda :as lambda]
            [thebes.log :as log]
            [thebes.stream :as stream]
            [thebes.topology :as topology]
            [thebes.transport :as transport]
            [thebes.view :as view]))

(defn- now [] (System/currentTimeMillis))

(defn- safe-prepare
  "The app's `:prepare`, returning nil for events it can't handle (or nil
  events, from unreadable log lines). Both layers use this, so a bad event
  is skipped by both instead of crashing one and not the other."
  [app]
  (let [prepare (:prepare app identity)]
    (fn [event]
      (when (some? event)
        (try (prepare event) (catch Exception _ nil))))))

(defn run-batch!
  "Recomputes every view from the master dataset. The watermark is taken,
  and a fresh speed-layer epoch opened, atomically with respect to appends,
  so no event is missed or double counted. Runs are serialized. Returns the
  run's summary."
  [{:keys [app log state batch-lock]}]
  (locking batch-lock
    (let [started   (now)
          watermark (log/with-watermark log
                      (fn [w] (swap! state lambda/begin-batch w) w))
          events    (log/read-events log watermark)
          views     (view/fold (:views app)
                               (->> events (r/map (safe-prepare app)) (r/remove nil?)))
          info      {:computed-at started :duration-ms (- (now) started)}]
      (swap! state lambda/complete-batch watermark views info)
      (assoc info :watermark watermark))))

(defn- speed-topology [{:keys [app state streams]} source]
  (let [views   (:views app)
        prepare (safe-prepare app)]
    (topology/start!
     {:source source
      :nodes
      (into {:prepare {:from        :source
                       :xf          (map (fn [{:keys [offset event]}]
                                           {:offset offset :event (prepare event)}))
                       :parallelism 4}
             :views   {:from :prepare
                       :sink (fn [{:keys [offset event]}]
                               (swap! state lambda/add-event views offset event))}}
            (mapcat (fn [[stream-name xf]]
                      (let [node (keyword (str "stream." (name stream-name)))]
                        [[node {:from :prepare :xf (comp (keep :event) xf)}]
                         [(keyword (str (name node) ".emit"))
                          {:from node :sink #(stream/emit! (streams stream-name) %)}]])))
            (:streams app))})))

(defn- start-scheduler [processor interval-ms]
  (let [stop   (a/chan)
        thread (a/thread
      (loop []
        (let [[_ ch] (a/alts!! [stop (a/timeout interval-ms)])]
          (when-not (= ch stop)
            (when (> (log/size (:log processor))
                     (get-in @(:state processor) [:batch :watermark]))
              (try
                (run-batch! processor)
                (catch Throwable t
                  (binding [*out* *err*]
                    (println "thebes.processor: batch run failed:" (.getMessage t))))))
            (recur)))))]
    (fn []
      (a/close! stop)
      (a/<!! thread))))

(defn start!
  "Starts a processor for `app`, reading events from `transport`, with its
  master dataset in `data-dir`. Runs one batch synchronously so existing
  data is served immediately. Options: `:batch-interval-ms` (default 10s)."
  [{:keys [app transport data-dir batch-interval-ms]
    :or   {batch-interval-ms 10000}}]
  (let [processor {:app        app
                   :log        (log/open data-dir)
                   :state      (atom (lambda/initial-state))
                   :batch-lock (Object.)
                   :streams    (into {} (map (fn [k] [k (stream/create k {})])) (keys (:streams app)))
                   :started-at (now)}
        _         (run-batch! processor)
        source    (a/chan 1024)
        topo      (speed-topology processor source)
        unsub     (transport/subscribe!
                   transport
                   (fn [event]
                     (let [offset (log/append! (:log processor) event)]
                       (a/>!! source {:offset offset :event event}))))
        unsched   (start-scheduler processor batch-interval-ms)]
    (assoc processor
           :topology topo
           :stop (fn []
                   (unsched)
                   (unsub)
                   (a/close! source)
                   (a/<!! (:done topo))
                   (log/close (:log processor))))))

(defn stop! [processor]
  ((:stop processor)))

(defn find-view [{:keys [app]} view-name]
  (some #(when (= (name (:name %)) (name view-name)) %) (:views app)))

(defn query
  "Rows of `view-name` merged across batch and speed layers, or nil if no
  such view exists."
  [processor view-name]
  (when-let [v (find-view processor view-name)]
    (let [state @(:state processor)]
      {:view         (:name v)
       :watermark    (get-in state [:batch :watermark])
       :speed-events (lambda/speed-count state)
       :rows         (view/present v (lambda/view-state state v))})))

(defn status [{:keys [log state topology started-at streams]}]
  (let [s @state]
    {:log-size     (log/size log)
     :processed    (:processed s)
     :speed-events (lambda/speed-count s)
     :epochs       (count (:epochs s))
     :batch        (select-keys (:batch s) [:watermark :runs :computed-at :duration-ms])
     :topology     @(:counters topology)
     :streams      (update-vals streams #(:seq @(:state %)))
     :uptime-ms    (- (now) started-at)}))
