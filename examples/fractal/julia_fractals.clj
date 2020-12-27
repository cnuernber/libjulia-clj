(ns julia-fractals
  (:require [tech.v3.tensor :as dtt]
            [tech.v3.datatype :as dtype]
            [tech.v3.libs.buffered-image :as bufimg]
            [libjulia-clj.julia :as julia])
  (:import [org.apache.commons.math3.complex Complex]))

(set! *unchecked-math* :warn-on-boxed)


(def fract-width 1920)
(def fract-height 1080)
(def i1 0.31)
(def i2 -0.6)
(def d 11)
(def zoom-factor 0.2)


(defn fractal
  []
  (let [icomp (Complex. i1 i2)
        ;;type out all the variables to avoid boxing
        zoom-factor (double zoom-factor)
        img-width (double fract-width)
        img-height (double fract-height)
        d (long d)
        hw (* 0.5 (double fract-width))
        hh (* 0.5 (double fract-height))
        zw (* zoom-factor img-width)
        zh (* zoom-factor img-height)]
    (-> (dtt/typed-compute-tensor
         :int64
         [img-height img-width]
         [y x]
         (loop [pos (Complex. (-> (- x hw) (/ zw)) (-> (- y hh) (/ zh)))
                c 0]
           (if (< c d)
             (recur (-> (.multiply pos pos) (.add icomp)) (inc c))
             (let [val (.abs pos)]
               (if (and (Double/isFinite val) (< val (dec d))) 255 0)))))
        (dtype/copy! (bufimg/new-image img-height img-width :byte-gray)))))


(comment

  (-> (fractal)
      (bufimg/save! "clj-fract.png"))

  (time (fractal))
  ;;202 ms
  )


(def julia-code
  "function juliaSet(i1,i2,d,zoomFactor,imgWidth,imgHeight)
    # Allocating a widthxheight matrix as our Clojure client is row-major
    matrix = Array{UInt8}(undef,imgWidth,imgHeight)
    icomp = Complex{Float64}(i1,i2)
    Threads.@threads for i in CartesianIndices(matrix)
        ## Julia has 1-based indexing...
        pos =  complex(((i[1]-1) - (0.5 * imgWidth)) / (zoomFactor * imgWidth),
                       ((i[2]-1) - (0.5 * imgHeight)) / (zoomFactor * imgHeight))

        for c in (1:d) pos = (pos * pos) + icomp end
        absval = abs(pos)
        if (absval != NaN && absval < (d-1))
            matrix[i] = 255
        else
            matrix[i] = 0
        end
    end
    return matrix
end")


(def jl-fn* (delay (do (julia/initialize!)
                       (julia/jl julia-code))))


(defn jl-fractal
  []
  (let [jl-fn @jl-fn*]
    (-> (jl-fn i1 i2 d zoom-factor fract-width fract-height)
        (dtt/ensure-tensor)
        (dtype/copy! (bufimg/new-image fract-height fract-width :byte-gray)))))


(comment

  (-> (jl-fractal)
      (bufimg/save! "jl-fract.png"))

  (time (jl-fractal))
  ;;34 ms
  )
