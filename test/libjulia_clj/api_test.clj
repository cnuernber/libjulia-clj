(ns libjulia-clj.api-test
  (:require [libjulia-clj.java-api :as japi]
            [tech.v3.datatype.jvm-map :as jvm-map]
            [clojure.test :refer [deftest is]]))


(deftest java-api-test
  (japi/-initialize (jvm-map/hash-map {"n-threads" 8}))
  (is (= 4 (japi/-runString "2 + 2")))
  (let [tuple (japi/-namedTuple {"a" 1 "b" 2})
        getindex (japi/-runString "getindex")]
    (is (instance? clojure.lang.IFn getindex))
    (is (= 1 (getindex tuple 1)))
    (is (= 2 (getindex tuple 2))))
  (let [ary (japi/-createJlArray "int32" [2 3] (range 6))]
    (japi/-arrayToJVM ary)))
