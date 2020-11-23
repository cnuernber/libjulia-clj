package julia_clj;


import com.sun.jna.*;
import java.util.*;

public class JLOptions extends Structure {
  public byte quiet;
  public byte banner;
  public Pointer julia_bindir;
  public Pointer julia_bin;
  public Pointer cmds;
  public Pointer image_file;
  public Pointer cpu_target;
  public int nthreads;
  public int nprocs;
  public Pointer machine_file;
  public Pointer project;
  public byte isinteractive;
  public byte color;
  public byte historyfile;
  public byte startupfile;
  public byte compile_enabled;
  public byte code_coverage;
  public byte malloc_log;
  public byte opt_level;
  public byte debug_level;
  public byte check_bounds;
  public byte depwarn;
  public byte warn_overwrite;
  public byte can_inline;
  public byte polly;
  public Pointer trace_compile;
  public byte fast_math;
  public byte worker;
  public Pointer cookie;
  public byte handle_signals;
  public byte use_sysimage_native_code;
  public byte use_compiled_modules;
  public Pointer bindto;
  public Pointer outputbc;
  public Pointer outputunoptbc;
  public Pointer outputo;
  public Pointer outputasm;
  public Pointer outputji;
  public Pointer output_code_coverage;
  public byte incremental;
  public byte image_file_specified;
  public byte warn_scope;
  public static class ByReference extends JLOptions implements Structure.ByReference {}
  public static class ByValue extends JLOptions implements Structure.ByValue {}
  public JLOptions () {}
  public JLOptions (Pointer p) { super(p); read(); }
  protected List getFieldOrder() { return Arrays.asList(new String[]
    {
      "quiet",
      "banner",
      "julia_bindir",
      "julia_bin",
      "cmds",
      "image_file",
      "cpu_target",
      "nthreads",
      "nprocs",
      "machine_file",
      "project",
      "isinteractive",
      "color",
      "historyfile",
      "startupfile",
      "compile_enabled",
      "code_coverage",
      "malloc_log",
      "opt_level",
      "debug_level",
      "check_bounds",
      "depwarn",
      "warn_overwrite",
      "can_inline",
      "polly",
      "trace_compile",
      "fast_math",
      "worker",
      "cookie",
      "handle_signals",
      "use_sysimage_native_code",
      "use_compiled_modules",
      "bindto",
      "outputbc",
      "outputunoptbc",
      "outputo",
      "outputasm",
      "outputji",
      "output_code_coverage",
      "incremental",
      "image_file_specified",
      "warn_scope"
    });
  }
}
