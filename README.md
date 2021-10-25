# julia-clj

[![Clojars Project](https://img.shields.io/clojars/v/com.cnuernber/libjulia-clj.svg)](https://clojars.org/cnuernber/libjulia-clj)
[![travis integration](https://travis-ci.com/cnuernber/libjulia-clj.svg?branch=master)](https://travis-ci.com/cnuernber/libjulia-clj)


* [API docs](https://cnuernber.github.io/libjulia-clj/)
* [In-Depth Example](https://github.com/cnuernber/kmeans-mnist)

## Usage

Install julia and set JULIA_HOME:

```console
wget https://julialang-s3.julialang.org/bin/linux/x64/1.5/julia-1.5.3-linux-x86_64.tar.gz \
  && tar -xvzf julia-1.5.3-linux-x86_64.tar.gz

export JULIA_HOME=$(pwd)/julia-1.5.3
```

In your repl, load the julia base namespace and initialize the system.

```clojure
user> (require '[libjulia-clj.julia :as julia])
nil
user> (julia/initialize!)
07:07:06.228 [nREPL-session-e1c7b4a4-54f4-4298-80bb-972e83b902ff] INFO libjulia-clj.impl.base - Attempting to initialize Julia at /home/chrisn/dev/cnuernber/libjulia-clj/julia-1.5.3/lib/libjulia.so
07:07:07.121 [nREPL-session-e1c7b4a4-54f4-4298-80bb-972e83b902ff] INFO tech.v3.jna.base - Library /home/chrisn/dev/cnuernber/libjulia-clj/julia-1.5.3/lib/libjulia.so found at [:system "/home/chrisn/dev/cnuernber/libjulia-clj/julia-1.5.3/lib/libjulia.so"]
:ok
user> (def ones-fn (julia/jl "Base.ones"))
#'user/ones-fn
user> (ones-fn 3 4)
[1.0 1.0 1.0 1.0; 1.0 1.0 1.0 1.0; 1.0 1.0 1.0 1.0]
user> (def julia-ary *1)
#'user/julia-ary
user> (require '[tech.v3.tensor :as dtt])
nil
user> (dtt/ensure-tensor julia-ary)
#tech.v3.tensor<float64>[3 4]
[[1.000 1.000 1.000 1.000]
 [1.000 1.000 1.000 1.000]
 [1.000 1.000 1.000 1.000]]
user> (def clj-tens *1)
#'user/clj-tens
user> (dtt/mset! clj-tens 0 25)
#tech.v3.tensor<float64>[3 4]
[[25.00 25.00 25.00 25.00]
 [1.000 1.000 1.000 1.000]
 [1.000 1.000 1.000 1.000]]
user> julia-ary
[25.0 25.0 25.0 25.0; 1.0 1.0 1.0 1.0; 1.0 1.0 1.0 1.0]
```

## Something Fun

```clojure
user> (require '[tech.v3.libs.buffered-image :as bufimg])
nil
user> (require '[tech.v3.datatype :as dtype])
nil
user> (def fract-width 1920)
(def fract-height 1080)
(def i1 0.31)
(def i2 -0.6)
(def d 11)
(def zoom-factor 0.2)
#'user/fract-width#'user/fract-height#'user/i1#'user/i2#'user/d#'user/zoom-factor
user> (def julia-code
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
#'user/julia-code
user> (def fractal-fn (julia/jl julia-code))
#'user/fractal-fn
user> (defn jl-fractal
        []
        (-> (fractal-fn i1 i2 d zoom-factor fract-width fract-height)
            (dtt/ensure-tensor)
            ;;Julia is column-major so our image comes out widthxheight
            ;;datatype is row major.
            (dtt/transpose [1 0])
            ;;The tensor library *knows* the original was transposed so transposing the result
            ;;back into row-major means the memory can be read in order and thus
            ;;the copy operation below is one large memcopy into a jvm byte array.
            (dtype/copy! (bufimg/new-image fract-height fract-width :byte-gray))))
#'user/jl-fractal
user> (jl-fractal)
#object[java.awt.image.BufferedImage 0x4d63b28f "BufferedImage@4d63b28f: type = 10 ColorModel: #pixelBits = 8 numComponents = 1 color space = java.awt.color.ICC_ColorSpace@2703464d transparency = 1 has alpha = false isAlphaPre = false ByteInterleavedRaster: width = 1920 height = 1080 #numDataElements 1 dataOff[0] = 0"]
;; Roughly 1920*1080*11*3, or 68428800 complex number operations
user> (time (def ignored (jl-fractal)))
"Elapsed time: 31.487044 msecs"
#'user/ignored
user> (bufimg/save! (jl-fractal) "julia.png")
true
```

![julia-img](topics/images/julia.png)


## License

Copyright © 2020 Chris Nuernberger

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.
