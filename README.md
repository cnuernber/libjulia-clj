# julia-clj

[![Clojars Project](https://img.shields.io/clojars/v/cnuernber/libjulia-clj.svg)](https://clojars.org/cnuernber/libjulia-clj)
[![travis integration](https://travis-ci.com/cnuernber/libjulia-clj.svg?branch=master)](https://travis-ci.com/cnuernber/libjulia-clj)


* [API docs](https://cnuernber.github.io/libjulia-clj/)

## Usage

Install julia and set JULIA_HOME:

```console
wget https://julialang-s3.julialang.org/bin/linux/x64/1.5/julia-1.5.3-linux-x86_64.tar.gz \
  && tar -xvzf julia-1.5.3-linux-x86_64.tar.gz

export JULIA_HOME=$(pwd)/julia-1.5.3
```

In your repl, load the julia base namespace (it calls initialize! automatically):

```clojure
user> (require '[libjulia-clj.modules.Base :as Base])
;;Long pause, loading metadata for all of Base takes a while.  Perhaps better to write a concrete namespace....
Nov 26, 2020 12:26:01 PM clojure.tools.logging$eval8218$fn__8221 invoke
INFO: Library /home/chrisn/dev/cnuernber/libjulia-clj/julia-1.5.3/lib/libjulia.so found at [:system "/home/chrisn/dev/cnuernber/libjulia-clj/julia-1.5.3/lib/libjulia.so"]
nil
user> (Base/ones 3 4)
Nov 26, 2020 12:26:09 PM clojure.tools.logging$eval8218$fn__8221 invoke
INFO: Reference thread starting
[1.0 1.0 1.0 1.0; 1.0 1.0 1.0 1.0; 1.0 1.0 1.0 1.0]
user> (require '[tech.v3.tensor :as dtt])
nil
user> ;;zero-copy...
user> (dtt/as-tensor (Base/ones 3 4))
#tech.v3.tensor<float64>[3 4]
[[1.000 1.000 1.000 1.000]
 [1.000 1.000 1.000 1.000]
 [1.000 1.000 1.000 1.000]]
```


## License

Copyright © 2020 Chris Nuernberger

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.
