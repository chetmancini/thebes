(ns thebes.cascalog.math
  (:import [org.apache.commons.math.util MathUtils]))

(defn dot-product [x y]
  (reduce + (map * x y)))

(defn greater-than-zero? [x]
  (> x 0))

(defn round [x precision]
  (MathUtils/round (cast Double x) precision))

(defn safe-div [numerator denominator]
  (try
    (/ numerator denominator)
    (catch ArithmeticException e denominator)))