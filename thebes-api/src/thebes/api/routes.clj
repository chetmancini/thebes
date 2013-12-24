(ns thebes.api.routes
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [liberator.dev :as dev])
  (:use [liberator.core :only [defresource request-method-in]]
        [liberator.representation :only [Representation]]
        [compojure.core :only [context ANY routes defroutes]]
        [clojure.string :only [split]]))

(defn put-data [input]
  (println input))

(defresource hello-world
  :handle-ok "Hello World!"
  :etag "fixed-etag"
  :available-media-types ["text/plain"])

(defresource data-input
  :allowed-methods [:post :get]
  :available-media-types ["application/json"]
  :handle-ok (fn [ctx]
              (str "<html>Post application/json to this resource.</html>"))
  :post! (fn [ctx]
           (dosync 
            (let [body (slurp (get-in ctx [:request :body]))]
              (put-data body)))))

(defn assemble-routes []
  (->
   (routes
    (ANY "/api/v1/data/in" [] data-input)
    (ANY "/hello-world" [] hello-world))
   (dev/wrap-trace :ui :header)))