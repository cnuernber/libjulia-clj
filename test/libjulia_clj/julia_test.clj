(ns libjulia-clj.julia-test
  (:require [libjulia-clj.julia :as julia]
            [tech.v3.datatype :as dtype]
            [tech.v3.tensor :as dtt]
            [clojure.test :refer [deftest is]]))


(julia/initialize!)


(deftest julia-test
  (let [ones-fn (julia/eval-string "Base.ones")
        jl-ary (ones-fn 3 4)
        tens-data (dtt/as-tensor jl-ary)]
    (dtt/mset! tens-data 0 25)
    ;;Make sure the both gc's are playing nice
    ;;with each other.
    (System/gc)
    (julia/cycle-gc!)
    (is (= [3 4] (dtype/shape jl-ary)))
    (is (= [[25.0 25.0 25.0 25.0]
            [1.0 1.0 1.0 1.0]
            [1.0 1.0 1.0 1.0]]
           (mapv vec (dtt/as-tensor jl-ary)))))
  (System/gc)
  (julia/cycle-gc!))
