(ns thebes.transport.memory
  "An in-process transport backed by a bounded core.async channel."
  (:require [clojure.core.async :as a]
            [thebes.transport :as transport]))

(defrecord MemoryTransport [ch]
  transport/Transport
  (publish! [_ events]
    (doseq [event events]
      (when-not (a/>!! ch event)
        (throw (ex-info "Transport is closed" {})))))

  (subscribe! [_ handler]
    (let [worker (a/thread
                   (loop []
                     (when-some [event (a/<!! ch)]
                       (try
                         (handler event)
                         (catch Throwable t
                           (binding [*out* *err*]
                             (println "thebes.transport.memory: handler failed:" (.getMessage t)))))
                       (recur))))]
      ;; Closing stops new publishes but lets the worker drain events that
      ;; were already accepted, so a clean shutdown loses nothing.
      (fn stop []
        (a/close! ch)
        (a/<!! worker))))

  (close! [_]
    (a/close! ch)))

(defn create [{:keys [buffer] :or {buffer 4096}}]
  (->MemoryTransport (a/chan buffer)))
