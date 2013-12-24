(defproject thebes "0.1.0-SNAPSHOT"
  :description "A lambda architecture for realtime data processing"
  :url "http://github.com/chetmancini/thebes"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :repositories {"conjars" "http://conjars.org/repo"}
  :jvm-opts ["-Djava.library.path=/usr/local/lib:/opt/local/lib:/usr/lib"]
  :javac-options {:debug "true" :fork "true"}
  :java-source-path "src/jvm"
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [cascalog/cascalog-core "2.0.0"]
                 [storm "0.9.0.1"]]
  :aot [thebes.TopologySubmitter]
  :main ^:skip-aot thebes.core
  :target-path "target/%s"
  :profiles {:dev {:dependencies [[org.apache.hadoop/hadoop-core "1.1.2"]
                                  [lein-midje "3.0.1"]
                                  [cascalog/midje-cascalog "2.0.0"]
                                  [midje "1.6.0"]]}
             :uberjar {:aot :all}})
