(ns julia-clj.main
  (:require [julia-clj.core :as jc])
  (:gen-class))


(defn -main
  [& args]
  (println "before init")
  (jc/jl_init__threading)
  (println "after init")
  (jc/jl_eval_string "println(\"test from Julia\")")
  (println "finishing up")
  (println "finished"))
