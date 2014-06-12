(defproject riemann-jvm-profiler "0.1.0-SNAPSHOT"
  :description "Distributed JVM profiling for Riemann."
  :url "http://github.com/riemann/riemann-jvm-profiler"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/tools.logging "0.2.6"]
                 [cheshire "5.3.1"]
                 [interval-metrics "1.0.0"]
                 [clj-radix "0.1.0-SNAPSHOT"]
                 [http-kit "2.1.16"]
                 [clj-time "0.7.0"]]
  :java-source-paths ["src/riemann"]
  :manifest {"premain-Class" "riemann.jvm_profiler.Agent"})
