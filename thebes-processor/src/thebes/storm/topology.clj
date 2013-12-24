(ns thebes.storm.topology
  (:require [thebes.storm
             [spouts :refer [type-spout]]
             [bolts :refer [stormy-bolt thebes-bolt]]]
            [backtype.storm [clojure :refer [topology spout-spec bolt-spec]] [config :refer :all]])
  (:import [backtype.storm StormSubmitter LocalCluster LocalDRPC]))

(defn stormy-topology []
  (topology
   {"spout" (spout-spec type-spout)}

   {"stormy-bolt" (bolt-spec {"spout" ["type"]} stormy-bolt :p 2)
    "thebes-bolt" (bolt-spec {"stormy-bolt" :shuffle} thebes-bolt :p 2)}))

(defn run! [& {debug "debug" workers "workers" :or {debug "true" workers "2"}}]
  (doto (LocalCluster.)
    (.submitTopology "stormy topology"
                     {TOPOLOGY-DEBUG (Boolean/parseBoolean debug)
                      TOPOLOGY-WORKERS (Integer/parseInt workers)}
                     (stormy-topology))))

(defn -main
  ([]
   (run!)))