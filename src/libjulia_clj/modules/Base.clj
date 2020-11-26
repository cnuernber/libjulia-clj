(ns libjulia-clj.modules.Base
  (:require [libjulia-clj.impl.base :as base])
  (:refer-clojure :exclude [* + - < <= == > >=
                            cat conj conj! count denominator empty first filter float flush gensym hash
                            keys last map max merge iterate macroexpand methods min mod numerator
                            peek pop! print println rand range rationalize read reduce rem repeat
                            replace reverse sort time vec get identity]))


(base/define-module-publics
  "Base"
  ;;Remap names to avoid so compilation errors
  {"def" "jl-def"
   "'" "quote"
   ":" "colon"
   "/" "div"
   "//" "divdiv"
   "div" "divv"
   "Enum" "jl-Enum"})
