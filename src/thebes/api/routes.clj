(ns thebes.api.routes
  (:require [liberator.dev :as dev]))

(defresource hello-world
  :handle-ok "Hello World!"
  :etag "fixed-etag"
  :available-media-types ["text/plain"])

(defresource index 
  :available-media-types ["text/html"]
  :handle-ok (fn [context]
               (html5 [:head [:title "API routes"]] 
                      [:body
                       [:h1 "API routes"]
                       [:ul
                        [:li [:a {:href "/hello-world"} "Hello World"]]
                        [:li [:a {:href "/x-liberator/requests/"} "Liberator request dump"]]]]))) 

(defn assemble-routes []
  (->
   (routes
    (ANY "/" [] index)
    (ANY "/hello-world" [] hello-world))
   (dev/wrap-trace :ui :header)))