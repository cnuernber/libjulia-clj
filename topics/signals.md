# Julia, The JVM, and Signals

Julia and the JVM both rely on an operating concept called [signals](https://en.wikipedia.org/wiki/Signal_(IPC)#:~:text=Signals%20are%20a%20limited%20form,of%20an%20event%20that%20occurred.).  These are a simple method of IPC and if you aren't familiar with them
it probably isn't necessary to get familiar right now but it is necessary
in order to use libjulia-clj for you to understand how the signal mechanism
in these two systems interact and what happens when they interact poorly.


Signals have integer names and a process in unix can install signal handlers
which are a function to be called when a given signal is triggered.  You may
have heard of a few of them; at least `SIGSEGV` which is a signal normally
reserved for when the operating system detects an out-of-bounds memory access,
otherwise called a segmentation fault.


## Two Worlds Collide

When using Julia from the JVM you are likely to run into instances where, during
their normal course of operation their respective usage of signals conflict.  For
instance, the JVM uses `SIGSEGV` during it's normal course of operation and if the
Julia handler for `SIGSEGV` is installed then things like a normal JVM garbage
collection run can cause the process to unceremoniously exit:

```clojure
user> (require '[libjulia-clj.julia :as julia])
nil
user> (julia/initialize! {:signals-enabled? true})
16:14:40.838 [nRepl-session-926e4a65-853b-40b4-a182-0f4b8a0cdfa3] INFO libjulia-clj.impl.base - Attempting to initialize Julia at /home/chrisn/dev/cnuernber/libjulia-clj/julia-1.5.3/lib/libjulia.so
16:14:40.875 [nRepl-session-926e4a65-853b-40b4-a182-0f4b8a0cdfa3] INFO tech.v3.jna.base - Library /home/chrisn/dev/cnuernber/libjulia-clj/julia-1.5.3/lib/libjulia.so found at [:system "/home/chrisn/dev/cnuernber/libjulia-clj/julia-1.5.3/lib/libjulia.so"]
16:14:40.881 [nRepl-session-926e4a65-853b-40b4-a182-0f4b8a0cdfa3] INFO libjulia-clj.impl.jna - Julia startup options: n-threads null, signals? true
:ok
user> (System/gc)

*** Closed on Mon Dec 14 16:14:45 2020 ***
```

Julia has an option to disable it's use of signals but this results in a crash as it
requires the use of at least `SIGINT` in order to manage garbage collection in
multithreaded code.

If we simply disable Julia's use of signals then single-threaded code or
multithreaded code that doesn't produce much garbage works fine.  Multithreaded
code, however, will eventually crash without warning:

```clojure

user> (require '[libjulia-clj.julia :as julia])
nil
user> (julia/initialize! {:n-threads 8 :signals-enabled? false})
16:28:10.854 [nRepl-session-a6713b61-bf94-4492-bcbb-7cc7e44c2a4f] INFO libjulia-clj.impl.base - Attempting to initialize Julia at /home/chrisn/dev/cnuernber/libjulia-clj/julia-1.5.3/lib/libjulia.so
16:28:10.908 [nRepl-session-a6713b61-bf94-4492-bcbb-7cc7e44c2a4f] INFO tech.v3.jna.base - Library /home/chrisn/dev/cnuernber/libjulia-clj/julia-1.5.3/lib/libjulia.so found at [:system "/home/chrisn/dev/cnuernber/libjulia-clj/julia-1.5.3/lib/libjulia.so"]
16:28:10.925 [nRepl-session-a6713b61-bf94-4492-bcbb-7cc7e44c2a4f] INFO libjulia-clj.impl.jna - Julia startup options: n-threads 8, signals? false
:ok
user> (System/gc)
nil
user> (julia/eval-string "Threads.@threads for i in 1:1000; zeros(1024, 1024) .+ zeros(1024, 1024); end")

*** Closed on Mon Dec 14 16:28:25 2020 ***
```

## The Work-Around For Now

The JVM has a facility for [signal chaining](https://docs.oracle.com/javase/10/troubleshoot/handle-signals-and-exceptions.htm#JSTGD356).  This allows us to launch the JVM
in a particular way where installs a handler that listens for entities attempting
to install a signal handler and it records these handlers.  When a signal occurse,
it can detect whether it came from the JVM or from an outside entity and thus route
the correct signal to the correct handler as required.

Using this facility is fairly simple, setup an environment variable  LD_PRELOAD that
forces the operating system to load a shared library that exports functions that
allow the JVM to chain signals as opposed to simply handling them.


If we modify our example from before with this pathway we can successfully run
our example:

```console
chrisn@chrisn-lt-01:~/dev/cnuernber/libjulia-clj$ find /usr -name "*jsig*"
/usr/lib/jvm/java-11-openjdk-amd64/lib/server/libjsig.so
/usr/lib/jvm/java-11-openjdk-amd64/lib/libjsig.so
/usr/lib/jvm/java-8-openjdk-amd64/jre/lib/amd64/server/libjsig.so
/usr/lib/jvm/java-8-openjdk-amd64/jre/lib/amd64/libjsig.so
chrisn@chrisn-lt-01:~/dev/cnuernber/libjulia-clj$ export LD_PRELOAD=/usr/lib/jvm/java-8-openjdk-amd64/jre/lib/amd64/libjsig.so
```

```clojure
user> (require '[libjulia-clj.julia :as julia])
nil
user> ;;Signals are automatically enabled if n-threads has a value
user> (julia/initialize! {:n-threads 8})
16:37:40.695 [nRepl-session-447a06c6-bf23-4338-9618-34e0f841c82b] INFO libjulia-clj.impl.base - Attempting to initialize Julia at /home/chrisn/dev/cnuernber/libjulia-clj/julia-1.5.3/lib/libjulia.so
16:37:40.741 [nRepl-session-447a06c6-bf23-4338-9618-34e0f841c82b] INFO tech.v3.jna.base - Library /home/chrisn/dev/cnuernber/libjulia-clj/julia-1.5.3/lib/libjulia.so found at [:system "/home/chrisn/dev/cnuernber/libjulia-clj/julia-1.5.3/lib/libjulia.so"]
16:37:40.747 [nRepl-session-447a06c6-bf23-4338-9618-34e0f841c82b] INFO libjulia-clj.impl.jna - Julia startup options: n-threads 8, signals? true
:ok
user> (julia/eval-string "Threads.@threads for i in 1:1000; zeros(1024, 1024) .+ zeros(1024, 1024); end")
nil
```

So, for now, we have to setup some nonstandard JVM state in order to use Julia to
it's full potential.  Still, it is pretty amazing that the chaining facility exists
and that it works as advertised and at least we have a solid pathway forward in
order to using Julia from the JVM or vice versa.
