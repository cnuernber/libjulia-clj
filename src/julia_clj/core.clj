(ns julia-clj.core
  (:require [tech.jna :as jna]
            [tech.jna.base :as jna-base])
  (:import [com.sun.jna Pointer NativeLibrary]
           [julia_clj JLOptions]))


(jna/def-jna-fn "julia" jl_init__threading
  "Initialize julia interpreter"
  nil)

(jna/def-jna-fn "julia" jl_is_initialized
  "Check of the interpreter is initialized"
  Integer)

(jna/def-jna-fn "julia" jl_eval_string
  "Eval a string"
  Pointer
  [data str])

(jna/def-jna-fn "julia" jl_atexit_hook
  "Shutdown julia gracefully"
  nil
  [status int])


(defn find-julia-symbol
  ^Pointer [sym-name]
  (.getGlobalVariableAddress ^NativeLibrary (jna-base/load-library "julia")
                             sym-name))


(defn julia-options
  ^JLOptions []
  (JLOptions. (find-julia-symbol "jl_options")))


(defn disable-julia-signals!
  []
  (let [opts (julia-options)]
    (set! (.handle_signals opts) 0)
    (.writeField opts "handle_signals")))
