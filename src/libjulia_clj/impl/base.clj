(ns libjulia-clj.impl.base
  (:require [tech.v3.jna :as jna]
            [tech.v3.jna.base :as jna-base]
            [tech.v3.datatype :as dtype]
            [tech.v3.datatype.protocols :as dtype-proto]
            [tech.v3.datatype.errors :as errors]
            [tech.v3.datatype.casting :as casting]
            [tech.v3.datatype.pprint :as dtype-pp]
            [tech.v3.datatype.native-buffer :as native-buffer]
            [tech.v3.datatype.jna]
            [tech.v3.tensor.dimensions.analytics :as dims-analytics]
            [tech.v3.tensor :as dtt]
            [tech.v3.resource :as resource]
            [libjulia-clj.impl.gc :as gc]
            [libjulia-clj.impl.jna :as julia-jna]
            [libjulia-clj.impl.protocols :as julia-proto]
            [primitive-math :as pmath]
            [clojure.tools.logging :as log])
  (:import [com.sun.jna Pointer NativeLibrary]
           [java.nio.file Paths]
           [clojure.lang IFn Symbol Keyword])
  (:refer-clojure :exclude [struct]))


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


(defn combine-paths
  ^String [src-path & args]
  (-> (Paths/get (str src-path) ^"[java.lang.String;"
                 (into-array String
                             (map str args)))
      (.toString)))


(defonce base-module-functions* (atom nil))


(defn initialize-base-module-functions!
  []
  (let [basemod (julia-jna/jl_base_module)]
    (reset! base-module-functions*
            {:sprint (julia-jna/jl_get_function basemod "sprint")
             :showerror (julia-jna/jl_get_function basemod "showerror")
             :catch-backtrace (julia-jna/jl_get_function basemod "catch_backtrace")
             :dump (julia-jna/jl_get_function basemod "dump")
             :print (julia-jna/jl_get_function basemod "print")
             :methods (julia-jna/jl_get_function basemod "methods")
             :length (julia-jna/jl_get_function basemod "length")
             :names (julia-jna/jl_get_function basemod "names")})))


(defn sprint-last-error
  ^String [exc]
  (when exc
    (let [{:keys [sprint showerror catch-backtrace]} @base-module-functions*
          bt (julia-jna/jl_call0 catch-backtrace)]
      (-> (julia-jna/jl_call3 sprint showerror exc bt)
          (julia-proto/julia->jvm nil)))))


(defn check-last-error
  []
  (when-let [exc (julia-jna/jl_exception_occurred)]
    (errors/throwf "Julia error:\n%s" (or (sprint-last-error exc)
                                          "unable to print error"))))


(defn jl-obj->str
  ^String [jl-ptr]
  (when jl-ptr
    (let [{:keys [sprint print]} @base-module-functions*]
      (-> (julia-jna/jl_call2 sprint print jl-ptr)
          (julia-proto/julia->jvm nil)))))


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
       (log/infof "Attempting to initialize Julia at %s" julia-library-path)
       (jna-base/load-library julia-library-path)
       (reset! julia-jna/julia-library-path* julia-library-path)
       (when-not (== 1 (julia-jna/jl_is_initialized))
         ;;The JVM uses SIGSEGV signals during it's normal course of
         ;;operation.  Without disabling Julia's signal handling  this will
         ;;cause an instantaneous and unceremonious exit :-).
         (julia-jna/disable-julia-signals!)
         (julia-jna/jl_init__threading)
         (julia-jna/initialize-typemap!)
         (initialize-julia-root-map!)
         (initialize-base-module-functions!))
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
  (let [names-fn (:names @base-module-functions*)
        names-ary (julia-jna/jl_call1 names-fn module)
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
                          (julia-jna/jl_symbol_name)))
                    :string data)
        (dtype/clone))))


(declare call-function raw-call-function)


(deftype GenericJuliaObject [^Pointer handle]
  julia-proto/PToJulia
  (->julia [item] handle)
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
     julia-proto/PToJulia
     (->julia [item] ~'handle)
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
    (let [{:keys [methods length]} @base-module-functions*]
      (try
        (not= 0 (long (call-function length
                                     [(raw-call-function methods [jl-obj])])))
        (catch Throwable e false)))))


(defmethod julia-proto/julia->jvm :default
  [julia-val options]
  ;;Do not root when someone asks us not to or when
  ;;we have explicitly disabled the julia gc.
  (when-not (or (:unrooted? options)
                (== 0 (julia-jna/jl_gc_is_enabled)))
    (root-ptr! julia-val))
  (if (jl-obj-callable? julia-val)
    (CallableJuliaObject. julia-val)
    (GenericJuliaObject. julia-val)))


