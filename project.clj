(defproject riemann-jvm-profiler "0.1.1-SNAPSHOT"
  :description "Distributed JVM profiling for Riemann."
  :url "http://github.com/riemann/riemann-jvm-profiler"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/tools.logging "1.2.4"]
                 [cheshire "5.11.0"]
                 [interval-metrics "1.0.1"]
                 [clj-radix "0.1.0"]
                 [http-kit "2.6.0"]
                 [clj-time "0.15.2"]
                 [javax.xml.bind/jaxb-api "2.4.0-b180830.0359"]]
  :plugins [[test2junit "1.3.3"]]
  :test2junit-output-dir "target/test2junit"
  :java-source-paths ["src/riemann"]
  :manifest {"premain-Class" "riemann.jvm_profiler.Agent"})
