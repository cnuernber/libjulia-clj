# JVM/Julia Garbage Collection Integration

Both the JVM and julia rely on garbage collection in order to decided
when and how to cleanup objects.  This means that we need a mechanism
to ensure that objects visible across language boundaries are not cleaned 
up prematurely.


## Julia Objects in the JVM


When a new julia object is returned from a function we first check
if this is a primitive or atomic type - numbers, symbols, strings. 
If so, we convert the value into the JVM immediately.  Else we 
return a JVM object and `root` the julia object in a datastructure
we declared to julia.


The user has a choice to link the Julia object to the JVM's GC mechanism
such that when the JVM decided the object is no longer reachable we will
then unroot the Julia object.  This is default and requires the user
to periodically call 
[`cycle-gc!`](https://github.com/cnuernber/libjulia-clj/blob/4bf826aa9651c848985e8e13c5d392db4da26d69/src/libjulia_clj/julia.clj#L112) 
in order to unroot objects as the JVM's callback happens in another thread and 
thus we can only mark objects that should be unrooted automatically.  The 
gc based unrooting needs to be cooperative at this point to ensure it happens
in whichever thread is currently using Julia.


There is also a stack based mechanism, 
[`with-stack-context`](https://github.com/cnuernber/libjulia-clj/blob/4bf826aa9651c848985e8e13c5d392db4da26d69/src/libjulia_clj/julia.clj#L123)
by which we can ensure that Julia objects rooted within a given stack 
scope are unrooted when programmatic flow exits that scope either 
normally or via an exception.


## JVM Objects in Julia


- TODO - not sure if this is very necessary or the best way to handle this.
Currently you can pass functions to Julia but this isn't very fleshed
out and it is most likely not bullet-proof.  If they are called from tasks
then there is a decent chance they will be silently ignored.  Most likely
you, from the JVM side, will need to ensure they do not leave JVM scope
while you think they are in scope in Julia.
