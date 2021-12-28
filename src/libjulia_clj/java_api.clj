(ns libjulia-clj.java-api
  "Java Users - Use the -aot postfixed version of the jar on clojars.  Then import
  `libjulia_clj.java_api`.  Each method in this namespace is exposed as a public static
  method on the java_api class so for instance `-initialize` is exposed as:

```java
  java_api.initialize(options);
```"
  (:import [clojure.java.api Clojure]
           [java.util Map Map$Entry]
           [java.util.function Supplier])
  (:gen-class
   :name libjulia_clj.java_api
   :main false
   :methods [#^{:static true} [initialize [java.util.Map] Object]
             #^{:static true} [runString [String] Object]
             #^{:static true} [inJlContext [java.util.function.Supplier] Object]
             #^{:static true} [namedTuple [java.util.Map] Object]
             #^{:static true} [createArray [String Object Object] Object]
             #^{:static true} [arrayToJVM [Object] java.util.Map]]))


(set! *warn-on-reflection* true)


(defonce requires* (delay
                     (let [require (Clojure/var "clojure.core" "require")]
                       (require (Clojure/read "tech.v3.datatype"))
                       (require (Clojure/read "libjulia-clj.julia"))
                       (require (Clojure/read "tech.v3.tensor")))))


(defn -initialize
  "Initialize the julia interpreter.  See documentation for [[libjulia-clj.julia/initialize!]].
  Options may be null or must be a map of string->value for one of the supported initialization
  values.  JULIA_HOME can be set by the user by setting the key \"julia-home\" in the
  options map to the desired value and this will supercede the environment variable
  version.

  Example:

```clojure
  (japi/-initialize (jvm-map/hash-map {\"n-threads\" 8}))
```"
  [options]
  @requires*
  ((Clojure/var "libjulia-clj.julia" "initialize!")
   (->> options
        (map (fn [^Map$Entry entry]
               [(keyword (.getKey entry))
                (.getValue entry)]))
        (into {}))))


(defn -runString
  "Run a string in Julia returning a jvm object if the return value is simple or
  a julia object if not.  The returned object will have a property overloaded
  toString method for introspection."
  [^String data]
  ((Clojure/var "libjulia-clj.julia" "jl") data))


(defn -inJlContext
  "Execute a function in a context where all julia objects created will be released
  just after the function returns.  The function must return pure JVM data - it cannot
  return a reference to a julia object."
  [^Supplier fn]
  ((Clojure/var "libjulia-clj.julia" "in-jl-ctx") fn))


(defn -namedTuple
  "Create a julia named tuple.  This is required for calling keyword functions.  The
  path for calling keyword functions looks something like:

  * `data` - must be an implementation of java.util.Map with strings as keys.

```clojure
(let [add-fn (jl \"function teste(a;c = 1.0, b = 2.0)
    a+b+c
end\")
          kwfunc (jl \"Core.kwfunc\")
          add-kwf (kwfunc add-fn)]
      (is (= 38.0 (add-kwf (jl/named-tuple {'b 10 'c 20})
                           add-fn
                           8.0)))
      (is (= 19.0 (add-kwf (jl/named-tuple {'b 10})
                           add-fn
                           8.0)))
      (is (= 11.0 (add-kwf (jl/named-tuple)
                           add-fn
                           8.0)))

      (is (= 38.0 (add-fn 8.0 :b 10 :c 20)))
      (is (= 19.0 (add-fn 8 :b 10)))
      (is (= 11.0 (add-fn 8))))
```"
  [^Map data]
  ((Clojure/var "libjulia-clj.julia" "named-tuple") data))


(defn -createArray
  "Return julia array out of the tuple of datatype, shape, and data.

  * `datatype` - must be one of the strings `[\"int8\" \"uint8\" \"int16\" \"uin16\"
  \"int32\" \"uint32\" \"int64\" \"uint64\" \"float32\" \"float64\"].
  * `shape` - an array or implementation of java.util.List that specifies the row-major
  shape intended of the data.  Note that Julia is column-major so this data will appear
  transposed when printed via Julia.
  * `data` may be a java array or an implementation of java.util.List.  Ideally data is
  of the same datatype as data."
  [datatype shape data]
  (let [datatype (keyword datatype)
        reshape (Clojure/var "tech.v3.tensor" "reshape")
        ->array (Clojure/var "libjulia-clj.julia" "->array")]
    (-> ((Clojure/var "tech.v3.tensor" "->tensor") data :datatype (keyword datatype))
        (reshape shape)
        (->array))))


(defn -arrayToJVM
  "Returns a map with three keys - shape, datatype, and data.  Shape is an integer array,
  datatype is a string denoting one of the supported datatypes, and data is a primitive
  array of data."
  [jlary]
  (let [tens-data ((Clojure/var "tech.v3.tensor" "as-tensor") jlary)]
    {"shape" ((Clojure/var "tech.v3.datatype" "->int-array")
              ((Clojure/var "tech.v3.datatype" "shape") tens-data))
     "datatype" (name ((Clojure/var "tech.v3.datatype" "elemwise-datatype") tens-data))
     "data" ((Clojure/var "tech.v3.datatype" "->array") tens-data)}))
