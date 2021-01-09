package julia_clj;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;
import java.nio.ByteBuffer;


public class DirectMapped
{
  public static native Pointer jl_call(Pointer p, Pointer args, int n_args);
  public static native Pointer jl_call0(Pointer f);
  public static native Pointer jl_call1(Pointer f, Pointer a0);
  public static native Pointer jl_call2(Pointer f, Pointer a0, Pointer a1);
  public static native Pointer jl_call3(Pointer f, Pointer a0, Pointer a1, Pointer a2);
}
