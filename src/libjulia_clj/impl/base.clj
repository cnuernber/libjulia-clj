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
            [libjulia-clj.impl.gc :as gc]
            [libjulia-clj.impl.jna :as julia-jna]
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


(defonce jvm-julia-roots* (atom nil))


(defn initialize-julia-root-map!
  []
  (errors/when-not-error
   (nil? @jvm-julia-roots*)
   "Attempt to initialize julia root map twice")
  (let [refmap (julia-jna/jl_eval_string "jvm_refs = IdDict()")
        set-index! (julia-jna/jl_get_function (julia-jna/jl_base_module) "setindex!")
        delete! (julia-jna/jl_get_function (julia-jna/jl_base_module) "delete!")]
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
         untracked-value (Pointer. native-value)
         log-level (or (:log-level options) :info)]
     (when log-level
       (log/logf log-level "Rooting address  0x%016X" native-value))
     (julia-jna/jl_call3 set-index! jvm-refs untracked-value value)
     (gc/track value (fn []
                       (when log-level
                         (log/logf log-level
                                   "Unrooting address 0x%016X"
                                   native-value))
                       (julia-jna/jl_call2 delete! jvm-refs untracked-value)))))
  ([value]
   (root-ptr! value nil)))


(defonce julia-typemap* (atom {:typeid->typename {}
                               :typename->typeid {}}))


(defn initialize-typemap!
  []
  (let [base-types (->> (julia-jna/list-julia-data-symbols)
                        (map (comp last #(s/split % #"\s+")))
                        (filter #(.endsWith ^String % "type"))
                        (map (fn [typename]
                               [(julia-jna/find-deref-julia-symbol typename)
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
      (let [^String type-str (julia-jna/jl_typeof_str item-ptr)]
        (if (.startsWith type-str "#")
          :jl-function
          type-str)))))


(defn julia-options
  ^JLOptions []
  (JLOptions. (julia-jna/find-julia-symbol "jl_options")))


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


(defonce base-module-functions* (atom nil))


(defn initialize-base-module-functions!
  []
  (let [basemod (julia-jna/base-module)]
    (reset! base-module-functions*
            {:sprint (julia-jna/jl_get_function basemod
                                                "sprint")
             :showerror (julia-jna/jl_get_function (julia-jna/jl_base_module)
                                                   "showerror")
             :catch-backtrace (julia-jna/jl_get_function (julia-jna/jl_base_module)
                                                         "catch_backtrace")}
            )))


(defonce error-handling-data* (atom nil))


(defn initialize-error-handling!
  []
  (errors/when-not-error
   (nil? @error-handling-data*)
   "Error handling initialized twice!")
  (reset!
   error-handling-data*
   {:sprint (julia-jna/jl_get_function (julia-jna/jl_base_module)
                                       "sprint")
    :showerror (julia-jna/jl_get_function (julia-jna/jl_base_module)
                                          "showerror")
    :catch-backtrace (julia-jna/jl_get_function (julia-jna/jl_base_module)
                                                "catch_backtrace")}))


(declare julia->jvm)


(defn sprint-last-error
  ^String [exc]
  (when exc
    (let [{:keys [sprint showerror catch-backtrace]} @error-handling-data*
          bt (julia-jna/jl_call0 catch-backtrace)]
      (-> (julia-jna/jl_call3 sprint showerror exc bt)
          (julia->jvm nil)))))


(defn check-last-error
  []
  (when-let [exc (julia-jna/jl_exception_occurred)]
    (errors/throwf "Julia error:\n%s" (or (sprint-last-error exc)
                                          "unable to print error"))))


(defonce julia-tostring-fns* (atom nil))

(defn initialize-generic-tostring!
  []
  (errors/when-not-error
   (nil? @julia-tostring-fns*)
    "Tostring functionality initialized twice!")
  (let [basemod (julia-jna/jl_base_module)]
    (reset! julia-tostring-fns*
            {:sprint (julia-jna/jl_get_function basemod "sprint")
             :dump (julia-jna/jl_get_function basemod "dump")
             :print (julia-jna/jl_get_function basemod "print")
             :methods (julia-jna/jl_get_function basemod "methods")
             :length (julia-jna/jl_get_function basemod "length")})))


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
