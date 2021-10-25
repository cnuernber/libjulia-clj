# Simple Julia Example

Ensure you have julia installed and JULIA_HOME exported to point to your
installation directory.  If you are on a linux system, from the top level
run:

```console
(base) chrisn@chrisn-lt3:~/dev/cnuernber/libjulia-clj/examples/fractal$ cd ../../
(base) chrisn@chrisn-lt3:~/dev/cnuernber/libjulia-clj$ source scripts/activate-julia
(base) chrisn@chrisn-lt3:~/dev/cnuernber/libjulia-clj$ cd examples/fractal/
(base) chrisn@chrisn-lt3:~/dev/cnuernber/libjulia-clj/examples/fractal$
```


```console

Downloading: com/cnuernber/libjulia-clj/1.000-beta-1/libjulia-clj-1.000-beta-1.pom from clojars
Downloading: com/cnuernber/libjulia-clj/1.000-beta-1/libjulia-clj-1.000-beta-1.jar from clojars
Clojure 1.10.3
(require '[julia-fractals :as jf])
nil
(jf/jl-fractal)
Oct 25, 2021 12:14:59 PM clojure.tools.logging$eval3221$fn__3224 invoke
INFO: Reference thread starting
Oct 25, 2021 12:14:59 PM clojure.tools.logging$eval3221$fn__3224 invoke
INFO: julia library arguments: ["--handle-signals=no" "--threads" "1"]
#object[java.awt.image.BufferedImage 0x31ee5a9 "BufferedImage@31ee5a9: type = 10 ColorModel: #pixelBits = 8 numComponents = 1 color space = java.awt.color.ICC_ColorSpace@67add4c9 transparency = 1 has alpha = false isAlphaPre = false ByteInterleavedRaster: width = 1920 height = 1080 #numDataElements 1 dataOff[0] = 0"]
(def img *1)
#'user/img
(require '[tech.v3.libs.buffered-image :as bufimg])
nil
(bufimg/save! img "test.png")
true
```
