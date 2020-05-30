# julia-clj

Use julia via jna.  Currently any call to initialize will cause a crash to happen
sooner or later so we are still at the first step.

## Usage

In one terminal type:
```console
scripts/run-docker
```

Then using emacs or your editor of choice connect to the repl.

```clojure
(require '[julia-clj.core :as jc])
(jc/jl_is_initialized) ;; 0
(jc/jl_init__threading)
(jc/jl_is_initialized) ;; 1

(System/gc) ;;crash after one or two of these.
```

Crash currently is (from julia):
```console
fatal: error thrown and no exception handler available.
ReadOnlyMemoryError()
```

## License

Copyright © 2020 Chris Nuernberger

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.
