(ns riemann.jvm-profiler.core-test
  (:require [clojure.test :as test]
            [riemann.jvm-profiler :refer :all]))

(test/deftest format-prefix-test
  (test/testing "format-prefix"
    (test/is (= (format-prefix "") "")
    (test/is (= (format-prefix "myapp") "myapp ")))))
