(ns libjulia-clj.modules.LinearAlgebra
  (:require [libjulia-clj.impl.base :as base])
  (:refer-clojure :exclude [/ cond]))


(base/initialize!)


(base/define-module-publics
  "import LinearAlgebra;LinearAlgebra"
  ;;Remap names to avoid compilation errors
  {})