(extend-protocol julia-proto/PToJulia
  Byte
  (->julia [item] (julia-jna/jl_box_int8 item))
  Short
  (->julia [item] (julia-jna/jl_box_int16 item))
  Integer
  (->julia [item] (julia-jna/jl_box_int32 item))
  Long
  (->julia [item] (julia-jna/jl_box_int64 item))
  Float
  (->julia [item] (julia-jna/jl_box_float32 item))
  Double
  (->julia [item] (julia-jna/jl_box_float64 item))
  String
  (->julia [item] (julia-jna/jl_cstr_to_string item))
  Symbol
  (->julia [item] (julia-jna/jl_symbol (name item)))
  Keyword
  (->julia [item] (julia-jna/jl_symbol (name item)))
  Pointer
  (->julia [item] item))

(defmethod julia-proto/julia->jvm :jl-bool-type
  [julia-val options]
  (if (== 0 (julia-jna/jl_unbox_bool julia-val))
    false
    true))


(defmethod julia-proto/julia->jvm :jl-uint-8-type
  [julia-val options]
  (pmath/byte->ubyte (julia-jna/jl_unbox_uint8 julia-val)))

(defmethod julia-proto/julia->jvm :jl-uint-16-type
  [julia-val options]
  (pmath/short->ushort (julia-jna/jl_unbox_uint16 julia-val)))

(defmethod julia-proto/julia->jvm :jl-uint-32-type
  [julia-val options]
  (pmath/int->uint (julia-jna/jl_unbox_uint32 julia-val)))

(defmethod julia-proto/julia->jvm :jl-uint-64-type
  [julia-val options]
  (julia-jna/jl_unbox_uint64 julia-val))


(defmethod julia-proto/julia->jvm :jl-int-8-type
  [julia-val options]
  (julia-jna/jl_unbox_int8 julia-val))

(defmethod julia-proto/julia->jvm :jl-int-16-type
  [julia-val options]
  (julia-jna/jl_unbox_int16 julia-val))

(defmethod julia-proto/julia->jvm :jl-int-32-type
  [julia-val options]
  (julia-jna/jl_unbox_int32 julia-val))

(defmethod julia-proto/julia->jvm :jl-int-64-type
  [julia-val options]
  (julia-jna/jl_unbox_int64 julia-val))

(defmethod julia-proto/julia->jvm :jl-float-64-type
  [julia-val options]
  (julia-jna/jl_unbox_float64 julia-val))

(defmethod julia-proto/julia->jvm :jl-float-32-type
  [julia-val options]
  (julia-jna/jl_unbox_float32 julia-val))

(defmethod julia-proto/julia->jvm :jl-string-type
  [julia-val options]
  (julia-jna/jl_string_ptr julia-val))


(defn jvm-args->julia
  [args]
  (mapv #(if %
           (julia-proto/->julia %)
           (julia-jna/jl_nothing)) args))


(defn raw-call-function
  "Call the function.  We disable the Julia GC when marshalling arguments but
  the GC is enabled for the actual julia function call.  The result is returned
  to the user as a Pointer."
  ^Pointer [fn-handle args]
  (resource/stack-resource-context
   ;;do not GC my stuff when I am marshalling function arguments to julia
   (let [jl-args (julia-jna/with-disabled-julia-gc (jvm-args->julia args))]
     (let [retval
           (case (count jl-args)
             0 (julia-jna/jl_call0 fn-handle)
             1 (julia-jna/jl_call1 fn-handle (first jl-args))
             2 (julia-jna/jl_call2 fn-handle (first jl-args) (second jl-args))
             3 (apply julia-jna/jl_call3 fn-handle jl-args)
             (let [n-args (count args)
                   ;;This will be cleaned up when the resource stack context unwraps.
                   ptr-buf (dtype/make-container :native-heap ptr-dtype
                                                 {:resource-type :stack}
                                                 (mapv #(if %
                                                          (Pointer/nativeValue ^Pointer %)
                                                          0)
                                                       jl-args))]
               (julia-jna/jl_call fn-handle ptr-buf n-args)))]
       (check-last-error)
       retval))))


(defn call-function
  "Call a function.  The result will be marshalled back to the jvm and if necessary,
  rooted."
  ([fn-handle args options]
   (-> (raw-call-function fn-handle args)
       (julia-proto/julia->jvm options)))
  ([fn-handle args]
   (call-function fn-handle args nil)))


(defn apply-tuple-type
  ^Pointer [args & [options]]
  (resource/stack-resource-context
   (let [jl-args (julia-jna/with-disabled-julia-gc (jvm-args->julia args))
         n-args (count args)
         ptr-buf (dtype/make-container :native-heap ptr-dtype
                                       {:resource-type :stack}
                                       (mapv #(if %
                                                (Pointer/nativeValue ^Pointer %)
                                                0)
                                             jl-args))
         retval (julia-jna/jl_apply_tuple_type_v ptr-buf n-args)]
     (check-last-error)
     (julia-proto/julia->jvm retval options))))


