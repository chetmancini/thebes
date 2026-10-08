(ns thebes.ingest
  "The ingest role: validates incoming events and publishes them to the
  transport. Ingest nodes are stateless, so with a shared transport such as
  RabbitMQ you can run as many as you like behind a load balancer."
  (:require [thebes.transport :as transport]))

(defn- normalize [event]
  (cond-> (assoc event :received-at (System/currentTimeMillis))
    (nil? (:id event)) (assoc :id (str (random-uuid)))))

(defn- problem [validate event]
  (cond
    (not (map? event)) "event must be a JSON object"
    validate (validate event)))

(defn accept!
  "Validates `events` against the app's `:validate` function, which returns
  an error message or nil. Publishes every event if all of them are valid;
  otherwise publishes nothing. Returns `{:accepted n}` or `{:errors [...]}`."
  [{:keys [validate]} transport events]
  (let [errors (keep-indexed (fn [i e]
                               (when-let [msg (problem validate e)]
                                 {:index i :error msg}))
                             events)]
    (if (seq errors)
      {:errors (vec errors)}
      (do (transport/publish! transport (mapv normalize events))
          {:accepted (count events)}))))
