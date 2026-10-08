(ns thebes.demo
  "A guided demo: `clojure -M:demo`.

  Starts a Thebes node, streams a simulated flood season of nilometer
  readings into it over HTTP, and renders a live dashboard from the query
  API while the batch and speed layers trade places. It finishes by checking
  that the realtime answer (batch ⊕ speed) equals a full batch recompute."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [thebes.examples.nile :as nile]
            [thebes.examples.nile-simulation :as sim]
            [thebes.system :as system])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.time Instant ZoneOffset)
           (java.time.format DateTimeFormatter)))

;; HTTP client --------------------------------------------------------------

(def ^:private ^HttpClient client (HttpClient/newHttpClient))

(defn- request [base method path body]
  (let [req  (-> (HttpRequest/newBuilder (URI. (str base path)))
                 (.header "Content-Type" "application/json")
                 (.method method (if body
                                   (HttpRequest$BodyPublishers/ofString (json/generate-string body))
                                   (HttpRequest$BodyPublishers/noBody)))
                 .build)
        resp (.send client req (HttpResponse$BodyHandlers/ofString))]
    (when (>= (.statusCode resp) 300)
      (throw (ex-info (str method " " path " failed: " (.statusCode resp) " " (.body resp)) {})))
    (json/parse-string (.body resp) true)))

(defn- GET [base path] (request base "GET" path nil))
(defn- POST [base path body] (request base "POST" path body))

;; Terminal rendering -------------------------------------------------------

(def ^:dynamic *color* true)

(defn- sgr [code s] (if *color* (str "\u001b[" code "m" s "\u001b[0m") (str s)))
(def ^:private bold   (partial sgr "1"))
(def ^:private dim    (partial sgr "2"))
(def ^:private red    (partial sgr "1;31"))
(def ^:private green  (partial sgr "32"))
(def ^:private yellow (partial sgr "33"))
(def ^:private cyan   (partial sgr "36"))
(def ^:private blue   (partial sgr "34"))

