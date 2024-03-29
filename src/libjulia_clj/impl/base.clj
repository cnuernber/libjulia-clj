(ns libjulia-clj.impl.base
  (:require [tech.v3.datatype :as dtype]
            [tech.v3.datatype.ffi :as dt-ffi]
            [tech.v3.datatype.ffi.size-t :as ffi-size-t]
            [tech.v3.datatype.ffi.ptr-value :as ffi-ptr-value]
            [tech.v3.datatype.protocols :as dtype-proto]
            [tech.v3.datatype.errors :as errors]
            [tech.v3.datatype.casting :as casting]
            [tech.v3.datatype.pprint :as dtype-pp]
            [tech.v3.datatype.native-buffer :as native-buffer]
            [tech.v3.tensor.dimensions.analytics :as dims-analytics]
            [tech.v3.tensor :as dtt]
            [tech.v3.resource :as resource]
            [libjulia-clj.impl.gc :as gc]
            [libjulia-clj.impl.ffi :as julia-ffi]
            [libjulia-clj.impl.protocols :as julia-proto]
            [com.github.ztellman.primitive-math :as pmath]
            [clojure.tools.logging :as log])
  (:import [tech.v3.datatype.ffi Pointer]
           [java.nio.file Paths]
           [java.util Map Iterator NoSuchElementException]
           [clojure.lang IFn Symbol Keyword])
  (:refer-clojure :exclude [struct]))


(set! *warn-on-reflection* true)


(defonce jvm-julia-roots* (atom nil))


(declare call-function raw-call-function call-function-kw)


(defn initialize-julia-root-map!
  []
  (errors/when-not-error
   (nil? @jvm-julia-roots*)
   "Attempt to initialize julia root map twice")
  (let [refmap (julia-ffi/jl_eval_string "jvm_refs = IdDict()")
        set-index! (julia-ffi/jl_get_function (julia-ffi/jl_base_module) "setindex!")
        delete! (julia-ffi/jl_get_function (julia-ffi/jl_base_module) "delete!")
        haskey (julia-ffi/jl_get_function (julia-ffi/jl_base_module) "haskey")]
    (reset! jvm-julia-roots*
            {:jvm-refs refmap
             :set-index! set-index!
             :delete! delete!
             :haskey haskey})))

(defonce julia-gc-root-log-level* (atom nil))

(defn root-ptr!
  "Root a pointer and set a GC hook that will unroot it when the time comes.
  Defaults to :auto track type"
  ([^Pointer value options]
   (when-not (or (:unrooted? options)
                 (== 0 (julia-ffi/jl_gc_is_enabled)))
     (let [{:keys [jvm-refs set-index! delete! haskey]} @jvm-julia-roots*
           native-value (.address value)
           untracked-value (Pointer. native-value)
           ;;Eventually we will turn off default logging...
           log-level (:log-level options @julia-gc-root-log-level*)]
       ;;We do not root pointers twice; that could cause a crash when
       ;;dereferencing
       (if-not (julia-proto/julia->jvm
                (julia-ffi/jl_call2 haskey jvm-refs untracked-value)
                {})
         (do
           (when log-level
             (log/logf log-level "Rooting address  0x%016X - %s"
                       native-value (julia-ffi/jl-ptr->typename value)))
           (julia-ffi/jl_call3 set-index! jvm-refs untracked-value value)
           (gc/track value (fn []
                             (when log-level
                               (log/logf log-level
                                         "Unrooting address 0x%016X"
                                         native-value))
                             (julia-ffi/jl_call2 delete! jvm-refs untracked-value))))
         ;;already rooted
         value))))
  ([value]
   (root-ptr! value nil)))


(defn combine-paths
  ^String [src-path & args]
  (-> (Paths/get (str src-path) ^"[java.lang.String;"
                 (into-array String
                             (map str args)))
      (.toString)))


(defonce module-functions* (atom nil))


