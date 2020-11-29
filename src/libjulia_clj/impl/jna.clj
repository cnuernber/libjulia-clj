(ns libjulia-clj.impl.jna
  "Low level raw JNA bindings and some basic initialization facilities"
  (:require [tech.v3.jna :as jna]
            [tech.v3.jna.base :as jna-base]
            [tech.v3.datatype.casting :as casting]
            [tech.v3.datatype.pprint :as dtype-pp]
            [camel-snake-kebab.core :as csk]
            [clojure.string :as s]
            [clojure.java.io :as io]
            [clojure.set :as set])
  (:import [com.sun.jna Pointer NativeLibrary]
           [julia_clj JLOptions]))



(defonce julia-library-path* (atom "julia"))


(defmacro def-julia-fn
  [fn-name docstring rettype & argpairs]
  `(defn ~fn-name
     ~docstring
     ~(mapv first argpairs)
     ;;Will need to protected against multithreaded access eventually
     #_(when-not (== (current-thread-id) (.get ^AtomicLong gil-thread-id))
       (throw (Exception. "Failure to capture gil when calling into libpython")))
     (let [~'jl-fn (jna/find-function ~(str fn-name) @julia-library-path*)
           ~'fn-args (object-array
                      ~(mapv (fn [[arg-symbol arg-coersion]]
                               (when (= arg-symbol arg-coersion)
                                 (throw (ex-info (format "Argument symbol (%s) cannot match coersion (%s)"
                                                         arg-symbol arg-coersion)
                                                 {})))
                               `(~arg-coersion ~arg-symbol))
                             argpairs))]
       ~(if rettype
          `(.invoke (jna-base/to-typed-fn ~'jl-fn) ~rettype ~'fn-args)
          `(.invoke (jna-base/to-typed-fn ~'jl-fn) ~'fn-args)))))

(defn jl_value_t
  ^Pointer [item]
  (jna/ensure-ptr item))


(defn jl_module_t
  ^Pointer [item]
  (jna/ensure-ptr item))

(defn jl_function_t
  ^Pointer [item]
  (jna/ensure-ptr item))


(def-julia-fn jl_init__threading
  "Initialize julia interpreter"
  nil)


(def-julia-fn jl_is_initialized
  "Check of the interpreter is initialized"
  Integer)


(def-julia-fn jl_eval_string
  "Eval a string"
  Pointer
  [data str])


(def-julia-fn jl_unbox_float64
  "Unbox a value to a float64 value."
  Double
  [data jna/ensure-ptr])


(def-julia-fn jl_atexit_hook
  "Shutdown julia gracefully"
  nil
  [status int])


(def-julia-fn jl_typename_str
  "Return the typename as a string"
  String
  [v jl_value_t])


(def-julia-fn jl_typeof_str
  "Return the type of an object as a string"
  String
  [v jl_value_t ])


(def-julia-fn jl_exception_occurred
  "Return the exception that occurred or now"
  Pointer)


(def-julia-fn jl_symbol
  "Create a julia symbol from a string"
  Pointer
  [symbol-name str])


(defn jl_symbol_name
  ^String [ptr]
  (jna/variable-byte-ptr->string
   (Pointer. (+ (Pointer/nativeValue ptr) 24))))


(def-julia-fn jl_symbol_lookup
  "Create a julia symbol from a string"
  Pointer
  [symbol-name str])


(def-julia-fn jl_symbol_n
  "Create a julia symbol from a string"
  Pointer
  [symbol-name jna/ensure-ptr
   name-len long])


(def-julia-fn jl_gensym
  "Generate a julia symbol"
  Pointer)


(def-julia-fn jl_typeof
  "return type of the thing"
  Pointer
  [t jl_value_t])


(def-julia-fn jl_get_global
  "Return the exception that occurred or now"
  Pointer
  [m jl_module_t]
  [s jl_value_t])


(defn jl_get_function
  "Find a julia function in a julia module"
  [module fn-name]
  (jl_get_global module (jl_symbol fn-name)))


(def-julia-fn jl_module_name
  "Return the module name as a symbol"
  Pointer
  [m jl_module_t])


(def-julia-fn jl_get_module_binding
  "Return the module name as a symbol"
  Pointer
  [m jl_module_t]
  [s jl_value_t])


(def-julia-fn jl_module_build_id
  "Return the module name as a symbol"
  Long
  [m jl_module_t])


(def-julia-fn jl_get_default_sysimg_path
  "Get the default sysimg path"
  String)


(def-julia-fn jl_subtype
  "Return 1 if this is a subtype of that"
  Integer
  [a jl_value_t]
  [b jl_value_t])


(def-julia-fn jl_isa
  "Return 1 't' isa 'x'"
  Integer
  [x jl_value_t]
  [t jl_value_t])

