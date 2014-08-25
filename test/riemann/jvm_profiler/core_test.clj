(ns riemann.jvm-profiler.core-test
  (:use [clojure.test :exclude (report)]
        [riemann.jvm-profiler]))

(deftest format-prefix-test
  (testing "format-prefix"
    (is (= (format-prefix "") "")
    (is (= (format-prefix "myapp") "myapp ")))))