(defn- visible-length [s] (count (str/replace s #"\u001b\[[0-9;]*m" "")))
(defn- pad-right [s n] (str s (apply str (repeat (max 0 (- n (visible-length s))) " "))))
(defn- pad-left  [s n] (str (apply str (repeat (max 0 (- n (visible-length s))) " ")) s))

(defn- fmt-int [n] (format "%,d" (long (or n 0))))
(defn- fmt-m [x] (if x (format "%.2fm" (double x)) "–"))

(def ^:private date-fmt (.withZone (DateTimeFormatter/ofPattern "MMM dd HH:mm") ZoneOffset/UTC))
(defn- fmt-ts [ms] (.format date-fmt (Instant/ofEpochMilli ms)))

(def ^:private spark-chars "▁▂▃▄▅▆▇█")

(defn- bar [width & segments]
  ;; segments: [fraction char color-fn]
  (let [cells (map (fn [[f c color]] [(int (Math/round (* width (double f)))) c color]) segments)
        used  (reduce + (map first cells))
        cells (if (> used width)
                (update-in (vec cells) [(dec (count cells)) 0] #(max 0 (- % (- used width))))
                cells)]
    (apply str (for [[n c color] cells] (color (apply str (repeat n c)))))))

(defn- tty? []
  (when-let [console (System/console)]
    (try
      (boolean (clojure.lang.Reflector/invokeInstanceMethod console "isTerminal" (object-array 0)))
      (catch Exception _ true))))

;; Dashboard ----------------------------------------------------------------

(defn- snapshot [base]
  {:status   (GET base "/api/v1/status")
   :stations (GET base "/api/v1/views/stations")
   :daily    (GET base "/api/v1/views/daily")
   :alerts   (:items (GET base "/api/v1/streams/flood-alerts"))})

(defn- season-sparkline
  "One character per day of a station's daily peak, scaled from the station's
  base level to its highest peak, red on days above flood stage."
  [{:keys [base flood-stage]} days width]
  (let [peaks (take-last width (map (comp :peak :value) days))
        hi    (max (+ base 0.1) (reduce max base (remove nil? peaks)))]
    (apply str
           (for [p peaks]
             (if (nil? p)
               " "
               (let [c (nth spark-chars (-> (/ (- p base) (- hi base)) (max 0.0) (min 1.0) (* 7) Math/round int))]
                 (if (> p flood-stage) (red (str c)) (blue (str c)))))))))

(defn- flooded-stations [alerts]
  (reduce (fn [s {:keys [type station]}]
            (if (= "flood" type) (conj s station) (disj s station)))
          #{} alerts))

(defn- station-rows [{:keys [stations daily alerts]} width]
  (let [by-id   (into {} (map (juxt (comp :station :key) :value)) (:rows stations))
        days    (group-by (comp :station :key) (:rows daily))
        flooded (flooded-stations alerts)]
    (for [{:keys [id name] :as station} nile/stations
          :let [v (by-id id)]]
      (str "  " (pad-right name 16)
           (pad-left (fmt-int (:readings v)) 8)
           (pad-left (fmt-m (:current v)) 8)
           (pad-left (fmt-m (get-in v [:level :max])) 8)
           "  " (pad-right (season-sparkline station (days id) width) width)
           "  "
           (cond (nil? v)     (dim "no data")
                 (flooded id) (red "▲ FLOOD")
                 :else        (green "normal"))))))

(defn- sensors-today [{:keys [daily]}]
  (let [rows (:rows daily)]
    (when (seq rows)
      (let [today (reduce max (map (comp :day :key) rows))]
        (transduce (comp (filter #(= today (get-in % [:key :day]))) (map (comp :sensors :value)))
                   + rows)))))

(defn- render [{:keys [status alerts] :as snap} {:keys [hour hours transport sent]}]
  (let [{:keys [log-size speed-events batch]} status
        width      (min 30 (quot hours 24))
        whole      (double (max 1 (+ log-size (max 0 (- sent (:processed status))))))
        in-flight  (max 0 (- sent (:processed status)))
        batch-frac (/ (:watermark batch 0) whole)
        speed-frac (/ speed-events whole)
        hour*      (min hour (dec hours))
        sim-ms     (+ sim/season-start-ms (* hour* 60 60 1000))
        expected   (* sim/sensors-per-station (count nile/stations))
        reporting  (sensors-today snap)]
    (str/join
     \newline
     (concat
      [""
       (str "  " (bold "THEBES") (dim " · lambda architecture demo")
            (pad-left (dim (str "nile app · " (name transport) " transport")) 50))
       ""
       (str "  " (dim "Season  ") (pad-right (fmt-ts sim-ms) 14)
            (bar 30 [(/ hour (double hours)) "━" cyan] [(- 1 (/ hour (double hours))) "━" dim])
            (dim (format "  day %d of %d" (inc (quot hour* 24)) (quot hours 24))))
       (str "  " (dim "Sensors ")
            (cond (nil? reporting)        (dim "–")
                  (< reporting expected)  (yellow (str reporting "/" expected " reporting (outage at Atbara)"))
                  :else                   (str reporting "/" expected " reporting")))
       ""
       (dim (str "  STATION" (pad-left "READINGS" 17) (pad-left "LEVEL" 8) (pad-left "PEAK" 8)
                 "  " (pad-right "DAILY PEAK" width) "  STATUS"))]
      (station-rows snap width)
      [""
       (str "  " (dim "Layers  ")
            (bar 44 [batch-frac "█" cyan] [speed-frac "█" yellow] [(- 1 batch-frac speed-frac) "░" dim]))
       (str "  " (dim "        ")
            (cyan "█") (dim " batch ") (fmt-int (:watermark batch))
            (dim (format " (run %d, %d ms)   " (:runs batch 0) (:duration-ms batch 0)))
            (yellow "█") (dim " speed ") (fmt-int speed-events)
            (dim "   ░ in flight ") (fmt-int in-flight))
       ""
       (str "  " (dim (str "Flood alerts · stateful stream in the speed layer · " (count alerts) " so far")))]
      (let [recent (take-last 6 alerts)]
        (if (empty? recent)
          [(dim "    waiting for the flood…")]
          (for [{:keys [type name level flood-stage ts]} recent]
            (str "    " (dim (fmt-ts ts)) "  "
                 (if (= "flood" type)
                   (str (red "▲ ") (pad-right name 16) (format "rose above flood stage  %.2fm > %.2fm" (double level) (double flood-stage)))
                   (str (green "▼ ") (pad-right name 16) (format "receded                 %.2fm" (double level))))))))
      [""]))))

;; Consistency check --------------------------------------------------------

(defn- approx=
  "Structural equality allowing for floating point differences: the batch
  layer sums in a different order than the speed layer."
  [a b]
  (cond
    (and (number? a) (number? b))
    (<= (Math/abs (- (double a) (double b))) (* 1e-9 (max 1.0 (Math/abs (double a)))))
    (and (map? a) (map? b))
    (and (= (set (keys a)) (set (keys b))) (every? #(approx= (a %) (b %)) (keys a)))
    (and (sequential? a) (sequential? b))
    (and (= (count a) (count b)) (every? true? (map approx= a b)))
    :else (= a b)))

(defn- wait-until-processed [base n]
  (loop [tries 0]
    (let [{:keys [processed]} (GET base "/api/v1/status")]
      (cond (>= processed n) true
            (> tries 600)    (throw (ex-info "Timed out waiting for the processor" {:processed processed}))
            :else            (do (Thread/sleep 50) (recur (inc tries)))))))

;; Main ---------------------------------------------------------------------

(def cli-options
  [["-t" "--transport TYPE" "memory or rabbitmq"
    :default :memory :parse-fn keyword :validate [#{:memory :rabbitmq} "Must be memory or rabbitmq"]]
   [nil "--rabbitmq-uri URI" "RabbitMQ URI (default: $THEBES_RABBITMQ_URI or amqp://localhost:5672)"]
   ["-u" "--url URL" "Drive an already-running node instead of starting one"]
   [nil "--days DAYS" "Length of the simulated season"
    :default 30 :parse-fn parse-long :validate [#(and % (<= 1 % 365)) "Must be 1-365"]]
   [nil "--rate HOURS" "Simulated hours per second"
    :default 36 :parse-fn parse-long :validate [#(and % (pos? %)) "Must be positive"]]
   [nil "--seed SEED" "Random seed for the simulation"
    :default 1824 :parse-fn parse-long]
   [nil "--batch-every DAYS" "Simulated days between batch recomputes"
    :default 4 :parse-fn parse-long :validate [#(and % (pos? %)) "Must be positive"]]
   [nil "--data-dir DIR" "Where to write the master dataset (default: a fresh temp dir)"]
   [nil "--plain" "No colors or live redraw"]
   ["-h" "--help"]])

(defn- start-node [{:keys [transport rabbitmq-uri data-dir]}]
  (let [dir (or data-dir
                (str (Files/createTempDirectory "thebes-demo-" (make-array FileAttribute 0))))]
    {:dir dir
     :system (system/start!
              {:role              :all
               :app               nile/app
               :port              0
               :data-dir          dir
               ;; The demo drives batch runs itself; see `run*`.
               :batch-interval-ms (* 60 60 1000)
               :transport         (if (= :rabbitmq transport)
                                    {:type         :rabbitmq
                                     :uri          rabbitmq-uri
                                     :queue        (str "thebes.demo-" (System/currentTimeMillis))
                                     :auto-delete? true}
                                    {:type :memory})})}))

(defn- produce!
  "Posts one simulated hour of readings at a time, paced to `rate` hours per
  second. When driving its own node, the demo also triggers batch runs every
  `batch-every` simulated days, so the story is the same at any speed: batch
  runs absorb the speed layer during the season, then stop for the final
  stretch so the closing check merges both layers."
  [base {:keys [url rate seed batch-every]} hours progress]
  (let [gen     (sim/create seed)
        started (System/currentTimeMillis)]
    (loop [h 0 batches []]
      (if (< h hours)
        (let [readings (gen h)
              wait     (- (+ started (long (/ (* 1000 h) rate))) (System/currentTimeMillis))
              _        (when (pos? wait) (Thread/sleep wait))
              _        (POST base "/api/v1/events" readings)
              _        (swap! progress #(-> % (update :sent + (count readings)) (assoc :hour (inc h))))
              batch?   (and (not url)
                            (pos? h)
                            (zero? (mod h (* 24 batch-every)))
                            (< h (* 0.85 hours)))]
          (recur (inc h)
                 (cond-> batches batch? (conj (future (POST base "/api/v1/batch" nil))))))
        (run! deref batches)))))

(defn- report-progress
  "Redraws the dashboard (live) or prints a status line every couple of
  seconds (plain) until the season is over."
  [base live? progress]
  (loop [i 0]
    (let [{:keys [hour hours sent] :as p} @progress]
      (when (< hour hours)
        (cond
          live?
          (print (str "\u001b[H\u001b[J" (render (snapshot base) p)))

          (zero? (mod i 8))
          (let [{:keys [batch speed-events]} (GET base "/api/v1/status")]
            (println (format "  day %2d/%d  sent %,7d  batch %,7d  speed %,6d"
                             (inc (quot (min hour (dec hours)) 24)) (quot hours 24)
                             sent (:watermark batch) speed-events))))
        (flush)
        (Thread/sleep 250)
        (recur (inc i))))))

(defn- summarize!
  "Prints the final dashboard and the closing checks. The consistency check
  compares the realtime answer (batch views merged with the speed layer)
  against a fresh recompute of everything from the master dataset."
  [base node live? {:keys [sent] :as p} elapsed-s]
  (wait-until-processed base sent)
  (let [before (mapv #(GET base (str "/api/v1/views/" %)) ["stations" "daily"])
        _      (print (str (when live? "\u001b[H\u001b[J") (render (snapshot base) p)))
        batch  (POST base "/api/v1/batch" nil)
        after  (mapv #(GET base (str "/api/v1/views/" %)) ["stations" "daily"])
        same?  (approx= (map :rows before) (map :rows after))
        alerts (:items (GET base "/api/v1/streams/flood-alerts"))
        status (GET base "/api/v1/status")
        check  (fn [ok? msg] (println (str "  " (if ok? (green "✓") (red "✗")) " " msg)))]
    (println)
    (check true (format "%s readings ingested over HTTP in %.1fs (%s/s)"
                        (fmt-int sent) elapsed-s (fmt-int (/ sent elapsed-s))))
    (when node
      (let [f (java.io.File. ^String (:dir node) "events.jsonl")]
        (check (= sent (:log-size status))
               (format "master dataset: %s (%s events, %.1f MB)"
                       (.getPath f) (fmt-int (:log-size status)) (/ (.length f) 1e6)))))
    (check true (format "batch layer ran %d times; full recompute of %s events took %d ms"
                        (get-in status [:batch :runs]) (fmt-int (:watermark batch)) (:duration-ms batch)))
    (check (seq alerts) (format "%d flood alerts from the speed layer's stateful stream" (count alerts)))
    (check same? (format "realtime view (batch ⊕ speed: %s + %s events) == full batch recompute"
                         (fmt-int (:watermark (first before))) (fmt-int (:speed-events (first before)))))
    (println)
    (println (dim (str "  Next: run a node with `clojure -M:run`, POST readings to /api/v1/events,\n"
                       "  and query /api/v1/views/stations?station=luxor. See README.md.")))
    same?))

(defn- run* [{:keys [url days rate transport] :as options} live?]
  (let [node     (when-not url (start-node options))
        base     (or url (str "http://localhost:" (get-in node [:system :port])))
        hours    (* 24 days)
        progress (atom {:hour 0 :hours hours :sent 0 :transport (if url :remote transport)})
        started  (System/currentTimeMillis)]
    (if live?
      (print "\u001b[2J\u001b[?25l")
      (println (str "Thebes demo: " days "-day flood season, " rate " simulated hours/second → " base)))
    (try
      (let [ui (future (report-progress base live? progress))]
        (try
          (produce! base options hours progress)
          @ui
          (finally (future-cancel ui))))
      (when-not (summarize! base node live? @progress (/ (- (System/currentTimeMillis) started) 1000.0))
        (throw (ex-info "Consistency check failed" {})))
      (finally
        (when live? (print "\u001b[?25h") (flush))
        (when node (system/stop! (:system node)))))))

(defn run [{:keys [plain] :as options}]
  (let [live? (and (not plain) (tty?) (nil? (System/getenv "NO_COLOR")))]
    (binding [*color* live?]
      (run* options live?))))

(defn -main [& args]
  (let [{:keys [options errors summary]} (cli/parse-opts args cli-options)]
    (cond
      (:help options) (println (str "Usage: clojure -M:demo [options]\n\n" summary))
      errors          (do (println (str/join \newline errors)) (System/exit 1))
      :else           (try
                        (run options)
                        (catch Exception e
                          (binding [*out* *err*]
                            (println "Demo failed:" (or (ex-message e) (str e)))
                            (when-let [cause (ex-cause e)]
                              (println "  caused by:" (or (ex-message cause) (str cause)))))
                          (System/exit 1))))
    (shutdown-agents)))
