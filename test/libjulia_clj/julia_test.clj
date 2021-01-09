(ns libjulia-clj.julia-test
  (:require [libjulia-clj.julia :refer [jl] :as jl]
            [libjulia-clj.impl.base :as jl-base]
            [tech.v3.datatype :as dtype]
            [tech.v3.tensor :as dtt]
            [clojure.test :refer [deftest is]]))

;;init only once

(defonce init* (delay (jl/initialize!)))

@init*

(jl/set-julia-gc-root-log-level! :info)


(deftest julia-test
  (let [ones-fn (jl "Base.ones")
        jl-ary (ones-fn 3 4)
        tens-data (dtt/as-tensor jl-ary)]
    (dtt/mset! tens-data 0 25)
    ;;Make sure the both gc's are playing nice
    ;;with each other.
    (System/gc)
    (jl/cycle-gc!)
    ;;datatype is transpose of julia
   (is (= [4 3] (dtype/shape jl-ary)))
   (is (= [[25.0 25.0 25.0]
           [1.0 1.0 1.0]
           [1.0 1.0 1.0]
           [1.0 1.0 1.0]]
           (mapv vec (dtt/as-tensor jl-ary)))))
  (System/gc)
  (jl/cycle-gc!))


(deftest kw-manual-args-test
  (jl/with-stack-context
    (let [add-fn (jl "function teste(a;c = 1.0, b = 2.0)
    a+b+c
end")
          kwfunc (jl "Core.kwfunc")
          add-kwf (kwfunc add-fn)]
      (is (= 38.0 (add-kwf (jl/named-tuple {'b 10 'c 20})
                           add-fn
                           8.0)))
      (is (= 19.0 (add-kwf (jl/named-tuple {'b 10})
                           add-fn
                           8.0)))
      (is (= 11.0 (add-kwf (jl/named-tuple)
                           add-fn
                           8.0)))

      (is (= 38.0 (add-fn 8.0 :b 10 :c 20)))
      (is (= 19.0 (add-fn 8 :b 10)))
      (is (= 11.0 (add-fn 8))))))


(deftest stack-context
  (jl/with-stack-context
    (let [jl-data (jl/new-array [2 2] :float32)
          size (jl "size")]
      (= [2 2] (vec (size jl-data)))))
  (jl/with-stack-context
    (let [tdata (dtt/->tensor (partition 3 (range 9)) :datatype :int32)
          jl-ary (jl/->array tdata)
          ary-data (dtype/make-container :int32 jl-ary)]
      (is (= (vec (range 9))
             (vec ary-data)))))
  (jl/with-stack-context
    (let [tdata (dtt/->tensor (partition 3 (range 9)) :datatype :int32)
          jl-ary (jl/->array tdata)
          id-fn (jl "identity")
          ;;This one should not get rooted
          jl-ary2 (id-fn jl-ary)]
      (is (= (vec (range 9))
             (vec (dtype/make-container :int64 jl-ary2)))))))
