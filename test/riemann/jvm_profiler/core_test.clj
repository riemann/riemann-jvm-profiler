(ns riemann.jvm-profiler.core-test
  (:require [riemann.jvm-profiler :as profiler])
  (:use [clojure.test]))

(deftest format-prefix-test
  (testing "format-prefix"
    (is (= (profiler/format-prefix "") "")
    (is (= (profiler/format-prefix "myapp") "myapp ")))))
