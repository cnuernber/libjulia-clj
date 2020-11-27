(ns libjulia-clj.impl.protocols
  "Protocols and multimethod definitions for the base julia binding.
  Protocols get completely rebound (thus loosing all older bindings)
  when recompiled so it is often a good idea to separate them out into
  their own file."
  (:require [libjulia-clj.impl.jna :as julia-jna]
            [tech.v3.datatype.errors :as errors]))


(defprotocol PToJulia
  (->julia [item]))


;;Object default protocol implementation
(extend-type Object
  PToJulia
  (->julia [item]
    (errors/throwf "Item %s is not convertible to julia" item)))


(defmulti julia->jvm
  "Convert a julia value to the JVM.

  Options:

  * `:unrooted?` - defaults to false.  When true, value is not rooted and
     no thus the julia GC may remove the value any point in your program's execution most
     likely resulting in a crash.
  * `:log-level` - When anything at all, jvm-gc<->julia-gc bindings will emit messages
     when an object is bound or unbound."
  (fn [julia-val options]
    (julia-jna/jl-ptr->typename julia-val)))
