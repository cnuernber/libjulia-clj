(ns libjulia-clj.impl.ffi
  "Low level raw JNA bindings and some basic initialization facilities"
  (:require [tech.v3.datatype.pprint :as dtype-pp]
            [tech.v3.datatype.ffi :as dt-ffi]
            [tech.v3.datatype.errors :as errors]
            [camel-snake-kebab.core :as csk]
            [clojure.string :as s]
            [clojure.set :as set]
            [clojure.tools.logging :as log]))



(defonce julia-library-path* (atom "julia"))


(def julia-fns-def
  {:jl_init__threading {:rettype :void
                        :doc "Initialize julia"}
   :jl_is_initialized {:rettype :int32
                       :doc "Returns 1 if julia is initialized, 0 otherwise"}
   :jl_eval_string {:rettype :pointer
                    :argtypes [['data :string]]
                    :doc "Eval a string returning a julia object"}
   :jl_atexit_hook {:rettype :void
                    :argytpes [['data :pointer]]
                    :doc "Register a julia fn to run at program exit"}
   :jl_typename_str {:rettype :string
                     :argtypes [['v :pointer]]
                     :doc "Return the object typename as a string"}
   :jl_typeof_str {:rettype :string
                   :argtypes [['v :pointer]]
                   :doc "Return the type of an object as a string"}
   :jl_exception_occured {:rettype :pointer?
                          :doc "Return the current in-flight exception or nil"}
   :jl_symbol {:rettype :pointer
               :argtypes [['symbol-name :string]]
               :doc "Create a julia symbol from a string"}
   :jl_symbol_lookup {:rettype :pointer
                      :argtypes [['symbol-name :string]]
                      :doc "Find a julia symbol in the runtime"}
   :jl_symbol_n {:rettype :pointer
                 :argtypes [['symbol-name :pointer]
                            ['name-len :int64]]
                 :doc "Create a julia symbol from a string"}
   :jl_gensym {:rettype :pointer
               :doc "Generate an opaque julia symbol"}
   :jl_typeof {:rettype :pointer
               :argtypes [['t :pointer]]
               :doc "Return the type of a thing as a julia symbol"}
   :jl_get_global {:rettype :pointer
                   :argtypes [['m :pointer]
                              ['s :pointer]]
                   :doc "Get reference to module symbol"}
   :jl_module_name {:rettype :pointer
                    :argytpes [['m :pointer]]
                    :doc "Return the module name as a symbol"}

   :jl_get_module_binding {:doc "Return the module name as a symbol"
                           :rettype :pointer
                           :argtypes [['m :pointer]
                                      ['s :pointer]]}

   :jl_module_build_id {:doc "Return the module name as a symbol"
                        :rettype :int64
                        :argtypes [['m :pointer]]}
   :jl_get_default_sysimg_path {:doc "Get the default sysimg path"
                                :rettype :string}


   :jl_subtype {:doc "Return 1 if this is a subtype of that"
                :rettype :int32
                :argtypes [['a :pointer]
                           ['b :pointer]]}
   :jl_isa {:doc "Return 1 if 't' isa 'x'"
            :rettype :int32
            ['x :pointer]
            ['t :pointer]}
   :jl_call {:rettype :pointer
             :argtypes [['f :pointer]
                        ['args :pointer]
                        ['nargs :int32]]
             :doc "Call a julia function with a variable number of arguments"}
   :jl_call0 {:rettype :pointer
              :argtypes [['f :pointer]]
              :doc "Call a julia function with no arguments"}
   :jl_call1 {:rettype :pointer
              :argtypes [['f :pointer]
                         ['arg0 :pointer]]
              :doc "Call a julia function with 1 argument"}
   :jl_call2 {:doc "Call a julia function with 2 arguments"
              :rettype :pointer
              :argtypes [['f :pointer]
                         ['a0 :pointer]
                         ['a1 :pointer]]}
   :jl_call3 {:doc   "Call a julia function with 3 arguments"
              :rettype :pointer
              :argtypes [['f :pointer]
                         ['a0 :pointer]
                         ['a1 :pointer]
                         ['a2 :pointer]]}
   :jl_box_bool {:doc "Box a boolean value"
                 :rettype :pointer
                 :argtypes [['x :int8]]}

   :jl_box_uint8 {:doc "Box a uint8 value"
                  :rettype :pointer
                  :argtypes [['x :int8]]}
   :jl_box_int8 {:doc "Box a int8 value"
                 :rettype :pointer
                 :argtypes [['x :int8]]}

   :jl_box_uint16 {:doc "Box a uint16 value"
                   :rettype :pointer
                   :argtypes [['x :int16]]}
   :jl_box_int16 {:doc "Box a int16 value"
                  :rettype :pointer
                  :argtypes [['x :int16]]}

   :jl_box_uint32 {:doc "Box a uint32 value"
                   :rettype :pointer
                   :argtypes [['x :int32]]}
   :jl_box_int32 {:doc "Box a int32 value"
                  :rettype :pointer
                  :argtypes [['x :int32]]}

   :jl_box_uint64 {:doc "Box a uint64 value"
                   :rettype :pointer
                   :argtypes [['x :int64]]}
   :jl_box_int64 {:doc "Box a int64 value"
                  :rettype :pointer
                  :argtypes [['x :int64]]}

   :jl_box_float32 {:doc "Box a float32 value"
                    :rettype :pointer
                    :argtypes [['x :float32]]}
   :jl_box_float64 {:doc "Box a float64 value"
                    :rettype :pointer
                    :argtypes [['x :float64]]}

   :jl_box_voidpointer {:doc "Box a pointer value to a julia voidptr value"
                        :rettype :pointer
                        :argtypes [['x :pointer]]}

   :jl_unbox_bool {:doc "Unbox a boolean"
                   :rettype :int32
                   :argtypes [['x :pointer]]}

   :jl_unbox_uint8 {:doc "Unbox a uint8 value"
                    :rettype :int8
                    :argtypes [['x :pointer]]}
   :jl_unbox_int8 {:doc "Unbox a int8 value"
                   :rettype :int8
                   :argtypes [['x :pointer]]}

   :jl_unbox_uint16 {:doc "Unbox a uint16 value"
                     :rettype :int16
                     :argtypes [['x :pointer]]}
   :jl_unbox_int16 {:doc "Unbox a int16 value"
                    :rettype :int16
                    :argtypes [['x :pointer]]}

   :jl_unbox_uint32 {:doc "Unbox a uint32 value"
                     :rettype :int32
                     :argtypes [['x :pointer]]}
   :jl_unbox_int32 {:doc "Unbox a int32 value"
                    :rettype :int32
                    :argtypes [['x :pointer]]}

   :jl_unbox_uint64 {:doc "Unbox a uint64 value"
                     :rettype :int64
                     :argtypes [['x :pointer]]}
   :jl_unbox_int64 {:doc "Unbox a int64 value"
                    :rettype :int64
                    :argtypes [['x :pointer]]}

   :jl_unbox_float32 {:doc "Unbox a float32 value"
                      :rettype :float32
                      :argtypes [['x :pointer]]}
   :jl_unbox_float64 {:doc "Unbox a float64 value"
                      :rettype :float64
                      :argtypes [['x :pointer]]}

   :jl_cstr_to_string {:doc "Convert a jvm string to a julia string"
                       :rettype :pointer
                       :argtypes [['arg :string]]}

   :jl_string_ptr {:doc "Convert a julia string to the jvm"
                   :rettype :string
                   :argtypes [['arg :pointer]]}

   :jl_array_size {:doc "Return the size of this dimension of the array"
                   :rettype :size-t
                   :argtypes [['ary :pointer]
                              ['d :int32]]}

   :jl_array_rank {:doc "Return the rank of the array"
                   :rettype :int32
                   :argtypes [['ary :pointer]]}

   :jl_array_eltype {:doc "Return elemwise datatype of the array"
                     :rettype :pointer
                     :argtypes [['ary :pointer]]}

   :jl_array_ptr {:doc "Return a pointer to the elemwise data of the array"
                  :rettype :pointer
                  :argtypes [['ary :pointer]]}

   :jl_arrayref {:doc "Return the rank of the array"
                 :rettype :pointer
                 :argtypes [['ary :pointer]
                            ['d :int32]]}

   :jl_apply_tuple_type_v {:doc "Create a new tuple type."
                           :rettype :pointer
                           :argtypes [['ary :pointer]
                                      ['d :size-t]]}

   :jl_apply_type {:doc "Apply a julia type"
                   :rettype :pointer
                   :argtypes [['tc :pointer]
                              ['params :pointer]
                              ['n :size-t]]}

   :jl_apply_type1 {:doc "Apply a julia type to 1 argument"
                    :rettype :pointer
                    :argtypes [['tc :pointer]
                               ['p1 :pointer]]}

   :jl_apply_type2 {:doc "Apply julia type to 2 arguments"
                    :rettype :pointer
                    :argtypes [['tc :pointer]
                               ['p1 :pointer]
                               ['p2 :pointer]]}

   :jl_new_structv {:doc "Create a new julia struct from some values"
                    :rettype :pointer
                    :argtypes [['datatype :pointer]
                               ['args :pointer]
                               ['n-args :int32]]}


   :jl_gc_collect {:doc "Force a GC run"
                   :rettype :void}

   :jl_gc_enable {:doc "Enable/disable the gc - 1 is enabled, 0 is disabled"
                  :rettype :int32
                  :argtypes [['enable :int32]]}

   :jl_gc_is_enabled {:doc "Return 1 if the julia gc is enabled"
                      :rettype :int32}

   })


(defonce julia (dt-ffi/library-singleton #'julia-fns-def))
(dt-ffi/library-singleton-reset! julia)
(defn set-library-instance!
  [lib-instance]
  (dt-ffi/library-singleton-set-instance! julia lib-instance))

(defn initialize!
  [& [options]]
  (let [jh (or (:julia-home options) (System/getenv "JULIA_HOME"))
        _ (errors/when-not-errorf jh "JULIA_HOME unset")
        jpath (-> (java.nio.file.Paths/get jh (into-array String ["lib"
                                                                  (System/mapLibraryName
                                                                   "julia")]))
                  (.toString))]
    (errors/when-not-errorf (.exists (java.io.File. jpath))
                            "Julia shared library not found at path %s
- is JULIA_HOME set incorrectly?", jpath)
    (dt-ffi/library-singleton-set! julia jpath)))

(defn find-fn [fn-name] (dt-ffi/library-singleton-find-fn julia fn-name))

(dt-ffi/define-library-functions libjulia-clj.impl.ffi/julia-fns-def find-fn nil)

(defn jl_symbol_name
  ^String [ptr]
  (dt-ffi/c->string ptr))


(defn jl_get_function
  "Find a julia function in a julia module"
  [module fn-name]
  (jl_get_global module (jl_symbol fn-name)))


(defn find-julia-symbol
  [sym-name]
  #_(.getGlobalVariableAddress ^NativeLibrary (jna-base/load-library
                                             @julia-library-path*)
                               sym-name))


(defn find-deref-julia-symbol
  [sym-name]
  #_(-> (find-julia-symbol sym-name)
      (.getPointer 0)))


(defn jl_main_module
  []
  (find-deref-julia-symbol "jl_main_module"))

(defn jl_core_module
  []
  (find-deref-julia-symbol "jl_core_module"))

(defn jl_base_module
  []
  (find-deref-julia-symbol "jl_base_module"))

(defn jl_top_module
  []
  (find-deref-julia-symbol "jl_top_module"))

(defn jl_nothing
  []
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


#_(defn initialize-typemap!
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


#_(defn jl-ptr->typename
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


#_(defn julia-eltype->datatype
  [eltype]
  (get-in @julia-typemap* [:typeid->typename
                           (jna/->ptr-backing-store eltype)]
          :object))


#_(defn julia-options
  ^JLOptions []
  (JLOptions. (find-julia-symbol "jl_options")))


#_(dtype-pp/implement-tostring-print JLOptions)


#_(defn disable-julia-signals!
  [& [options]]
  (let [opts (julia-options)
        n-threads (:n-threads options)
        signals-enabled? (:signals-enabled? options (not (nil? n-threads)))]
    (log/infof "Julia startup options: n-threads %d, signals? %s, opt-level %d"
               n-threads signals-enabled? (:optimization-level options 0))
    (when-not signals-enabled?
      (set! (.handle_signals opts) 0)
      (.writeField opts "handle_signals"))
    (when-let [n-threads (:n-threads options)]
      (set! (.nthreads opts) n-threads)
      (.writeField opts "nthreads"))
    (when-let [opt-level (:optimization-level options)]
      (set! (.opt_level opts) (int opt-level))
      (.writeField opts "opt_level"))))


#_(defmacro with-disabled-julia-gc
  "Run a block of code with the julia garbage collector temporarily disabled."
  [& body]
  `(let [cur-enabled# (julia-jna/jl_gc_is_enabled)]
     (julia-jna/jl_gc_enable 0)
     (try
       ~@body
       (finally
         (julia-jna/jl_gc_enable cur-enabled#)))))
