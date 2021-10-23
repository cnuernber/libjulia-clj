# Simple Julia Example


```console
(base) chrisn@chrisn-lt3:~/dev/cnuernber/libjulia-clj/examples/fractal$ clj
Clojure 1.10.3
(require '[julia-fractals :as jf])
nil
(require '[tech.v3.libs.buffered-image :as bufimg])
nil
(def img (jf/fractal))
#'user/img
img
#object[java.awt.image.BufferedImage 0x4f74edbf "BufferedImage@4f74edbf: type = 10 ColorModel: #pixelBits = 8 numComponents = 1 color space = java.awt.color.ICC_ColorSpace@3c1f2651 transparency = 1 has alpha = false isAlphaPre = false ByteInterleavedRaster: width = 1920 height = 1080 #numDataElements 1 dataOff[0] = 0"]
(bufimg/save! img "test.png")
true
user=>
(base) chrisn@chrisn-lt3:~/dev/cnuernber/libjulia-clj/examples/fractal$
```