(defn apply-type
  ^Pointer [jl-type args]
  (resource/stack-resource-context
   (let [args (julia-jna/with-disabled-julia-gc (jvm-args->julia args))
         retval
         (case (count args)
           1 (julia-jna/jl_apply_type1 jl-type (first args))
           2 (julia-jna/jl_apply_type2 jl-type (first args) (second args))
           (let [n-args (count args)
                 ptr-buf (dtype/make-container :native-heap ptr-dtype
                                               {:resource-type :stack}
                                               (mapv #(if %
                                                        (Pointer/nativeValue ^Pointer %)
                                                        0)
                                                     args))]
             (julia-jna/jl_apply_type jl-type ptr-buf n-args)))]
     (check-last-error)
     (julia-proto/julia->jvm retval nil))))


(defn struct
  ^Pointer [jl-type args]
  (resource/stack-resource-context
   (let [args (julia-jna/with-disabled-julia-gc (jvm-args->julia args))
         n-args (count args)
         ptr-buf (dtype/make-container :native-heap ptr-dtype
                                       {:resource-type :stack}
                                       (mapv #(if %
                                                (Pointer/nativeValue ^Pointer %)
                                                0)
                                             args))
         retval (julia-jna/jl_new_structv jl-type ptr-buf n-args)]
     (check-last-error)
     (julia-proto/julia->jvm retval nil))))





(dtype-pp/implement-tostring-print Pointer)


(defn module-publics
  [module]
  (->> (module-symbol-names module)
       (map (fn [sym-name]
              (let [global-sym (julia-jna/jl_get_function module sym-name)]
                (when global-sym
                  [(symbol sym-name)
                   {:symbol-type (julia-jna/jl-ptr->typename global-sym)
                    :symbol global-sym}]))))
       (remove nil?)
       (sort-by first)))


(defmacro define-module-publics
  [module-name unsafe-name-map]
  (if-let [mod (julia-jna/jl_eval_string module-name)]
    (let [publics (module-publics mod)
          docs-fn (julia-jna/jl_eval_string "Base.Docs.doc")]
      `(do
         (def ~'module (julia-jna/jl_eval_string ~module-name))
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
                              (julia-proto/julia->jvm
                               (julia-jna/jl_get_function ~'module ~sym-rawname)
                               {:unrooted? true})))))))))
    (errors/throwf "Failed to find module: %s" module-name)))


(defn julia-array->nd-descriptor
  [ary-ptr]
  (let [rank (julia-jna/jl_array_rank ary-ptr)
        shape (vec (reverse
                    (map #(julia-jna/jl_array_size ary-ptr %)
                         (range rank))))
        data (julia-jna/jl_array_ptr ary-ptr)
        dtype (-> (julia-jna/jl_array_eltype ary-ptr)
                  (julia-jna/julia-eltype->datatype))
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
  julia-proto/PToJulia
  (->julia [item] handle)
  jna/PToPtr
  (is-jna-ptr-convertible? [this] true)
  (->ptr-backing-store [this] handle)
  dtype-proto/PElemwiseDatatype
  (elemwise-datatype [this]
    (julia-jna/julia-eltype->datatype
     (julia-jna/jl_array_eltype handle)))
  dtype-proto/PShape
  (shape [this]
    (let [rank (julia-jna/jl_array_rank handle)]
      (mapv #(julia-jna/jl_array_size handle %)
            (range rank))))
  dtype-proto/PECount
  (ecount [this]
    (long (apply * (dtype-proto/shape this))))
  dtype-proto/PToTensor
  (as-tensor [this]
    (-> (julia-array->nd-descriptor handle)
        (dtt/nd-buffer-descriptor->tensor)
        (dtt/transpose (vec (reverse (range (julia-jna/jl_array_rank handle)))))))
  dtype-proto/PToNDBufferDesc
  (convertible-to-nd-buffer-desc? [this] true)
  (->nd-buffer-descriptor [this] (julia-array->nd-descriptor handle))
  dtype-proto/PToBuffer
  (convertible-to-buffer? [this] true)
  (->buffer [this] (dtype-proto/->buffer (dtt/as-tensor this)))
  Object
  (toString [this]
    (jl-obj->str handle)))


(dtype-pp/implement-tostring-print JuliaArray)


(defmethod julia-proto/julia->jvm "Array"
  [jl-ptr options]
  (when-not (:unrooted? options)
    (root-ptr! jl-ptr))
  (JuliaArray. jl-ptr))
