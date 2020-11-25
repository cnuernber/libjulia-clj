(ns libjulia-clj.impl.base
  (:require [tech.jna :as jna]
            [tech.jna.base :as jna-base]
            [tech.v3.datatype :as dtype]
            [tech.v3.datatype.native-buffer :as native-buffer]
            [tech.v3.datatype.jna :as dtype-jna]
            [clojure.java.io :as io]
            [clojure.string :as s])
  (:import [com.sun.jna Pointer NativeLibrary]
           [julia_clj JLOptions]
           [java.nio.file Paths]))


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


(defn jl_value_t
  ^Pointer [item]
  (jna/ensure-ptr item))


(defn jl_module_t
  ^Pointer [item]
  (jna/ensure-ptr item))


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
  [symbol-name (comp jna/string->ptr str)])


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


(defn jl_get_function
  "Find a julia function in a julia module"
  [module fn-name]
  (jl_get_global module (jl_symbol fn-name)))


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


(defn jl_base_module
  ^Pointer []
  (find-julia-symbol "jl_base_module"))


(defn julia-options
  ^JLOptions []
  (JLOptions. (find-julia-symbol "jl_options")))


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
       (disable-julia-signals!)
       (jl_init__threading)
       (catch Throwable e
         (throw (ex-info (format "Failed to find julia library.  Is JULIA_HOME unset?  Attempted %s"
                                 julia-library-path)
                         {:error e})))))
   :ok)
  ([]
   (initialize! nil)))