(def-julia-fn jl_call
  "Call a julia function"
  Pointer
  [f jl_function_t]
  [args jna/ensure-ptr]
  [nargs int])

(def-julia-fn jl_call0
  "Call a julia function with no arguments"
  Pointer
  [f jl_function_t])


(def-julia-fn jl_call1
  "Call a julia function with no arguments"
  Pointer
  [f jl_function_t]
  [a jl_value_t])


(def-julia-fn jl_call2
  "Call a julia function with no arguments"
  Pointer
  [f jl_function_t]
  [a jl_value_t]
  [b jl_value_t])


(def-julia-fn jl_call3
  "Call a julia function with no arguments"
  Pointer
  [f jl_function_t]
  [a jl_value_t]
  [b jl_value_t]
  [c jl_value_t])


;;Boxing things up into julia-land

(def-julia-fn jl_box_bool
  "Box a boolean value"
  Pointer
  [x (comp unchecked-byte #(casting/bool->number %) boolean)])


(def-julia-fn jl_box_uint8
  "Box a uint8 value"
  Pointer
  [x unchecked-byte])

(def-julia-fn jl_box_int8
  "Box a int8 value"
  Pointer
  [x unchecked-byte])

(def-julia-fn jl_box_uint16
  "Box a uint16 value"
  Pointer
  [x unchecked-short])

(def-julia-fn jl_box_int16
  "Box a int16 value"
  Pointer
  [x unchecked-short])

(def-julia-fn jl_box_uint32
  "Box a uint32 value"
  Pointer
  [x unchecked-int])


(def-julia-fn jl_box_int32
  "Box a int32 value"
  Pointer
  [x unchecked-int])

(def-julia-fn jl_box_uint64
  "Box a uint64 value"
  Pointer
  [x unchecked-long])


(def-julia-fn jl_box_int64
  "Box a int64 value"
  Pointer
  [x unchecked-long])


(def-julia-fn jl_box_float32
  "Box a float32 value"
  Pointer
  [x unchecked-float])

(def-julia-fn jl_box_float64
  "Box a float64 value"
  Pointer
  [x unchecked-double])


;;Unboxing things from julia-land

(def-julia-fn jl_unbox_bool
  "Unbox a boolean"
  Integer
  [x jl_value_t])

(def-julia-fn jl_unbox_uint8
  "Unbox a uint8 value"
  Byte
  [x jl_value_t])

(def-julia-fn jl_unbox_int8
  "Unbox a int8 value"
  Byte
  [x jl_value_t])

(def-julia-fn jl_unbox_uint16
  "Unbox a uint16 value"
  Short
  [x jl_value_t])

(def-julia-fn jl_unbox_int16
  "Unbox a int16 value"
  Short
  [x jl_value_t])

(def-julia-fn jl_unbox_uint32
  "Unbox a uint32 value"
  Integer
  [x jl_value_t])


(def-julia-fn jl_unbox_int32
  "Unbox a int32 value"
  Integer
  [x jl_value_t])

(def-julia-fn jl_unbox_uint64
  "Unbox a uint64 value"
  Long
  [x jl_value_t])


(def-julia-fn jl_unbox_int64
  "Unbox a int64 value"
  Long
  [x jl_value_t])


(def-julia-fn jl_unbox_float32
  "Unbox a float32 value"
  Float
  [x jl_value_t])

(def-julia-fn jl_unbox_float64
  "Unbox a float64 value"
  Double
  [x jl_value_t])


(def-julia-fn jl_cstr_to_string
  "Convert a jvm string to a julia string"
  Pointer
  [arg str])


(def-julia-fn jl_string_ptr
  "Convert a julia string to the jvm"
  String
  [arg jl_value_t])


(def-julia-fn jl_array_size
  "Return the size of this dimension of the array"
  jna/size-t-type
  [ary jl_value_t]
  [d int])

(def-julia-fn jl_array_rank
  "Return the rank of the array"
  Integer
  [ary jl_value_t])

(def-julia-fn jl_array_eltype
  "Return elemwise datatype of the array"
  Pointer
  [ary jl_value_t])

(def-julia-fn jl_array_ptr
  "Return a pointer to the elemwise data of the array"
  Pointer
  [ary jl_value_t])


(def-julia-fn jl_arrayref
  "Return the rank of the array"
  Pointer
  [ary jl_value_t]
  [d int])

(def-julia-fn jl_apply_tuple_type_v
  "Create a new tuple type."
  Pointer
  [ary jna/ensure-ptr]
  [d jna/size-t])


(def-julia-fn jl_apply_type
  "Apply a julia type"
  Pointer
  [tc jl_value_t]
  [params jna/ensure-ptr]
  [n jna/size-t])


(def-julia-fn jl_apply_type1
  "Apply a julia type to 1 argument"
  Pointer
  [tc jl_value_t]
  [p1 jl_value_t])


(def-julia-fn jl_apply_type2
  "Apply julia type to 2 arguments"
  Pointer
  [tc jl_value_t]
  [p1 jl_value_t]
  [p2 jl_value_t])


(def-julia-fn jl_new_structv
  "Create a new julia struct from some values"
  Pointer
  [datatype jl_value_t]
  [args jna/ensure-ptr]
  [n-args int])


(def-julia-fn jl_gc_collect
  "Force a GC run"
  nil)

(def-julia-fn jl_gc_enable
  "Enable/disable the gc - 1 is enabled, 0 is disabled"
  Integer
  [enable int])

(def-julia-fn jl_gc_is_enabled
  "Return 1 if the julia gc is enabled"
  Integer)

(def julia-symbol-names
  (-> (slurp (io/resource "symbols.txt"))
      (s/split #"\n")))


(defn find-julia-symbols-by-name
  "find julia symbols that are like the symbol you are looking forB"
  [src-name]
  (let [src-name (.toLowerCase (str src-name))]
    (->> julia-symbol-names
         (filter #(.contains (.toLowerCase (str %)) src-name)))))


(defn list-julia-data-symbols
  []
  (->> julia-symbol-names
       (filter #(re-find #"\w+ B jl_" %))))


(defn find-julia-symbol
  ^Pointer [sym-name]
  (.getGlobalVariableAddress ^NativeLibrary (jna-base/load-library
                                             @julia-library-path*)
                             sym-name))


(defn find-deref-julia-symbol
  ^Pointer [sym-name]
  (-> (find-julia-symbol sym-name)
      (.getPointer 0)))


(defn jl_main_module
  ^Pointer []
  (find-deref-julia-symbol "jl_main_module"))

(defn jl_core_module
  ^Pointer []
  (find-deref-julia-symbol "jl_core_module"))

(defn jl_base_module
  ^Pointer []
  (find-deref-julia-symbol "jl_base_module"))

(defn jl_top_module
  ^Pointer []
  (find-deref-julia-symbol "jl_top_module"))


(defn jl_nothing
  ^Pointer []
  (find-deref-julia-symbol "jl_nothing"))


(defonce julia-typemap* (atom {:typeid->typename {}
                               :typename->typeid {}}))

(def jl-type->datatype
  {:jl-bool-type :boolean
   :jl-int-8-type :int8
   :jl-uint-8-type :uint8
   :jl-int-16-type :int16
   :jl-uint-16-type :uint16
   :jl-int-32-type :int32
   :jl-uint-32-type :uint32
   :jl-int-64-type :int64
   :jl-uint-64-type :uint64
   :jl-float-32-type :float32
   :jl-float-64-type :float64
   :jl-string-type :string
   :symbol :jl-symbol-type})


(def datatype->jl-type
  (set/map-invert jl-type->datatype))


(defn initialize-typemap!
  []
  (let [base-types (->> (list-julia-data-symbols)
                        (map (comp last #(s/split % #"\s+")))
                        (filter #(.endsWith ^String % "type"))
                        (map (fn [typename]
                               (let [jl-kwd (keyword (csk/->kebab-case typename))]
                                 [(find-deref-julia-symbol typename)
                                  (get jl-type->datatype jl-kwd jl-kwd)])))
                        (into {}))]
    (swap! julia-typemap*
           (fn [typemap]
             (-> typemap
                 (update :typeid->typename merge base-types)
                 (update :typename->typeid merge (set/map-invert base-types))))))
  :ok)


(defn jl-ptr->typename
  "If the typename is a known typename, return the keyword typename.
  Else return typeof_str."
  [item-ptr]
  (when (and item-ptr (not= 0 (Pointer/nativeValue item-ptr)))
    (if-let [retval (get-in @julia-typemap* [:typeid->typename (jl_typeof item-ptr)])]
      retval
      (let [^String type-str (jl_typeof_str item-ptr)]
        (if (.startsWith type-str "#")
          :jl-function
          type-str)))))


(defn julia-eltype->datatype
  [eltype]
  (get-in @julia-typemap* [:typeid->typename eltype] :object))


(defn julia-options
  ^JLOptions []
  (JLOptions. (find-julia-symbol "jl_options")))


(dtype-pp/implement-tostring-print JLOptions)


(defn disable-julia-signals!
  []
  (let [opts (julia-options)]
    (set! (.handle_signals opts) 0)
    (.writeField opts "handle_signals")))


(defmacro with-disabled-julia-gc
  "Run a block of code with the julia garbage collector temporarily disabled."
  [& body]
  `(let [cur-enabled# (julia-jna/jl_gc_is_enabled)]
     (julia-jna/jl_gc_enable 0)
     (try
       ~@body
       (finally
         (julia-jna/jl_gc_enable cur-enabled#)))))
