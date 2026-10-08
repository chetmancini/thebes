(ns thebes.examples.nile-simulation
  "Synthetic nilometer readings for the demo.

  A flood wave leaves the Ethiopian highlands, reaches Khartoum a few days
  into the season, and travels downstream at roughly 160 km a day, losing a
  little height as it goes. Each station has four sensors with their own
  small bias and noise, and one sensor at Atbara goes offline for a few days
  mid-season. The simulation is deterministic for a given seed."
  (:require [thebes.examples.nile :as nile])
  (:import (java.time Instant)
           (java.util Random)))

(def season-start-ms
  "Late July, when the annual flood traditionally begins to rise."
  (.toEpochMilli (Instant/parse "2026-07-25T00:00:00Z")))

(def ^:private hour-ms (* 60 60 1000))
(def sensors-per-station 4)
(def ^:private wave-start-day 5.0)
(def ^:private wave-speed-km-per-day 160.0)
(def ^:private wave-width-days 2.5)

(defn- offline? [sensor hour]
  (and (= sensor "atbara-3") (<= (* 9 24) hour (* 13 24))))

(defn true-level
  "The modeled river level at a station, in meters, `hour` hours into the
  season, before sensor noise."
  [{:keys [km base flood-stage]} hour]
  (let [day       (/ hour 24.0)
        arrival   (+ wave-start-day (/ km wave-speed-km-per-day))
        amplitude (* 1.3 (- flood-stage base))
        z         (/ (- day arrival) wave-width-days)]
    (+ base
       (* amplitude (Math/exp (* -0.5 z z)))
       (* 0.08 (Math/sin (/ (* 2 Math/PI hour) 24))))))

(defn- round2 [x] (/ (Math/round (* 100.0 (double x))) 100.0))

(defn create
  "Returns a generator: a function of the hour index that returns that hour's
  readings from every online sensor."
  [seed]
  (let [rng   (Random. seed)
        bias  (into {}
                    (for [{:keys [id]} nile/stations
                          n (range 1 (inc sensors-per-station))]
                      [(str id "-" n) (* 0.05 (.nextGaussian rng))]))]
    (fn readings [hour]
      (vec
       (for [{:keys [id] :as station} nile/stations
             n (range 1 (inc sensors-per-station))
             :let [sensor (str id "-" n)]
             :when (not (offline? sensor hour))
             :let [level (+ (true-level station hour) (bias sensor) (* 0.06 (.nextGaussian rng)))]]
         {:station id
          :sensor  sensor
          :ts      (+ season-start-ms (* hour hour-ms) (* n 60 1000))
          :level   (round2 level)
          :flow    (round2 (* 450 (Math/pow (max level 0.1) 1.6)))})))))
