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


(deftest kw-manual-args-test
  (let [add-fn (julia/eval-string "function teste(a;c = 1.0, b = 2.0)
    a+b+c
end")
        kwfunc (julia/eval-string "Core.kwfunc")
        add-kwf (kwfunc add-fn)]
    (is (= 38.0 (add-kwf (julia/named-tuple {'b 10 'c 20})
                         add-fn
                         8.0)))
    (is (= 19.0 (add-kwf (julia/named-tuple {'b 10})
                       add-fn
                       8.0)))
    (is (= 11.0 (add-kwf (julia/named-tuple)
                         add-fn
                         8.0)))

    (is (= 38.0 (add-fn 8.0 :b 10 :c 20)))
    (is (= 19.0 (add-fn 8 :b 10)))
    (is (= 11.0 (add-fn 8))))
  ;;Note that things are still rooted at this point even though let scope has closed.
  (System/gc)
  (julia/cycle-gc!))
