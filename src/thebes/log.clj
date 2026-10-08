(ns thebes.log
  "The master dataset: an immutable, append-only log of raw events.

  Events are stored one JSON object per line in `<dir>/events.jsonl`, so the
  dataset is easy to inspect, grep, or load into other tools. An event's
  *offset* is its zero-based line number. The batch layer always recomputes
  from this log, which makes it the single source of truth: views can be
  thrown away and rebuilt at any time."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io])
  (:import (java.io BufferedWriter File)))

(defn- count-lines [^File f]
  (if (.exists f)
    (with-open [r (io/reader f)]
      (count (line-seq r)))
    0))

(defn- ends-mid-line?
  "True if a crash left the file ending part way through a line."
  [^File f]
  (and (.exists f)
       (pos? (.length f))
       (with-open [raf (java.io.RandomAccessFile. f "r")]
         (.seek raf (dec (.length f)))
         (not= (int \newline) (.read raf)))))

(defn open
  "Opens (creating if needed) the log in directory `dir`. A torn final line
  left by a crash is terminated, so it keeps its offset and later appends
  start on a fresh line."
  [dir]
  (let [f (io/file dir "events.jsonl")]
    (io/make-parents f)
    (when (ends-mid-line? f)
      (spit f "\n" :append true))
    {:file   f
     :writer (io/writer f :append true)
     :lock   (Object.)
     :size   (atom (count-lines f))}))

(defn close [{:keys [^BufferedWriter writer]}]
  (.close writer))

(defn size
  "Number of events in the log."
  [log]
  @(:size log))

(defn append!
  "Appends `event` and returns its offset. Each line is flushed before
  returning, so an acknowledged event survives a process crash (though not,
  without fsync, a machine crash)."
  [{:keys [^BufferedWriter writer lock size]} event]
  (locking lock
    (.write writer ^String (json/generate-string event))
    (.newLine writer)
    (.flush writer)
    (let [offset @size]
      (reset! size (inc offset))
      offset)))

(defn with-watermark
  "Calls `(f watermark)` while holding the append lock, where `watermark` is
  the current size of the log. No event can be appended while `f` runs, so
  it can atomically record where a batch run will stop reading."
  [{:keys [lock size]} f]
  (locking lock
    (f @size)))

(defn- parse-line [line]
  (try (json/parse-string line true) (catch Exception _ nil)))

(defn read-events
  "Reads the first `n` events from the log into a vector, indexed by offset.
  A line that isn't valid JSON (such as one torn by a crash) reads as nil."
  [{:keys [file]} n]
  (with-open [r (io/reader file)]
    (into [] (comp (take n) (map parse-line)) (line-seq r))))
