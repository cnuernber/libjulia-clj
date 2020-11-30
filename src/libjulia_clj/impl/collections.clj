(ns libjulia-clj.impl.collections
  (:require [libjulia-clj.impl.base :as base]
            [libjulia-clj.impl.protocols :as julia-proto]
            [tech.v3.jna :as jna])
  (:import [java.util Map Iterator]
           [clojure.lang ILookup ISeq MapEntry]
           [com.sun.jna Pointer]))


(deftype JuliaDict [^Pointer handle]
  julia-proto/PToJulia
  (->julia [item] handle)
  jna/PToPtr
  (is-jna-ptr-convertible? [this] true)
  (->ptr-backing-store [this] handle)
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


(defmethod julia-proto/julia->jvm "Dict"
  [jl-ptr options]
  (base/root-ptr! jl-ptr options)
  (JuliaDict. jl-ptr))
