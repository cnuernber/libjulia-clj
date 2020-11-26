(ns libjulia-clj.impl.base
  (:require [tech.v3.jna :as jna]
            [tech.v3.jna.base :as jna-base]
            [tech.v3.datatype :as dtype]
            [tech.v3.datatype.protocols :as dtype-proto]
            [tech.v3.datatype.errors :as errors]
            [tech.v3.datatype.casting :as casting]
            [tech.v3.datatype.pprint :as dtype-pp]
            [tech.v3.datatype.native-buffer :as native-buffer]
            [tech.v3.datatype.jna :as dtype-jna]
            [tech.v3.tensor.dimensions.analytics :as dims-analytics]
            [tech.v3.tensor :as dtt]
            [tech.v3.resource :as resource]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [camel-snake-kebab.core :as csk]
            [clojure.set :as set]
            [primitive-math :as pmath]
            [clojure.tools.logging :as log])
  (:import [com.sun.jna Pointer NativeLibrary]
           [julia_clj JLOptions]
           [java.nio.file Paths]
           [clojure.lang IFn Symbol Keyword]))


(defonce julia-library-path* (atom "julia"))


(definline current-thread-id
  ^long []
  (-> (Thread/currentThread)
      (.getId)))


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
  (.getGlobalVariableAddress ^NativeLibrary (jna-base/load-library @julia-library-path*)
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


(defonce jvm-julia-roots* (atom nil))


(defn initialize-julia-root-map!
  []
  (errors/when-not-error
   (nil? @jvm-julia-roots*)
   "Attempt to initialize julia root map twice")
  (let [refmap (jl_eval_string "jvm_refs = IdDict()")
        set-index! (jl_get_function (jl_base_module) "setindex!")
        delete! (jl_get_function (jl_base_module) "delete!")]
    (reset! jvm-julia-roots*
            {:jvm-refs refmap
             :set-index! set-index!
             :delete! delete!})))


(defn root-ptr!
  "Root a pointer and set a GC hook that will unroot it when the time comes.
  Defaults to :auto track type"
  ([^Pointer value options]
   (let [{:keys [jvm-refs set-index! delete!]} @jvm-julia-roots*
         native-value (Pointer/nativeValue value)
         untracked-value (Pointer. native-value)]
     (when (:log-level options)
       (log/logf (:log-level options) "Rooting address  0x%016X" native-value))
     (jl_call3 set-index! jvm-refs untracked-value value)
     (resource/track value {:track-type (:resource-type options :auto)
                            :dispose-fn (fn []
                                          (when (:log-level options)
                                            (log/logf (:log-level options)
                                                      "Unrooting address 0x%016X"
                                                      native-value))
                                          (jl_call2 delete! jvm-refs untracked-value))})))
  ([value]
   (root-ptr! value nil)))


(defonce julia-typemap* (atom {:typeid->typename {}
                               :typename->typeid {}}))


(defn initialize-typemap!
  []
  (let [base-types (->> (list-julia-data-symbols)
                        (map (comp last #(s/split % #"\s+")))
                        (filter #(.endsWith ^String % "type"))
                        (map (fn [typename]
                               [(find-deref-julia-symbol typename)
                                (keyword (csk/->kebab-case typename))]))
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


(defn julia-options
  ^JLOptions []
  (JLOptions. (find-julia-symbol "jl_options")))


(dtype-pp/implement-tostring-print JLOptions)


(defn disable-julia-signals!
  []
  (let [opts (julia-options)]
    (set! (.handle_signals opts) 0)
    (.writeField opts "handle_signals")))


(defn combine-paths
  ^String [src-path & args]
  (-> (Paths/get (str src-path) ^"[java.lang.String;"
                 (into-array String
                             (map str args)))
      (.toString)))


(defonce error-handling-data* (atom nil))


(defn initialize-error-handling!
  []
  (errors/when-not-error
   (nil? @error-handling-data*)
   "Error handling initialized twice!")
  (reset! error-handling-data*
          {:sprint (jl_get_function (jl_base_module) "sprint")
           :showerror (jl_get_function (jl_base_module) "showerror")
           :catch-backtrace (jl_get_function (jl_base_module) "catch_backtrace")}))


(declare julia->jvm)


(defn sprint-last-error
  ^String [exc]
  (when exc
    (let [{:keys [sprint showerror catch-backtrace]} @error-handling-data*
          bt (jl_call0 catch-backtrace)]
      (-> (jl_call3 sprint showerror exc bt)
          (julia->jvm nil)))))


(defn check-last-error
  []
  (when-let [exc (jl_exception_occurred)]
    (errors/throwf "Julia error:\n%s" (or (sprint-last-error exc)
                                          "unable to print error"))))


(defonce julia-tostring-fns* (atom nil))

(defn initialize-generic-tostring!
  []
  (errors/when-not-error
   (nil? @julia-tostring-fns*)
   "Tostring functionality initialized twice!")
  (reset! julia-tostring-fns*
          {:sprint (jl_get_function (jl_base_module) "sprint")
           :dump (jl_get_function (jl_base_module) "dump")
           :print (jl_get_function (jl_base_module) "print")
           :methods (jl_get_function (jl_base_module) "methods")
           :length (jl_get_function (jl_base_module) "length")}))


(defn jl-obj->str
  ^String [jl-ptr]
  (when jl-ptr
    (let [{:keys [sprint dump print]} @julia-tostring-fns*]
      (-> (jl_call2 sprint print jl-ptr)
          (julia->jvm nil)))))


(defn initialize!
  "Initialize julia optionally providing an explicit path which will override
  the julia library search mechanism.

  Currently the search mechanism is:

  [user-path]->JULIA_HOME->\"julia\"

  Returns :ok on success else exception."
  ([{:keys [julia-library-path]}]
   (let [julia-library-path (cond
                              (not (nil? julia-library-path))
                              julia-library-path
                              (not (nil? (System/getenv "JULIA_HOME")))
                              (combine-paths (System/getenv "JULIA_HOME") "lib" (System/mapLibraryName "julia"))
                              :else
                              "julia")]
     (try
       (jna-base/load-library julia-library-path)
       (reset! julia-library-path* julia-library-path)
       (when-not (== 1 (jl_is_initialized))
         (disable-julia-signals!)
         (jl_init__threading)
         (initialize-typemap!)
         (initialize-julia-root-map!)
         (initialize-error-handling!)
         (initialize-generic-tostring!))
       (catch Throwable e
         (throw (ex-info (format "Failed to find julia library.  Is JULIA_HOME unset?  Attempted %s"
                                 julia-library-path)
                         {:error e})))))
   :ok)
  ([]
   (initialize! nil)))


(def ptr-dtype (jna/size-t-compile-time-switch :int32 :int64))


(defn module-symbol-names
  [module]
  (let [names-fn (jl_get_function (jl_base_module) "names")
        names-ary (jl_call1 names-fn module)
        ary-data (native-buffer/wrap-address (Pointer/nativeValue names-ary)
                                             16 ptr-dtype :little-endian nil)
        data-ptr (ary-data 0)
        ;;If julia is compiled with STORE_ARRAY_LENGTH
        data-len (ary-data 1)
        data (native-buffer/wrap-address data-ptr
                                         (* data-len
                                            (casting/numeric-byte-width ptr-dtype))
                                         ptr-dtype :little-endian nil)]
    (-> (dtype/emap (fn [^long sym-data]
                      (-> (Pointer. sym-data)
                          (jl_symbol_name)))
                    :string data)
        (dtype/clone))))


(defprotocol PToJulia
  (->julia [item])
  (as-julia [item]))


(defmulti julia->jvm
  "Convert a julia value to the JVM.

  Options:

  * `:unrooted?` - defaults to false.  When true, value is not rooted and
  no thus the julia GC may remove the value any point in your program's execution most
  likely resulting in a crash."
  (fn [julia-val options]
    (jl-ptr->typename julia-val)))

(declare call-function raw-call-function)


(deftype GenericJuliaObject [^Pointer handle]
  PToJulia
  (->julia [item] handle)
  (as-julia [item] handle)
  jna/PToPtr
  (is-jna-ptr-convertible? [this] true)
  (->ptr-backing-store [this] handle)
  Object
  (toString [this]
    (jl-obj->str handle)))

(dtype-pp/implement-tostring-print GenericJuliaObject)


(defmacro ^:private impl-callable-julia-object
  []
  `(deftype ~'CallableJuliaObject [~'handle]
     PToJulia
     (->julia [item] ~'handle)
     (as-julia [item] ~'handle)
     jna/PToPtr
     (is-jna-ptr-convertible? [this#] true)
     (->ptr-backing-store [this#] ~'handle)
     Object
     (toString [this]
       (jl-obj->str ~'handle))
     IFn
     ~@(->> (range 16)
         (map (fn [idx]
                (let [argsyms (->> (range idx)
                                   (mapv (fn [arg-idx]
                                           (symbol (str "arg-" arg-idx)))))]
                  `(invoke ~(vec (concat ['this]
                                         argsyms))
                           (call-function ~'handle ~argsyms nil))))))
     (applyTo [this# argseq#]
       (call-function ~'handle argseq# nil))))


(impl-callable-julia-object)


(dtype-pp/implement-tostring-print CallableJuliaObject)

(defn jl-obj-callable?
  [jl-obj]
  (when jl-obj
    (let [{:keys [methods length]} @julia-tostring-fns*]
      (try
        (not= 0 (long (call-function length
                                     [(raw-call-function methods [jl-obj])])))
        (catch Throwable e false)))))


(defmethod julia->jvm :default
  [julia-val options]
  (when-not (:unrooted? options)
    (root-ptr! julia-val))
  (if (jl-obj-callable? julia-val)
    (CallableJuliaObject. julia-val)
    (GenericJuliaObject. julia-val)))


(extend-type Object
  PToJulia
  (->julia [item]
    (errors/throwf "Item %s is not convertible to julia" item)))


(extend-protocol PToJulia
  Byte
  (->julia [item] (jl_box_int8 item))
  Short
  (->julia [item] (jl_box_int16 item))
  Integer
  (->julia [item] (jl_box_int32 item))
  Long
  (->julia [item] (jl_box_int64 item))
  Float
  (->julia [item] (jl_box_float32 item))
  Double
  (->julia [item] (jl_box_float64 item))
  String
  (->julia [item] (jl_cstr_to_string item))
  Symbol
  (->julia [item] (jl_symbol (name item)))
  Keyword
  (->julia [item] (jl_symbol (name item)))
  Pointer
  (->julia [item] item))

(defmethod julia->jvm :jl-bool-type
  [julia-val options]
  (if (== 0 (jl_unbox_bool julia-val))
    false
    true))


(defmethod julia->jvm :jl-uint-8-type
  [julia-val options]
  (pmath/byte->ubyte (jl_unbox_uint8 julia-val)))

(defmethod julia->jvm :jl-uint-16-type
  [julia-val options]
  (pmath/short->ushort (jl_unbox_uint16 julia-val)))

(defmethod julia->jvm :jl-uint-32-type
  [julia-val options]
  (pmath/int->uint (jl_unbox_uint32 julia-val)))

(defmethod julia->jvm :jl-uint-64-type
  [julia-val options]
  (jl_unbox_uint64 julia-val))


(defmethod julia->jvm :jl-int-8-type
  [julia-val options]
  (jl_unbox_int8 julia-val))

(defmethod julia->jvm :jl-int-16-type
  [julia-val options]
  (jl_unbox_int16 julia-val))

(defmethod julia->jvm :jl-int-32-type
  [julia-val options]
  (jl_unbox_int32 julia-val))

(defmethod julia->jvm :jl-int-64-type
  [julia-val options]
  (jl_unbox_int64 julia-val))

(defmethod julia->jvm :jl-float-64-type
  [julia-val options]
  (jl_unbox_float64 julia-val))

(defmethod julia->jvm :jl-float-32-type
  [julia-val options]
  (jl_unbox_float32 julia-val))

(defmethod julia->jvm :jl-string-type
  [julia-val options]
  (jl_string_ptr julia-val))


(defn jvm-args->julia
  [args]
  (mapv #(if %
           (->julia %)
           (jl_nothing)) args))


(defmacro with-disabled-gc
  [& body]
  `(let [cur-enabled# (jl_gc_is_enabled)]
     (jl_gc_enable 0)
     (try
       ~@body
       (finally
         (jl_gc_enable cur-enabled#)))))


(defn raw-call-function
  "Call the function.  We disable the Julia GC when marshalling arguments but
  the GC is enabled for the actual julia function call.  The result is returned
  to the user as a Pointer."
  ^Pointer [fn-handle args]
  (resource/stack-resource-context
   ;;do not GC my stuff when I am marshalling function arguments to julia
   (let [jl-args (with-disabled-gc (jvm-args->julia args))]
     (let [retval
           (case (count jl-args)
             0 (jl_call0 fn-handle)
             1 (jl_call1 fn-handle (first jl-args))
             2 (jl_call2 fn-handle (first jl-args) (second jl-args))
             3 (apply jl_call3 fn-handle jl-args)
             (let [n-args (count args)
                   ptr-buf (dtype/make-container :native-heap ptr-dtype
                                                 {:resource-type :stack}
                                                 (mapv #(if %
                                                          (Pointer/nativeValue ^Pointer %)
                                                          0)
                                                       jl-args))]
               (jl_call fn-handle ptr-buf n-args)))]
       (check-last-error)
       retval))))


(defn call-function
  "Call a function.  The result will be marshalled back to the jvm and if necessary,
  rooted."
  ([fn-handle args options]
   (-> (raw-call-function fn-handle args)
       (julia->jvm options)))
  ([fn-handle args]
   (call-function fn-handle args nil)))


(dtype-pp/implement-tostring-print Pointer)


(defn module-publics
  [module]
  (->> (module-symbol-names module)
       (map (fn [sym-name]
              (let [global-sym (jl_get_function module sym-name)]
                (when global-sym
                  [(symbol sym-name)
                   {:symbol-type (jl-ptr->typename global-sym)
                    :symbol global-sym}]))))
       (remove nil?)
       (sort-by first)))


(defmacro define-module-publics
  [module-name unsafe-name-map]
  (if-let [mod (jl_eval_string module-name)]
    (let [publics (module-publics mod)
          docs-fn (jl_eval_string "Base.Docs.doc")]
      `(do
         (def ~'module (jl_eval_string ~module-name))
         ~@(->> publics
                (map (fn [[sym {jl-symbol :symbol}]]
                       (when jl-symbol
                         (let [sym-name (name sym)
                               sym-rawname sym-name
                               sym-name (.replace ^String sym-name "@" "AT")
                               sym-name (get unsafe-name-map sym-name sym-name)
                               docs (jl-obj->str (raw-call-function docs-fn [jl-symbol]))]
                           `(def ~(with-meta (symbol sym-name)
                                    {:doc docs})
                              (julia->jvm (jl_get_global ~'module (jl_symbol ~sym-rawname))
                                          {:unrooted? true})))))))))
    (errors/throwf "Failed to find module: %s" module-name)))


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
   :jl-float-64-type :float64})


(def datatype->jl-type
  (set/map-invert jl-type->datatype))


(defn julia-eltype->datatype
  [eltype]
  (let [jl-type (get-in @julia-typemap* [:typeid->typename eltype])]
    (get jl-type->datatype jl-type :object)))


(defn julia-array->nd-descriptor
  [ary-ptr]
  (let [rank (jl_array_rank ary-ptr)
        shape (mapv #(jl_array_size ary-ptr %) (range rank))
        data (jl_array_ptr ary-ptr)
        dtype (-> (jl_array_eltype ary-ptr)
                  (julia-eltype->datatype))
        byte-width (casting/numeric-byte-width dtype)
        ;;TODO - Figure out to handle non-packed data.  This is a start, however.
        strides (->> (dims-analytics/shape-ary->strides shape)
                     (mapv #(* byte-width (long %))))]
    {:ptr (Pointer/nativeValue data)
     :elemwise-datatype dtype
     :shape shape
     :strides strides
     :julia-array ary-ptr}))


(deftype JuliaArray [^Pointer handle]
  PToJulia
  (->julia [item] handle)
  (as-julia [item] handle)
  jna/PToPtr
  (is-jna-ptr-convertible? [this] true)
  (->ptr-backing-store [this] handle)
  dtype-proto/PElemwiseDatatype
  (elemwise-datatype [this]
    (julia-eltype->datatype (jl_array_eltype handle)))
  dtype-proto/PShape
  (shape [this]
    (let [rank (jl_array_rank handle)]
      (mapv #(jl_array_size handle %) (range rank))))
  dtype-proto/PECount
  (ecount [this]
    (long (apply * (dtype-proto/shape this))))
  dtype-proto/PToTensor
  (as-tensor [this]
    (-> (julia-array->nd-descriptor handle)
        (dtt/nd-buffer-descriptor->tensor)))
  dtype-proto/PToNDBufferDesc
  (convertible-to-nd-buffer-desc? [this] true)
  (->nd-buffer-descriptor [this] (julia-array->nd-descriptor handle))
  dtype-proto/PToNativeBuffer
  (convertible-to-native-buffer? [this] true)
  (->native-buffer [this]
    (dtype-proto/->native-buffer (dtype-proto/as-tensor this)))
  Object
  (toString [this]
    (jl-obj->str handle)))


(dtype-pp/implement-tostring-print JuliaArray)



(defmethod julia->jvm "Array"
  [jl-ptr options]
  (when-not (:unrooted? options)
    (root-ptr! jl-ptr))
  (JuliaArray. jl-ptr))
