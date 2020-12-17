package libjulia_clj;

import com.sun.jna.*;

public interface JLFunction extends Callback {
  Pointer jlinvoke(Pointer args);
}
