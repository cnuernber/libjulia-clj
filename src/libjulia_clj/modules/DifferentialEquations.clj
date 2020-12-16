(ns libjulia-clj.modules.DifferentialEquations
  (:require [libjulia-clj.impl.base :as base])
  (:refer-clojure :exclude [/ cond]))


(base/initialize!)


(base/define-module-publics
  "import DifferentialEquations;DifferentialEquations"
  ;;Remap names to avoid compilation errors
  {})
