(ns libjulia-clj.julia-test
  (:require [libjulia-clj.julia :refer [jl] :as jl]
            [libjulia-clj.impl.base :as jl-base]
            [tech.v3.datatype :as dtype]
            [tech.v3.tensor :as dtt]
            [clojure.test :refer [deftest is]]))

;;init only once

(defonce init* (delay (jl/initialize!)))

@init*


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
    (is (= 11.0 (add-fn 8))))
  ;;Note that things are still rooted at this point even though let scope has closed.
  (System/gc)
  (jl/cycle-gc!))


(defn make-task-wrapper
  []
  (let [ary-list (java.util.ArrayList.)
        raw-clj-fn (jl-base/fn->jl (fn [data] (.add ary-list data)))
        [before,after,wrapper] (jl "before = Any[]; after = Any[];
(before,after,
function wrapper(fn_ptr)
  function cback(args...)
    push!(before, args)
    ccall(fn_ptr, Any, (Any,), args)
    push!(after, args)
  end
end)")
        cback (wrapper raw-clj-fn)]
    {:ary-list ary-list
     :raw-fn raw-clj-fn
     :before before
     :after after
     :wrapper wrapper
     :cback cback}))


(defn callback-from-task
  []
  (let [doasync (jl "function doasync(cback, arg) @async cback(arg) end")
        {:keys [ary-list raw-clj-fn before after wrapper]} (make-task-wrapper)]
    [before,after,wrapper]))
