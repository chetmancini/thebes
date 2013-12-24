(ns thebes.api.output
  (:gen-class)
  (:require [langohr.core      :as rmq]
            [langohr.channel   :as lch]
            [langohr.queue     :as lq]
            [langohr.consumers :as lc]
            [langohr.basic     :as lb]
            [clj-time.core :as time]
         	[clj-time.coerce :as tc])
  (:use [thebes.config]))

(let [conn  (rmq/connect {:host queue-host
						  :port (Integer/parseInt queue-port)
						  :username queue-username
						  :password queue-password
						  :vhost queue-vhost})
      ch    (lch/open conn)]
	(println (format "[main] Connected. Channel id: %d" (.getChannelNumber ch))))

(defn publish-data [data]
	(lb/publish ch queue-exchange-name queue-name data 
		:content-type "application/json" 
		:type "thebes.api.post"
		:app-id "thebes-api"
		:timestamp (tc/to-long (time/now))))

(defn close-channel [ch]
	(println "Channel closed")
	(rmq/close ch))

(defn close-connection [ch]
	(println "Connection closed")
	(rmq/close conn))
