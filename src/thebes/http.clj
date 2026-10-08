(ns thebes.http
  "The HTTP API. Which routes are mounted depends on the node's role:
  ingest nodes accept events, processor nodes serve views and streams, and
  an all-in-one node does both."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [org.httpkit.server :as hk]
            [thebes.ingest :as ingest]
            [thebes.processor :as processor]
            [thebes.stream :as stream]))

(defn- json-response
  ([body] (json-response 200 body))
  ([status body]
   {:status  status
    :headers {"Content-Type" "application/json"}
    :body    (json/generate-string body)}))

(defn- error [status message & {:as extra}]
  (json-response status (merge {:error message} extra)))

(defn- query-params [{:keys [query-string]}]
  (if (str/blank? query-string)
    {}
    (into {}
          (for [pair (str/split query-string #"&")
                :let [[k v] (str/split pair #"=" 2)]]
            [(keyword (java.net.URLDecoder/decode ^String k "UTF-8"))
             (java.net.URLDecoder/decode ^String (or v "") "UTF-8")]))))

(defn- parse-body [{:keys [body]}]
  (when body
    (let [text (slurp body)]
      (when-not (str/blank? text)
        (json/parse-string text true)))))

;; Handlers -----------------------------------------------------------------
;; Each handler takes the node context, the request, and the path params.

(defn- index [{:keys [role app]} _ _]
  (json-response
   {:name      "thebes"
    :app       (:name app)
    :role      role
    :endpoints (cond-> ["GET  /health"]
                 (#{:all :ingest} role)
                 (conj "POST /api/v1/events")
                 (#{:all :processor} role)
                 (into ["GET  /api/v1/status"
                        "GET  /api/v1/views"
                        "GET  /api/v1/views/:name[?part=value]"
                        "POST /api/v1/batch"
                        "GET  /api/v1/streams/:name[?since=seq]"
                        "GET  /api/v1/streams/:name/sse"]))}))

(defn- health [_ _ _]
  (json-response {:status "ok"}))

(defn- post-events [{:keys [app transport]} req _]
  (let [parsed (try {:ok (parse-body req)}
                    (catch Exception e {:error (.getMessage e)}))]
    (cond
      (:error parsed)     (error 400 "Malformed JSON" :detail (:error parsed))
      (nil? (:ok parsed)) (error 400 "Request body must be a JSON object or array of objects")
      :else
      (let [body   (:ok parsed)
            events (if (sequential? body) body [body])
            result (ingest/accept! app transport events)]
        (if (:errors result)
          (json-response 422 {:error "Invalid events" :problems (:errors result)})
          (json-response 202 result))))))

(defn- status [{:keys [processor]} _ _]
  (json-response (processor/status processor)))

(defn- run-batch [{:keys [processor]} _ _]
  (json-response (processor/run-batch! processor)))

(defn- list-views [{:keys [processor]} _ _]
  (json-response
   {:views (for [v (get-in processor [:app :views])]
             {:name     (:name v)
              :doc      (:doc v)
              :group-by (keys (:group-by v))})}))

(defn- matches-filters?
  "True when every filter's key part, as a string, equals its value."
  [filters {:keys [key]}]
  (every? (fn [[k v]] (= v (str (get key k)))) filters))

(defn- get-view [{:keys [processor]} req {view-name :name}]
  (if-let [result (processor/query processor view-name)]
    ;; Query parameters naming a key part filter rows; others are ignored.
    (let [parts   (set (keys (:group-by (processor/find-view processor view-name))))
          filters (select-keys (query-params req) parts)]
      (json-response (update result :rows #(filterv (partial matches-filters? filters) %))))
    (error 404 (str "No such view: " view-name))))

(defn- find-stream [{:keys [processor]} stream-name]
  (get-in processor [:streams (keyword stream-name)]))

(defn- get-stream [ctx req {stream-name :name}]
  (if-let [s (find-stream ctx stream-name)]
    (let [since (or (parse-long (get (query-params req) :since "0")) 0)]
      (json-response {:stream stream-name :items (stream/since s since)}))
    (error 404 (str "No such stream: " stream-name))))

(defn- sse-stream
  "Streams new items as Server-Sent Events: `curl -N .../sse`."
  [ctx req {stream-name :name}]
  (if-let [s (find-stream ctx stream-name)]
    (hk/as-channel req
                   {:on-open (fn [ch]
                               (hk/send! ch {:status  200
                                             :headers {"Content-Type"  "text/event-stream"
                                                       "Cache-Control" "no-cache"}}
                                         false)
                               (let [unsub (stream/subscribe!
                                            s #(hk/send! ch (str "data: " (json/generate-string %) "\n\n") false))]
                                 (hk/on-close ch (fn [_] (unsub)))))})
    (error 404 (str "No such stream: " stream-name))))

;; Routing ------------------------------------------------------------------

(def ^:private routes
  "[method path-pattern role handler]; keywords in a pattern capture params."
  [[:get  []                                 :any       index]
   [:get  ["health"]                         :any       health]
   [:post ["api" "v1" "events"]              :ingest    post-events]
   [:get  ["api" "v1" "status"]              :processor status]
   [:post ["api" "v1" "batch"]               :processor run-batch]
   [:get  ["api" "v1" "views"]               :processor list-views]
   [:get  ["api" "v1" "views" :name]         :processor get-view]
   [:get  ["api" "v1" "streams" :name]       :processor get-stream]
   [:get  ["api" "v1" "streams" :name "sse"] :processor sse-stream]])

(defn- match-path [pattern segments]
  (when (= (count pattern) (count segments))
    (reduce (fn [params [p s]]
              (cond (keyword? p) (assoc params p s)
                    (= p s)      params
                    :else        (reduced nil)))
            {}
            (map vector pattern segments))))

(defn- serves? [node-role route-role]
  (or (= :any route-role) (= :all node-role) (= node-role route-role)))

(defn- route [{:keys [role] :as ctx} {:keys [request-method uri] :as req}]
  (let [segments (vec (remove str/blank? (str/split uri #"/")))
        matches  (keep (fn [[method pattern route-role f]]
                         (when (serves? role route-role)
                           (when-let [params (match-path pattern segments)]
                             [method f params])))
                       routes)]
    (if-let [[_ f params] (first (filter #(= request-method (first %)) matches))]
      (f ctx req params)
      (if (seq matches)
        (error 405 "Method not allowed")
        (error 404 "Not found")))))

(defn handler
  "Builds the Ring handler. `ctx` holds `:role`, `:app`, and `:transport`
  and/or `:processor` as the role requires."
  [ctx]
  (fn [req]
    (try
      (route ctx req)
      (catch Throwable t
        (binding [*out* *err*]
          (println "thebes.http: request failed:" (.getMessage t)))
        (error 500 "Internal error")))))

(defn start!
  "Starts an HTTP server; port 0 picks a free port. Returns the server."
  [ctx {:keys [port] :or {port 8080}}]
  (hk/run-server (handler ctx) {:port port :legacy-return-value? false}))

(defn port [server] (hk/server-port server))

(defn stop! [server]
  @(hk/server-stop! server))
