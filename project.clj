(defproject thebes "0.1.0-SNAPSHOT"
  :description "A lambda architecture for realtime data processing"
  :url "http://github.com/chetmancini/thebes"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :repositories {"conjars" "http://conjars.org/repo"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [cascalog/cascalog-core "2.0.0"]]
  :main ^:skip-aot thebes.core
  :target-path "target/%s"
  :profiles {:dev {:dependencies [[org.apache.hadoop/hadoop-core "1.1.2"]]}
             :uberjar {:aot :all}})
