(ns thebes.api.parse
  (:require [clojure.java.io :as io]
            [cheshire.core :refer :all])
  (:use [liberator.core :only [defresource request-method-in]]
        [compojure.core :only [context ANY routes defroutes]]
        [clojure.string :only [split]]))

(defn body-as-string [ctx]
  (if-let [body (get-in ctx [:request :body])]
    (condp instance? body
      java.lang.String body
      (slurp (io/reader body)))))

(defn parse-json [context key]
  "For PUT and POST parse the body as json and store in the context
  under the given key."
  (when (#{:put :post} (get-in context [:request :request-method]))
    (try
      (if-let [body (body-as-string context)]
        (let [data (parse-string body)]
          [false {key data}])
        {:message "No body"})
      (catch Exception e
        (.printStackTrace e)
        {:message (format "IOException: " (.getMessage e))}))))

(defn check-content-type [ctx content-types]
  "For PUT and POST check if the content type is json."
  (if (#{:put :post} (get-in ctx [:request :request-method]))
    (or
     (some #{(get-in ctx [:request :headers "content-type"])}
           content-types)
     [false {:message "Unsupported Content-Type"}])
    true))