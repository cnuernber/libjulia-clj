(ns libjulia-clj.modules.Core
  (:require [libjulia-clj.impl.base :as base])
  (:refer-clojure :exclude [eval]))


(base/initialize!)


(base/define-module-publics
  "Core"
  {"AssertionError" "jl-AssertionError"
   "Exception" "jl-Exception"
   "Integer" "jl-Integer"
   "Number" "jl-Number"
   "OutOfMemoryError" "jl-OutOfMemoryError"
   "StackOverflowError" "jl-StackOverflowError"
   "String" "jl-String"})
