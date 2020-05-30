(ns julia-clj.core
  (:require [tech.jna :as jna])
  (:import [com.sun.jna Pointer]))


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
