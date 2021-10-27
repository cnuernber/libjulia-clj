(ns libjulia-clj.impl.ffi
  "Low level raw JNA bindings and some basic initialization facilities"
  (:require [tech.v3.datatype.pprint :as dtype-pp]
            [tech.v3.datatype.ffi :as dt-ffi]
            [tech.v3.datatype.ffi.size-t :as ffi-size-t]
            [tech.v3.datatype.ffi.ptr-value :as ffi-ptr-value]
            [tech.v3.datatype.errors :as errors]
            [tech.v3.datatype.native-buffer :as native-buffer]
            [tech.v3.datatype.casting :as casting]
            [tech.v3.datatype :as dt]
            [tech.v3.datatype.jvm-map :as jvm-map]
            [tech.v3.resource :as resource]
            [camel-snake-kebab.core :as csk]
            [clojure.string :as s]
            [clojure.set :as set]
            [clojure.tools.logging :as log]))


(set! *warn-on-reflection* true)


(defonce julia-library-path* (atom "julia"))


(def julia-fns-def
  {:jl_parse_opts {:rettype :void
                   :argtypes [['argcp :pointer]
                              ['argvp :pointer]]
                   :doc "parse argc/argv pair"}
   :jl_init__threading {:rettype :void
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
   :jl_exception_occurred {:rettype :pointer?
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
                           :argtypes [['ary :pointer?]
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
                               ['args :pointer?]
                               ['n-args :int32]]}


   :jl_gc_collect {:doc "Force a GC run"
                   :rettype :void}

   :jl_gc_enable {:doc "Enable/disable the gc - 1 is enabled, 0 is disabled"
                  :rettype :int32
                  :argtypes [['enable :int32]]}

   :jl_gc_is_enabled {:doc "Return 1 if the julia gc is enabled"
                      :rettype :int32}

   })


;;The symbols we need from the shared library
(def julia-symbols-def ["jl_core_module"
                        "jl_base_module"
                        "jl_bool_type"
                        "jl_symbol_type"
                        "jl_string_type"
                        "jl_bool_type"
                        "jl_int8_type"
                        "jl_uint8_type"
                        "jl_int16_type"
                        "jl_uint16_type"
                        "jl_int32_type"
                        "jl_uint32_type"
                        "jl_int64_type"
                        "jl_uint64_type"
                        "jl_float32_type"
                        "jl_float64_type"])


(defonce julia (dt-ffi/library-singleton #'julia-fns-def #'julia-symbols-def nil))
(dt-ffi/library-singleton-reset! julia)
(defn set-library-instance!
  [lib-instance]
  (dt-ffi/library-singleton-set-instance! julia lib-instance))


(defn initialize!
  "Load the libjulia library.  This does no further initialization, so it is necessary
  to the call 'init-process-options' and then 'jl_init__threading' at which point
  'jl_is_initialized' should be true."
  [& [options]]
  (let [jh (or (:julia-home options) (System/getenv "JULIA_HOME"))
        _ (errors/when-not-errorf jh "JULIA_HOME is unset")
        jdirs ["lib" "bin"]
        jpaths (map #(-> (java.nio.file.Paths/get
                          jh (into-array String [% (System/mapLibraryName "julia")]))
                         (.toString))
                    jdirs)
        found-dir (-> (filter #(.exists (java.io.File. ^String %)) jpaths)
                      first)]
    (errors/when-not-errorf found-dir
                            "Julia shared library not found at paths\n%s
- is JULIA_HOME set incorrectly?", (interpose jpaths "\n"))
    (dt-ffi/library-singleton-set! julia found-dir)
    :ok))

(defn find-fn [fn-name] (dt-ffi/library-singleton-find-fn julia fn-name))

(dt-ffi/define-library-functions libjulia-clj.impl.ffi/julia-fns-def find-fn nil)

(defn jl_symbol_name
  ^String [ptr]
  (dt-ffi/c->string ptr))


(defn jl_get_function
  "Find a julia function in a julia module"
  [module fn-name]
  (jl_get_global module (jl_symbol fn-name)))


(defn native-addr
  ^long [data]
  (-> (dt/as-native-buffer data)
      (.address)))


(defn init-process-options
  "Must be called before jl_init__threading.

  Options are:
  * `:enable-signals?` - true to enable julia signal handling.  If false you must disable
     threads (set n-threads to 1).  If using signals you must preload libjsig.so - see
     documentation.  Defaults to false.
  * `:n-threads` - Set the number of threads to use with julia.   Defaults to nil which means
     unless JULIA_NUM_THREADS is set signals are disabled."
  [& [{:keys [enable-signals? n-threads]
       :or {enable-signals? false}}]]
  (resource/stack-resource-context
   (let [n-threads (cond
                     (= n-threads -1) (.availableProcessors (Runtime/getRuntime))
                     (nil? n-threads) (when-let [env-val (System/getenv "JULIA_NUM_THREADS")]
                                        (Integer/parseInt env-val))
                     (= n-threads :auto) :auto
                     :else (int n-threads))
         enable-signals? (if (boolean? enable-signals?)
                           enable-signals?
                           (or enable-signals? (not (nil? n-threads))))
         opt-strs [(format "--handle-signals=%s" (if enable-signals? "yes" "no"))
                   "--threads" (str (or n-threads 1))]
         ptr-type (ffi-size-t/lower-ptr-type :pointer)
         str-ptrs (mapv (comp native-addr dt-ffi/string->c) opt-strs)
         argv (dt/make-container :native-heap ptr-type str-ptrs)
         argvp (dt/make-container :native-heap ptr-type [(native-addr argv)])
         argcp (dt/make-container :native-heap ptr-type [(count opt-strs)])]
     (log/infof "julia library arguments: %s" opt-strs)
     (jl_parse_opts argcp argvp)
     (argcp 0))))

(defonce julia-symbols (jvm-map/concurrent-hash-map))

(defn find-julia-symbol
  ^tech.v3.datatype.ffi.Pointer [sym-name]
  (jvm-map/compute-if-absent! julia-symbols sym-name
                              (fn [& args]
                                (-> (dt-ffi/library-singleton-find-symbol julia sym-name)
                                    (dt-ffi/->pointer)))))


(defn find-deref-julia-symbol
  ^tech.v3.datatype.ffi.Pointer [sym-name]
  (let [ptr-ptr (-> (find-julia-symbol sym-name)
                    (.address))
        buf-dtype (ffi-size-t/lower-ptr-type :pointer)
        nbuf (native-buffer/wrap-address ptr-ptr (casting/numeric-byte-width buf-dtype)
                                         buf-dtype :little-endian nil)]
    ;;deref the ptr
    (tech.v3.datatype.ffi.Pointer. (nbuf 0))))


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


(def jl-type->datatype
  {:jl-bool-type :boolean
   :jl-int8-type :int8
   :jl-uint8-type :uint8
   :jl-int16-type :int16
   :jl-uint16-type :uint16
   :jl-int32-type :int32
   :jl-uint32-type :uint32
   :jl-int64-type :int64
   :jl-uint64-type :uint64
   :jl-float32-type :float32
   :jl-float64-type :float64
   :jl-string-type :string
   :jl-symbol-type :symbol})


(def datatype->jl-type
  (set/map-invert jl-type->datatype))

(defonce type-ptr->kwd (jvm-map/concurrent-hash-map))
(defonce kwd->type-ptr (jvm-map/concurrent-hash-map))

(defn add-library-julia-type!
  ([type-symbol-name clj-kwd]
   (let [type-ptr (find-deref-julia-symbol type-symbol-name)]
     (jvm-map/compute-if-absent! type-ptr->kwd type-ptr (constantly clj-kwd))
     (jvm-map/compute-if-absent! kwd->type-ptr clj-kwd (constantly type-ptr))
     type-ptr))
  ([type-symbol-name]
   (add-library-julia-type! type-symbol-name (keyword type-symbol-name))))


(defn initialize-type-map!
  []
  (doseq [[typename clj-kwd] jl-type->datatype]
    (add-library-julia-type! (.replace (name typename) "-" "_") clj-kwd))
  :ok)


(defn lookup-library-type
  [type-kwd]
  (if-let [retval (get kwd->type-ptr type-kwd)]
    retval
    (add-library-julia-type! (.replace (name (get datatype->jl-type type-kwd type-kwd))
                                       "-" "_") type-kwd)))


(defn jl-ptr->typename
  "If the typename is a known typename, return the keyword typename.
  Else return typeof_str."
  [item-ptr]
  (when (not= 0 (ffi-ptr-value/ptr-value? item-ptr))
    (if-let [retval (get type-ptr->kwd (jl_typeof item-ptr))]
      retval
      (let [^String type-str (jl_typeof_str item-ptr)]
        (if (.startsWith type-str "#")
          :jl-function
          type-str)))))


(defn julia-eltype->datatype
  [eltype]
  (get type-ptr->kwd eltype :object))


(defmacro with-disabled-julia-gc
  "Run a block of code with the julia garbage collector temporarily disabled."
  [& body]
  `(let [cur-enabled# (jl_gc_is_enabled)]
     (jl_gc_enable 0)
     (try
       ~@body
       (finally
         (jl_gc_enable cur-enabled#)))))
