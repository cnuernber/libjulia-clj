(ns libjulia-clj.impl.protocols
  "Protocols and multimethod definitions for the base julia binding.
  Protocols get completely rebound (thus loosing all older bindings)
  when recompiled so it is often a good idea to separate them out into
  their own file."
  (:require [libjulia-clj.impl.ffi :as julia-ffi]))


(defprotocol PToJulia
  (->julia [item]))


(defprotocol PJuliaKWFn
  "Implementation protocol to get the julia kw fn for a given fn."
  (kw-fn [item]))


(defmulti julia->jvm
  "Convert a julia value to the JVM.

  Options:

  * `:unrooted?` - defaults to false.  When true, value is not rooted and no thus the
     julia GC may remove the value any point in your program's execution most
     likely resulting in a crash.
  * `:log-level` - When anything at all, jvm-gc<->julia-gc bindings will emit messages
     when an object is bound or unbound.
  * `:gc-obj` - Have the new julia object maintain a reference to this object.  Only
     used for very special cases."
  (fn [julia-val options]
    (julia-ffi/jl-ptr->typename julia-val)))
