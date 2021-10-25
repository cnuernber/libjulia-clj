(ns libjulia-clj.impl.collections
  (:require [libjulia-clj.impl.base :as base]
            [libjulia-clj.impl.protocols :as julia-proto]
            [libjulia-clj.impl.ffi :as julia-ffi]
            [tech.v3.datatype.pprint :as dtype-pp]
            [tech.v3.datatype.ffi :as dt-ffi])
  (:import [java.util Map Iterator]
           [clojure.lang ILookup ISeq MapEntry]
           [com.sun.jna Pointer]
           [tech.v3.datatype ObjectReader]))


(deftype JuliaDict [^Pointer handle]
  julia-proto/PToJulia
  (->julia [item] handle)
  dt-ffi/PToPointer
  (convertible-to-pointer? [this] true)
  (->pointer [this] handle)
  Map
  (size [this] (int (base/module-fn :length handle)))
  (isEmpty [this] (boolean (base/module-fn :isempty handle)))
  (containsKey [this k] (boolean (base/module-fn :haskey handle k)))
  (containsValue [this v] (throw (UnsupportedOperationException. "Unimplemented")))
  (get [this k] (base/module-fn :get handle k))
  (put [this k v] (base/module-fn :setindex! handle v k))
  (remove [this k] (base/module-fn :delete! handle k))
  (putAll [this m] (throw (UnsupportedOperationException.)))
  (clear [this] (throw (UnsupportedOperationException.)))
  (keySet [this]
    (throw (UnsupportedOperationException.)))
  (values [this] (throw (UnsupportedOperationException.)))
  (entrySet [this]
    (throw (UnsupportedOperationException.)))
  Iterable
  (iterator [this]
    (let [pairs (base/module-fn :pairs handle)
          base-iterator (.iterator (base/julia-obj->iterable pairs))]
      (reify Iterator
        (hasNext [iter] (.hasNext base-iterator))
        (next [iter]
          (let [next-pair (.next base-iterator)]
            (MapEntry. (base/module-fn :getindex next-pair 1)
                       (base/module-fn :getindex next-pair 2)))))))
  Object
  (toString [this]
    (base/jl-obj->str handle)))


(dtype-pp/implement-tostring-print JuliaDict)


(defmethod julia-proto/julia->jvm "Dict"
  [jl-ptr options]
  (base/root-ptr! jl-ptr options)
  (JuliaDict. jl-ptr))


(deftype JuliaTuple [^Pointer handle]
  julia-proto/PToJulia
  (->julia [item] handle)
  dt-ffi/PToPointer
  (convertible-to-pointer? [this] true)
  (->pointer [this] handle)
  ObjectReader
  (elemwiseDatatype [this]
    (-> (base/module-fn :eltype handle)
        (julia-ffi/julia-eltype->datatype)))
  (lsize [this] (long (base/module-fn :length handle)))
  (readObject [this idx]
    (base/module-fn :getindex handle (int (inc idx))))
  Object
  (toString [this]
    (base/jl-obj->str handle)))


(dtype-pp/implement-tostring-print JuliaTuple)


(defmethod julia-proto/julia->jvm "Tuple"
  [jl-ptr options]
  (base/root-ptr! jl-ptr options)
  (JuliaTuple. jl-ptr))
