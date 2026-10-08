(ns thebes.test-util
  (:require [cheshire.core :as json])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn temp-dir []
  (str (Files/createTempDirectory "thebes-test-" (make-array FileAttribute 0))))

(def ^:private ^HttpClient client (HttpClient/newHttpClient))

(defn http
  "Makes a request to a local node; returns [status parsed-body]. `body` may
  be a string (sent verbatim) or data (sent as JSON)."
  ([port method path] (http port method path nil))
  ([port method path body]
   (let [publisher (cond (nil? body)    (HttpRequest$BodyPublishers/noBody)
                         (string? body) (HttpRequest$BodyPublishers/ofString body)
                         :else          (HttpRequest$BodyPublishers/ofString (json/generate-string body)))
         req  (-> (HttpRequest/newBuilder (URI. (str "http://localhost:" port path)))
                  (.header "Content-Type" "application/json")
                  (.method method publisher)
                  .build)
         resp (.send client req (HttpResponse$BodyHandlers/ofString))]
     [(.statusCode resp) (json/parse-string (.body resp) true)])))

(defn eventually
  "Polls `(f)` until it returns truthy or `timeout-ms` passes; returns the
  last result."
  ([f] (eventually f 5000))
  ([f timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (let [result (f)]
         (if (or result (> (System/currentTimeMillis) deadline))
           result
           (do (Thread/sleep 20) (recur))))))))
