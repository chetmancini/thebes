(ns thebes.api.server
  (:require
   [ring.adapter.jetty :as jetty])
  (:use
   [thebes.config]
   [thebes.api.routes :only [assemble-routes]]
   [ring.middleware.multipart-params :only [wrap-multipart-params]]
   [ring.util.response :only [header]]
   [compojure.handler :only [api]]))

(def handler
  (-> (assemble-routes)
      api
      wrap-multipart-params))

(defn start [options]
  (jetty/run-jetty #'handler (assoc options :join? false)))

(defn -main
  ([port]
     (start {:port (Integer/parseInt port)}))
  ([]
     (-main api-port)))
