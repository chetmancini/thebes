(ns thebes.api.routes
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [liberator.dev :as dev])
  (:use [liberator.core :only [defresource request-method-in]]
        [liberator.representation :only [Representation]]
        [compojure.core :only [context ANY routes defroutes]]
        [clojure.string :only [split]]))

(defresource hello-world
  :handle-ok "Hello World!"
  :etag "fixed-etag"
  :available-media-types ["text/plain"])

(defn assemble-routes []
  (->
   (routes
    (ANY "/hello-world" [] hello-world))
   (dev/wrap-trace :ui :header)))