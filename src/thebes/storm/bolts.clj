(ns thebes.storm.bolts
  (:require [backtype.storm [clojure :refer [emit-bolt! defbolt ack! bolt]]]))

(defbolt stormy-bolt ["stormy"] [{type :type :as tuple} collector]
  (emit-bolt! collector [(case type
                           :regular "I'm regular Stormy!"
                           :bizarro "I'm bizarro Stormy!"
                           "I have no idea what I'm doing.")]
              :anchor tuple)
  (ack! collector tuple))

(defbolt thebes-bolt ["message"] [{stormy :stormy :as tuple} collector]
  (emit-bolt! collector [(str "thebes produced: "stormy)] :anchor tuple)
  (ack! collector tuple))