(defn initialize-module-functions!
  []
  (let [basemod (julia-ffi/jl_base_module)
        coremod (julia-ffi/jl_core_module)]
    (reset! module-functions*
            {:sprint (julia-ffi/jl_get_function basemod "sprint")
             :showerror (julia-ffi/jl_get_function basemod "showerror")
             :catch-backtrace (julia-ffi/jl_get_function basemod "catch_backtrace")
             :dump (julia-ffi/jl_get_function basemod "dump")
             :print (julia-ffi/jl_get_function basemod "print")
             :methods (julia-ffi/jl_get_function basemod "methods")
             :length (julia-ffi/jl_get_function basemod "length")
             :names (julia-ffi/jl_get_function basemod "names")
             :kwfunc (julia-ffi/jl_get_function coremod "kwfunc")
             :isempty (julia-ffi/jl_get_function basemod "isempty")
             :setindex! (julia-ffi/jl_get_function basemod "setindex!")
             :getindex (julia-ffi/jl_get_function basemod "getindex")
             :fieldnames (julia-ffi/jl_get_function basemod "fieldnames")
             :getfield (julia-ffi/jl_get_function basemod "getfield")
             :keys (julia-ffi/jl_get_function basemod "keys")
             :values (julia-ffi/jl_get_function basemod "values")
             :haskey (julia-ffi/jl_get_function basemod "haskey")
             :get (julia-ffi/jl_get_function basemod "get")
             :append! (julia-ffi/jl_get_function basemod "append!")
             :delete! (julia-ffi/jl_get_function basemod "delete!")
             :iterate (julia-ffi/jl_get_function basemod "iterate")
             :pairs (julia-ffi/jl_get_function basemod "pairs")
             :eltype (julia-ffi/jl_get_function basemod "eltype")})))


(defn module-fn
  [fn-name & args]
  (if-let [fn-val (get @module-functions* fn-name)]
    (call-function fn-val args)
    (errors/throwf "Failed to find module function %s" fn-name)))


(defn sprint-last-error
  ^String [exc]
  (when exc
    (let [{:keys [sprint showerror catch-backtrace]} @module-functions*
          bt (julia-ffi/jl_call0 catch-backtrace)]
      (-> (julia-ffi/jl_call3 sprint showerror exc bt)
          (julia-proto/julia->jvm nil)))))


(defn check-last-error
  []
  (when-let [exc (julia-ffi/jl_exception_occurred)]
    (errors/throwf "Julia error:\n%s" (or (sprint-last-error exc)
                                          "unable to print error"))))


(defn jl-obj->str
  ^String [jl-ptr]
  (when jl-ptr
    (let [{:keys [sprint print]} @module-functions*]
      (-> (julia-ffi/jl_call2 sprint print jl-ptr)
          (julia-proto/julia->jvm nil)))))


(defn windows-os?
  []
  (.contains (.toLowerCase (System/getProperty "os.name"))
             "windows"))


(defn initialize!
  "Initialize julia optionally providing an explicit path which will override
  the julia library search mechanism.

  Currently the search mechanism is:

  [user-path]->JULIA_HOME->\"julia\"

  Returns :ok on success else exception.

  Options:

  * `:julia-home` - Explicitly declare the env var julia-home.
  * `:n-threads` - Set to -1 to set to n-cpus.  Defaults to nil which means single threaded
     unless the JULIA_NUM_THREADS environment variable is set.  Note that this has implications
     for application stability - see the signals.md topic.
  * `:signals-enabled?` - Users do not usually need to set this.  This allows users to disable
     all of Julia's signal handling most likely leading to a crash.  See the signals.md topic."
  ([{:keys [julia-library-path]
     :as options}]
   (julia-ffi/initialize! options)
   (reset! julia-ffi/julia-library-path* julia-library-path)
   (when-not (== 1 (julia-ffi/jl_is_initialized))
     ;;The JVM uses SIGSEGV signals during it's normal course of
     ;;operation.  Without disabling Julia's signal handling  this will
     ;;cause an instantaneous and unceremonious exit :-).
     (julia-ffi/init-process-options options)
     (julia-ffi/jl_init__threading)
     (julia-ffi/initialize-type-map!)
     (initialize-julia-root-map!)
     (initialize-module-functions!))
   :ok)
  ([]
   (initialize! nil)))


(def ptr-dtype (ffi-size-t/lower-ptr-type :pointer))


(defn module-symbol-names
  [module]
  (let [names-fn (:names @module-functions*)
        names-ary (julia-ffi/jl_call1 names-fn module)
        ary-data (native-buffer/wrap-address (ffi-ptr-value/ptr-value names-ary)
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
                          (julia-ffi/jl_symbol_name)))
                    :string data)
        (dtype/clone))))


(deftype GenericJuliaObject [^Pointer handle gc-obj]
  julia-proto/PToJulia
  (->julia [item] handle)
  dt-ffi/PToPointer
  (convertible-to-pointer? [this] true)
  (->pointer [this] handle)
  Object
  (toString [this]
    (jl-obj->str handle)))

