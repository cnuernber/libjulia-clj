(ns libjulia-clj.julia
  "Public API for Julia functionality.  Initialize! must be called before any other functions
  and cycle-gc! should be called periodically.  It is probably a bad idea to call Julia
  from multiple threads so access to julia should be from one thread or protected via
  a mutex.

Example:

```clojure
user> (require '[libjulia-clj.julia :as julia])
nil
user> (julia/initialize!)
:ok
user> (def ones-fn (julia/jl \"Base.ones\"))
Nov 27, 2020 12:39:39 PM clojure.tools.logging$eval6611$fn__6614 invoke
INFO: Rooting address  0x00007F3E092D6E40
#'user/ones-fn
user> (def jl-ary (ones-fn 3 4))

Nov 27, 2020 12:40:02 PM clojure.tools.logging$eval6611$fn__6614 invoke
INFO: Rooting address  0x00007F3DFF9C92A0
#'user/jl-ary
user> jl-ary
[1.0 1.0 1.0 1.0; 1.0 1.0 1.0 1.0; 1.0 1.0 1.0 1.0]
user> (type jl-ary)
libjulia_clj.impl.base.JuliaArray
user> (require '[tech.v3.tensor :as dtt])
nil
user> ;;zero-copy
user> (def tens (dtt/as-tensor jl-ary))
#'user/tens
user> tens
#tech.v3.tensor<float64>[3 4]
[[1.000 1.000 1.000 1.000]
 [1.000 1.000 1.000 1.000]
 [1.000 1.000 1.000 1.000]]
user> (dtt/mset! tens 0 25)
#tech.v3.tensor<float64>[3 4]
[[25.00 25.00 25.00 25.00]
 [1.000 1.000 1.000 1.000]
 [1.000 1.000 1.000 1.000]]
user> jl-ary
[25.0 25.0 25.0 25.0; 1.0 1.0 1.0 1.0; 1.0 1.0 1.0 1.0]
```"
  (:require [libjulia-clj.impl.base :as base]
            [libjulia-clj.impl.protocols :as julia-proto]
            [libjulia-clj.impl.jna :as julia-jna]
            [libjulia-clj.impl.gc :as julia-gc]
            ;;pure language extensions for now.
            [libjulia-clj.impl.collections]
            [tech.v3.datatype :as dtype]
            [tech.v3.tensor :as dtt]
            [tech.v3.datatype.export-symbols :refer [export-symbols]]
            [tech.v3.datatype.errors :as errors])
  (:refer-clojure :exclude [struct]))


(export-symbols libjulia-clj.impl.base
                initialize!
                lookup-julia-type
                apply-tuple-type
                apply-type
                struct
                tuple
                named-tuple
                call-function
                call-function-kw)


(defn jl
  "Eval a string in julia returning the result.  If the result is callable in Julia,
  the result will be callable in Clojure.  Currently one major limit is that you
  cannot pass a clojure IFn as a Julia callback."
  ([str-data options]
   (let [retval (julia-jna/jl_eval_string str-data)]
     (base/check-last-error)
     (julia-proto/julia->jvm retval nil)))
  ([str-data]
   (jl str-data nil)))


(defn typeof
  "Get the julia type of an item."
  [item]
  (when item
    (let [retval (julia-jna/jl_typeof item)]
      (base/check-last-error)
      (julia-proto/julia->jvm retval nil))))


(defn ^{:doc (:doc (meta #'base/apply-tuple-type))}
  apply-tuple-type
  [& args]
  (base/apply-tuple-type args))


(defn ^{:doc (:doc (meta #'base/apply-type))} apply-type
  [jl-type & args]
  (base/apply-type jl-type args))


(defn ^{:doc (:doc (meta #'base/struct))} struct
  [struct-type & args]
  (base/struct struct-type args))


(defn ^{:doc (:doc (meta #'base/tuple))} tuple
  [& args]
  (base/tuple args))


(defn cycle-gc!
  "Call periodically to release rooted Julia objects.  We root return values if they
  aren't primitives and then hook them to the jvm GC such that they get put in a queue
  once they aren't reachable by the program.  This call clears that queue and unroots
  them thus notifying the Julia GC that they aren't reachable any more.

  In the future point this may be done for you."
  []
  (julia-gc/clear-reference-queue))


;; This requires us to do our own reference counting so as to only release an
;; object once.  I am not ready to implement that yet.
;; (defmacro with-stack-context
;;   "Run code in which all objects created within this context will be released once
;;   the stack unwinds where released means unrooted and thus potentially available to
;;   the next julia garbage collection run."
;;   [& body]
;;   `(julia-gc/with-stack-context
;;      ~@body))

(defonce ^{:doc "Resolves to the base julia array datatype"}
  base-ary-type* (delay (jl "Base.Array")))

(defonce ^{:doc "Resolves to the julia undef type"}
  jl-undef* (delay (jl "Base.undef")))


(defn new-array
  "Create a new, uninitialized dense julia array.  Because Julia is column major
  while tech.v3.datatype is row major, the returned array's size will be the
  reverse of dtype/shape as that keeps the same in memory alignment of data."
  ([shape datatype]
   (let [jl-dtype (base/lookup-julia-type datatype)
         ary-type (apply-type @base-ary-type* jl-dtype)]
     (apply ary-type @jl-undef* shape)))
  ([shape]
   (new-array shape :float64)))


(defn ->array
  "Create a new dense julia array that is the 'transpose' of the input tensor.
  Transposing ensures the memory alignment matches and as Julia is column-major
  while datatype is row-major."
  [tens]
  (let [dtype (dtype/elemwise-datatype tens)
        tens-shape (dtype/shape tens)
        retval (new-array (reverse tens-shape) dtype)]
    (dtype/copy! tens retval)
    retval))
