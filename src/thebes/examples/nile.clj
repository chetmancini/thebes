(ns thebes.examples.nile
  "Example app: a network of nilometers.

  For millennia, Egyptians measured the Nile's annual flood with nilometers,
  and a good flood meant a good harvest. Here, gauge stations from Khartoum
  to Cairo report river level readings, which Thebes turns into per-station
  statistics, a daily river-wide series, and a realtime stream of flood
  alerts.

  Events look like:

    {\"station\": \"luxor\", \"sensor\": \"luxor-2\",
     \"ts\": 1784505600000, \"level\": 6.42, \"flow\": 5120.0}"
  (:require [thebes.agg :as agg]
            [thebes.view :as view]))

(def stations
  "Gauge stations from upstream to downstream. `:km` is the distance from
  Khartoum along the river, levels are in meters above the gauge's datum.
  The figures are illustrative, not survey data."
  [{:id "khartoum" :name "Khartoum"       :km 0    :base 4.0 :flood-stage 9.5}
   {:id "atbara"   :name "Atbara"         :km 320  :base 3.6 :flood-stage 8.6}
   {:id "aswan"    :name "Aswan"          :km 1550 :base 3.0 :flood-stage 7.0}
   {:id "luxor"    :name "Luxor (Thebes)" :km 1760 :base 2.8 :flood-stage 6.5}
   {:id "asyut"    :name "Asyut"          :km 2100 :base 2.5 :flood-stage 5.9}
   {:id "cairo"    :name "Cairo"          :km 2500 :base 2.2 :flood-stage 5.2}])

(def station-by-id (into {} (map (juxt :id identity)) stations))

(def ^:private recede-margin
  "Hysteresis: a flooded station must fall this far below flood stage before
  it counts as receded, so sensor noise around the threshold doesn't flap."
  0.4)

(defn validate
  "Returns an error message for a malformed reading, or nil."
  [{:keys [station level ts]}]
  (cond
    (not (string? station))           "station must be a string"
    (not (station-by-id station))     (str "unknown station: " station)
    (not (number? level))             "level must be a number"
    (not (integer? ts))               "ts must be epoch milliseconds"))

(defn prepare
  "Derives fields both layers rely on. Because the batch and speed layers
  share this function, derived values can never disagree between them."
  [{:keys [station level] :as event}]
  (let [stage (:flood-stage (station-by-id station))]
    (assoc event
           :flood-stage stage
           :flooding? (boolean (and stage (number? level) (> level stage))))))

(def views
  [{:name     :stations
    :doc      "Per-station level statistics, current level, and flood hours"
    :group-by {:station :station}
    :agg      (agg/multi
               {:readings      (agg/count)
                :level         (agg/stats :level)
                :current       (agg/latest :level :ts)
                :flow          (agg/mean :flow)
                :flood-readings (agg/filtered :flooding? (agg/count))
                :sensors       (agg/distinct-approx :sensor)})}

   {:name     :daily
    :doc      "Daily peak level and number of sensors reporting, per station"
    :group-by {:station :station
               :day     (view/time-bucket :ts :day)}
    :agg      (agg/multi
               {:readings (agg/count)
                :peak     (agg/max :level)
                :sensors  (agg/distinct-approx :sensor)})}])

(defn flood-detector
  "A stateful transducer over readings that emits an alert when a station
  rises above flood stage and when it recedes again. State is per station,
  which is why this runs as a single-threaded stream, in log order."
  []
  (fn [rf]
    (let [flooded (volatile! #{})]
      (fn
        ([] (rf))
        ([acc] (rf acc))
        ([acc {:keys [station level ts flood-stage] :as reading}]
         (let [was? (contains? @flooded station)
               alert (fn [type]
                       {:type        type
                        :station     station
                        :name        (:name (station-by-id station))
                        :level       level
                        :flood-stage flood-stage
                        :ts          ts})]
           (cond
             (and (not was?) (:flooding? reading))
             (do (vswap! flooded conj station) (rf acc (alert "flood")))

             (and was? (< level (- flood-stage recede-margin)))
             (do (vswap! flooded disj station) (rf acc (alert "receded")))

             :else acc)))))))

(def app
  {:name     "nile"
   :validate validate
   :prepare  prepare
   :views    views
   :streams  {:flood-alerts (flood-detector)}})