(dtype-pp/implement-tostring-print GenericJuliaObject)


(defn args->pos-kw-args
  "Utility function that, given a list of arguments, separates them
  into positional and keyword arguments.  Throws an exception if the
  keyword argument is not followed by any more arguments."
  [arglist]
  (loop [args arglist
         pos-args []
         kw-args nil
         found-kw? false]
    (if-not (seq args)
      [pos-args kw-args]
      (let [arg (first args)
            [pos-args kw-args args found-kw?]
            (if (keyword? arg)
              (if-not (seq (rest args))
                (throw (Exception.
                        (format "Keyword arguments must be followed by another arg: %s"
                                (str arglist))))
                [pos-args (assoc kw-args arg (first (rest args)))
                 (drop 2 args) true])
              (if found-kw?
                (throw (Exception.
                        (format "Positional arguments are not allowed after keyword arguments: %s"
                                arglist)))
                [(conj pos-args (first args))
                 kw-args
                 (rest args) found-kw?]))]
        (recur args pos-args kw-args found-kw?)))))


(defmacro ^:private impl-callable-julia-object
  []
  `(deftype ~'CallableJuliaObject [~'handle ~'kw-fn-handle ~'gc-obj]
     julia-proto/PToJulia
     (->julia [item] ~'handle)
     julia-proto/PJuliaKWFn
     (kw-fn [item] ~'kw-fn-handle)
     dt-ffi/PToPointer
     (convertible-to-pointer? [this#] true)
     (->pointer [this#] ~'handle)
     Object
     (toString [this]
       (jl-obj->str ~'handle))
     IFn
     ~@(->> (range 16)
         (map (fn [idx]
                (let [argsyms (->> (range idx)
                                   (mapv (fn [arg-idx]
                                           (symbol (str "arg-" arg-idx)))))]
                  `(invoke ~(vec (concat ['this] argsyms))
                           (let [[pos-args# kw-args#] (args->pos-kw-args ~argsyms)]
                             (if (nil? kw-args#)
                               (call-function ~'handle ~argsyms nil)
                               (call-function-kw ~'handle pos-args# kw-args#))))))))
     (applyTo [this# argseq#]
       (let [[pos-args# kw-args#] (args->pos-kw-args argseq#)]
         (if (nil? kw-args#)
           (call-function ~'handle argseq# nil)
           (call-function-kw ~'kw-fn-handle ~'handle pos-args# kw-args#))))))


(impl-callable-julia-object)


(dtype-pp/implement-tostring-print CallableJuliaObject)

(defn jl-obj-callable?
  [jl-obj]
  (when jl-obj
    (let [{:keys [methods length]} @module-functions*]
      (try
        (not= 0 (long (call-function length
                                     [(raw-call-function methods [jl-obj])])))
        (catch Throwable e false)))))


(defn kw-fn
  ([jl-fn options]
   (let [{:keys [kwfunc]} @module-functions*]
     (call-function kwfunc [jl-fn] options)))
  ([jl-fn]
   (kw-fn jl-fn nil)))


(extend-type Object
  julia-proto/PJuliaKWFn
  (kw-fn [item]
    (kw-fn item)))


(defmethod julia-proto/julia->jvm :default
  [julia-val options]
  (when julia-val
    ;;Do not root when someone asks us not to or when
    ;;we have explicitly disabled the julia gc.
    (root-ptr! julia-val options)
    (if (jl-obj-callable? julia-val)
      (CallableJuliaObject. julia-val (kw-fn julia-val {:unrooted? true})
                            (:gc-obj options))
      (GenericJuliaObject. julia-val
                           (:gc-obj options)))))


(extend-protocol julia-proto/PToJulia
  Boolean
  (->julia [item] (julia-ffi/jl_box_bool (if item 1 0)))
  Byte
  (->julia [item] (julia-ffi/jl_box_int8 item))
  Short
  (->julia [item] (julia-ffi/jl_box_int16 item))
  Integer
  (->julia [item] (julia-ffi/jl_box_int32 item))
  Long
  (->julia [item] (julia-ffi/jl_box_int64 item))
  Float
  (->julia [item] (julia-ffi/jl_box_float32 item))
  Double
  (->julia [item] (julia-ffi/jl_box_float64 item))
  String
  (->julia [item] (julia-ffi/jl_cstr_to_string item))
  Symbol
  (->julia [item] (julia-ffi/jl_symbol (name item)))
  Keyword
  (->julia [item] (julia-ffi/jl_symbol (name item)))
  Pointer
  (->julia [item] item))

(defmethod julia-proto/julia->jvm :boolean
  [julia-val options]
  (if (== 0 (julia-ffi/jl_unbox_bool julia-val))
    false
    true))


(defmethod julia-proto/julia->jvm :uint8
  [julia-val options]
  (pmath/byte->ubyte (julia-ffi/jl_unbox_uint8 julia-val)))

(defmethod julia-proto/julia->jvm :uint16
  [julia-val options]
  (pmath/short->ushort (julia-ffi/jl_unbox_uint16 julia-val)))

(defmethod julia-proto/julia->jvm :uint32
  [julia-val options]
  (pmath/int->uint (julia-ffi/jl_unbox_uint32 julia-val)))

(defmethod julia-proto/julia->jvm :uint64
  [julia-val options]
  (julia-ffi/jl_unbox_uint64 julia-val))


(defmethod julia-proto/julia->jvm :int8
  [julia-val options]
  (julia-ffi/jl_unbox_int8 julia-val))

(defmethod julia-proto/julia->jvm :int16
  [julia-val options]
  (julia-ffi/jl_unbox_int16 julia-val))

(defmethod julia-proto/julia->jvm :int32
  [julia-val options]
  (julia-ffi/jl_unbox_int32 julia-val))

(defmethod julia-proto/julia->jvm :int64
  [julia-val options]
  (julia-ffi/jl_unbox_int64 julia-val))

(defmethod julia-proto/julia->jvm :float64
  [julia-val options]
  (julia-ffi/jl_unbox_float64 julia-val))

(defmethod julia-proto/julia->jvm :float32
  [julia-val options]
  (julia-ffi/jl_unbox_float32 julia-val))

(defmethod julia-proto/julia->jvm :string
  [julia-val options]
  (julia-ffi/jl_string_ptr julia-val))

(defmethod julia-proto/julia->jvm :jl-nothing-type
  [julia-val options]
  nil)


(defn jvm-args->julia
  [args]
  (mapv #(if %
           (julia-proto/->julia %)
           (julia-ffi/jl_nothing)) args))


#_(defn lookup-julia-type
  "Lookup a julia type from a clojure type keyword."
  [clj-type-kwd]
  (if-let [retval (get-in @julia-ffi/julia-typemap* [:typename->typeid clj-type-kwd])]
    (julia-proto/julia->jvm retval {:unrooted? true})
    (errors/throwf "Failed to find julia type %s" clj-type-kwd)))


(defn jvm-args->julia-types
  [args]
  (mapv #(if %
           (julia-proto/->julia %)
           (julia-ffi/jl_nothing))
        args))


(defn jvm-args->julia-symbols
  [args]
  (mapv #(cond (string? %) (julia-ffi/jl_symbol %)
               (or (keyword? %) (symbol? %)) (julia-proto/->julia %)
               :else (errors/throwf "%s is not convertible to a julia symbol" %))
        args))


(defn raw-call-function
  "Call the function.  We disable the Julia GC when marshalling arguments but
  the GC is enabled for the actual julia function call.  The result is returned
  to the user as a Pointer."
  ^Pointer [fn-handle args]
  (resource/stack-resource-context
   ;;do not GC my stuff when I am marshalling function arguments to julia
   (let [jl-args (julia-ffi/with-disabled-julia-gc (jvm-args->julia args))]
     (let [retval
           (case (count jl-args)
             0 (julia-ffi/jl_call0 fn-handle)
             1 (julia-ffi/jl_call1 fn-handle (first jl-args))
             2 (julia-ffi/jl_call2 fn-handle (first jl-args) (second jl-args))
             3 (apply julia-ffi/jl_call3 fn-handle jl-args)
             (let [n-args (count args)
                   ;;This will be cleaned up when the resource stack context unwraps.
                   ptr-buf (dtype/make-container :native-heap ptr-dtype
                                                 {:resource-type :stack}
                                                 (mapv #(if %
                                                          (ffi-ptr-value/ptr-value %)
                                                          0)
                                                       jl-args))]
               (julia-ffi/jl_call fn-handle ptr-buf n-args)))]
       (check-last-error)
       retval))))


(defn call-function
  "Call a function.  The result will be marshalled back to the jvm and if necessary,
  rooted.  This method is normally not necessary but useful if you would like to
  use keywords in your argument list or specify to avoid rooting the result."
  ([fn-handle args options]
   (let [retval (raw-call-function fn-handle args)]
     (julia-proto/julia->jvm retval options)))
  ([fn-handle args]
   (call-function fn-handle args nil)))

(defn apply-tuple-type
  "Create a new Julia tuple type from a sequence of types."
  ^Pointer [args & [options]]
  (resource/stack-resource-context
   (let [jl-args (julia-ffi/with-disabled-julia-gc (jvm-args->julia-types args))
         n-args (count args)
         ptr-buf (dtype/make-container :native-heap ptr-dtype
                                       {:resource-type :stack}
                                       (mapv #(if %
                                                (ffi-ptr-value/ptr-value %)
                                                0)
                                             jl-args))
         retval (julia-ffi/jl_apply_tuple_type_v ptr-buf n-args)]
     (check-last-error)
     (julia-proto/julia->jvm retval options))))


(defn apply-type
  "Create a new Julia type from an existing type and a sequence of other
  types."
  ^Pointer [jl-type args]
  (resource/stack-resource-context
   (let [args (julia-ffi/with-disabled-julia-gc (jvm-args->julia-types args))
         retval
         (case (count args)
           1 (julia-ffi/jl_apply_type1 jl-type (first args))
           2 (julia-ffi/jl_apply_type2 jl-type (first args) (second args))
           (let [n-args (count args)
                 ptr-buf (dtype/make-container
                          :native-heap ptr-dtype
                          {:resource-type :stack}
                          (mapv #(if %
                                   (ffi-ptr-value/ptr-value %)
                                   0)
                                args))]
             (julia-ffi/jl_apply_type jl-type ptr-buf n-args)))]
     (check-last-error)
     (julia-proto/julia->jvm retval nil))))


(defn struct
  "Instantiate a Julia struct type (tuple, NamedTuple, etc).  Use this after
  apply-tuple-type in order to create an instance of your new type."
  ^Pointer [jl-type args]
  (resource/stack-resource-context
   (let [args (julia-ffi/with-disabled-julia-gc (jvm-args->julia args))
         n-args (count args)
         ptr-buf (dtype/make-container :native-heap ptr-dtype
                                       {:resource-type :stack}
                                       (mapv #(if %
                                                (ffi-ptr-value/ptr-value %)
                                                0)
                                             args))
         retval (julia-ffi/jl_new_structv jl-type ptr-buf n-args)]
     (check-last-error)
     (julia-proto/julia->jvm retval nil))))


(defn tuple
  "Create a julia tuple from a set of arguments.  The tuple type will be the
  same datatype as the argument types.

  This function converts the arguments to julia, calls `apply-tuple-type`
  on the resulting argument types, and then instantiates an instance of
  the given newly created tuple type."
  [args]
  (let [[jl-args tuple-type]
        (julia-ffi/with-disabled-julia-gc
          (let [jl-args (jvm-args->julia args)]
            [jl-args
             (apply-tuple-type (map julia-ffi/jl_typeof jl-args))]))]
    (struct tuple-type jl-args)))


(defn named-tuple
  "Create a julia named tuple from a map of values.  The keys of the map must be
  keywords or symbols.  A new named tuple type will be created and instantiated."
  ([value-map]
   (let [generic-nt-type (julia-ffi/lookup-library-type :jl-namedtuple-type)
         [jl-values nt-type]
         (julia-ffi/with-disabled-julia-gc
           (let [item-keys (tuple (jvm-args->julia-symbols (keys value-map)))
                 map-vals (vals value-map)
                 jl-values (jvm-args->julia map-vals)
                 item-type-tuple (apply-tuple-type (map julia-ffi/jl_typeof jl-values))
                 nt-type (apply-type generic-nt-type [item-keys item-type-tuple])]
             [jl-values nt-type]))]
     (check-last-error)
     ;;And now, with gc (potentially) enabled, attempt to create the struct
     (struct nt-type jl-values)))
  ([]
   (named-tuple nil)))


(defn kw-args->named-tuple
  [kw-args]
  (if (instance? Map kw-args)
    (named-tuple kw-args)
    (julia-proto/->julia kw-args)))


(defn call-function-kw
  "Call a julia function and pass in keyword arguments.  This is useful if you
  would like to specify the arglist and kw-argmap.  Normally this is done for
  you."
  ([fn-handle args kw-args options]
   (call-function (julia-proto/kw-fn fn-handle)
                  (concat [(julia-ffi/with-disabled-julia-gc
                             (kw-args->named-tuple kw-args))
                           fn-handle]
                          args)
                  options))
  ([fn-handle args kw-args]
   (call-function-kw fn-handle args kw-args nil))
  ([fn-handle args]
   (call-function-kw fn-handle args nil nil)))


(dtype-pp/implement-tostring-print Pointer)


(defn module-publics
  [module]
  (->> (module-symbol-names module)
       (map (fn [sym-name]
              (let [global-sym (julia-ffi/jl_get_function module sym-name)]
                (when global-sym
                  [(symbol sym-name)
                   {:symbol-type (julia-ffi/jl-ptr->typename global-sym)
                    :symbol global-sym}]))))
       (remove nil?)
       (sort-by first)))


(defmacro define-module-publics
  [module-name unsafe-name-map]
  (if-let [mod (julia-ffi/jl_eval_string module-name)]
    (let [publics (module-publics mod)
          docs-fn (julia-ffi/jl_eval_string "Base.Docs.doc")]
      `(do
         (def ~'module (julia-ffi/jl_eval_string ~module-name))
         ~@(->> publics
                (map (fn [[sym {jl-symbol :symbol}]]
                       (when jl-symbol
                         (try
                           (let [sym-name (name sym)
                                 sym-rawname sym-name
                                 sym-name (.replace ^String sym-name "@" "AT")
                                 sym-name (get unsafe-name-map sym-name sym-name)
                                 docs (jl-obj->str (raw-call-function docs-fn [jl-symbol]))]
                             `(def ~(with-meta (symbol sym-name)
                                      {:doc docs})
                                (try
                                  (julia-proto/julia->jvm
                                   (julia-ffi/jl_get_function ~'module ~sym-rawname)
                                   {:unrooted? true})
                                  (catch Exception e#
                                    (log/warnf e# "Julia symbol %s(%s) will be unavailable"
                                               ~sym-name ~sym-rawname)))))
                           (catch Exception e
                             (log/warnf e "Julia symbol %s will unavailable" (name sym)))))))
                (remove nil?))))
    (errors/throwf "Failed to find module: %s" module-name)))


(defn julia-array->nd-descriptor
  [ary-ptr]
  (let [rank (julia-ffi/jl_array_rank ary-ptr)
        shape (vec (reverse
                    (map #(julia-ffi/jl_array_size ary-ptr %)
                         (range rank))))
        data (julia-ffi/jl_array_ptr ary-ptr)
        dtype (-> (julia-ffi/jl_array_eltype ary-ptr)
                  (julia-ffi/julia-eltype->datatype))
        byte-width (casting/numeric-byte-width dtype)
        ;;TODO - Figure out to handle non-packed data.  This is a start, however.
        strides (->> (dims-analytics/shape-ary->strides shape)
                     (mapv #(* byte-width (long %))))]
    {:ptr (ffi-ptr-value/ptr-value data)
     :elemwise-datatype dtype
     :shape shape
     :strides strides
     :julia-array ary-ptr}))


(deftype JuliaArray [^Pointer handle]
  julia-proto/PToJulia
  (->julia [item] handle)
  dt-ffi/PToPointer
  (convertible-to-pointer? [item] true)
  (->pointer [this] handle)
  dtype-proto/PElemwiseDatatype
  (elemwise-datatype [this]
    (-> (julia-ffi/jl_array_eltype handle)
        (julia-ffi/julia-eltype->datatype)))
  dtype-proto/PShape
  (shape [this]
    (let [rank (julia-ffi/jl_array_rank handle)]
      (mapv #(julia-ffi/jl_array_size handle %)
            (reverse (range rank)))))
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
  dtype-proto/PToBuffer
  (convertible-to-buffer? [this] true)
  (->buffer [this] (dtype-proto/->buffer (dtt/as-tensor this)))
  dtype-proto/PToNativeBuffer
  (convertible-to-native-buffer? [this] true)
  (->native-buffer [this] (-> (dtype-proto/as-tensor this)
                              (dtype-proto/->native-buffer)))
  Object
  (toString [this]
    (jl-obj->str handle)))


(dtype-pp/implement-tostring-print JuliaArray)


(defn- wrap-array
  [jl-ptr options]
  (root-ptr! jl-ptr options)
  (JuliaArray. jl-ptr))


(defmethod julia-proto/julia->jvm "Array" [jl-ptr options]
  (wrap-array jl-ptr options))

(defmethod julia-proto/julia->jvm :jl-array-int-64-type [jl-ptr options]
  (wrap-array jl-ptr options))

(defmethod julia-proto/julia->jvm :jl-array-uint-64-type [jl-ptr options]
  (wrap-array jl-ptr options))

(defmethod julia-proto/julia->jvm :jl-array-int-32-type [jl-ptr options]
  (wrap-array jl-ptr options))

(defmethod julia-proto/julia->jvm :jl-array-uint-32-type [jl-ptr options]
  (wrap-array jl-ptr options))

(defmethod julia-proto/julia->jvm :jl-array-int-16-type [jl-ptr options]
  (wrap-array jl-ptr options))

(defmethod julia-proto/julia->jvm :jl-array-uint-16-type [jl-ptr options]
  (wrap-array jl-ptr options))

(defmethod julia-proto/julia->jvm :jl-array-int-8-type [jl-ptr options]
  (wrap-array jl-ptr options))

(defmethod julia-proto/julia->jvm :jl-array-uint-8-type [jl-ptr options]
  (wrap-array jl-ptr options))


(deftype JLIterator [iter
                     ^:unsynchronized-mutable iter-state
                     ^:unsynchronized-mutable last-value]
  Iterator
  (hasNext [this]
    (boolean (not (nil? iter-state))))
  (next [this]
    (when-not iter-state
      (throw (NoSuchElementException.)))
    (let [next-tuple (module-fn :iterate iter iter-state)
          retval last-value]
      (if next-tuple
        (do
          (set! last-value (module-fn :getindex next-tuple 1))
          (set! iter-state (module-fn :getindex next-tuple 2)))
        (do
          (set! last-value nil)
          (set! iter-state nil)))
      retval)))


(defn julia-obj->iterable
  ^Iterable [jl-obj]
  (reify Iterable
    (iterator [this]
      (let [first-tuple (module-fn :iterate jl-obj)]
        (if first-tuple
          (JLIterator. jl-obj
                       (module-fn :getindex first-tuple 2)
                       (module-fn :getindex first-tuple 1))
          (JLIterator. jl-obj nil nil))))))

(def comp-iface-def (dt-ffi/define-foreign-interface :int32 [:pointer :pointer]))


(defn fn->jl
  "Convert a clojure function to a Julia void-ptr."
  ([clj-fn {:keys [no-coerce?] :as options}]
   (errors/when-not-errorf (instance? IFn clj-fn)
     "Item %s is not an instance of IFn"
     clj-fn)
   (let [inst
         (dt-ffi/instantiate-foreign-interface
          comp-iface-def
          (if no-coerce?
            clj-fn
            (fn [jl-args]
              (try
                (let [retval (apply clj-fn (julia-proto/julia->jvm
                                            jl-args options))]
                  ;;Explicit nil check so we can return booleans.
                  (if (nil? retval)
                    (julia-ffi/jl_nothing)
                    (julia-proto/->julia retval)))
                (catch Throwable e
                  (log/warnf e "Clojure fn errored during julia call")
                  (julia-ffi/jl_nothing))))))

         fn-ptr (dt-ffi/foreign-interface-instance->c comp-iface-def inst)]
     (julia-proto/julia->jvm (julia-ffi/jl_box_voidpointer fn-ptr)
                             {:gc-obj [clj-fn fn-ptr]})))
  ([clj-fn]
   (fn->jl clj-fn nil)))


(def fn-wrapper* (delay (->
                         (julia-ffi/jl_eval_string "function(fn_ptr::Ptr{Nothing}) return function(a...) return ccall(fn_ptr, Any, (Any,), a) end end")
                         (julia-proto/julia->jvm nil))))

;;Object default protocol implementation
(extend-type Object
  julia-proto/PToJulia
  (->julia [item]
    (if (instance? IFn item)
      (let [jl-fn (fn->jl item)
            ^CallableJuliaObject wrapped-fn (@fn-wrapper* jl-fn)]
        (CallableJuliaObject. (.handle wrapped-fn) (.kw-fn-handle wrapped-fn)
                              [jl-fn (.gc-obj wrapped-fn)]))
      (errors/throwf "Item %s is not convertible to julia" item))))
