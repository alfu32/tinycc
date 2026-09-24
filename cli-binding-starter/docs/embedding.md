# Embedding TinyCC

`libtcc` is the in-process API for a compiler-backed editor or application.
Every release bundle contains the command-line compiler, the shared library,
the static library, `libtcc.h`, and the private compiler runtime tree.  Keep
that tree with the native library: it supplies TinyCC's headers and
`libtcc1.a`.

Linux and Windows bundles also contain the matching `sysroot/` extracted from
`sysroots-bundle-2026.09.21.tar.zst`. The Java and Python facades select it by
default and pass it to TCC for system headers, libraries, and CRT objects.
User-supplied `--sysroot`, `-I`, and `-L` options remain available. macOS does
not ship an SDK in this bundle and uses the host-provided system SDK.

For a relocatable application, find that private tree at runtime and pass it
to every compiler state with `tcc_set_lib_path()`. In Linux and Windows
bundles, also pass the extracted `sysroot/` to `tcc_set_sysroot()`; the Java
and Python facades do this automatically. In the Unix bundles the private
tree is `lib/tcc`; in the Windows bundles it is `bin`, because the Windows DLL
locates its runtime relative to itself.

The editor flow is:

1. Create a `TCCState` and install `tcc_set_error_func()` before compiling.
   The callback is synchronous, so diagnostics can be sent to the editor as
   they are produced. Prefix in-memory source with a `#line` directive to
   preserve the editor filename and line numbers.
2. Set `TCC_OUTPUT_EXE` or `TCC_OUTPUT_DLL` *before* compiling. Call
   `tcc_compile_string()` and then `tcc_output_file()` for the requested
   executable or dynamic library.
3. Delete the state after the compilation. Create a fresh state for each edit
   or output target; that keeps diagnostics and compiler options isolated.

The public ABI is [libtcc.h](../libtcc.h).  `tests/libtcc_test.c` is a small C
example.  Do not call `tcc_relocate()` before `tcc_output_file()`; outputting
a file performs relocation itself.

## Launchers

The manual **native release bundles** workflow takes a required `release_name`
input and publishes these extra GitHub Release assets:

- `tinycc-cli.jar` is a lightweight proxy that invokes the system `tcc`
  executable from `PATH`; install TinyCC separately. TCC options are passed
  through, with `exe`/`dll` compatibility shorthands and the `--launcher`
  convenience option. For example: `java -jar tinycc-cli.jar input.c -luuid
  -o output`.
- `tinycc-cross-cli.jar` is the self-contained CLI. It calls the exported
  `main` in its bundled driver through JNI and does not spawn `tcc`. The
  optional leading `exe` is ignored and leading `dll` adds `-shared`. Add
  `--target` followed by one of `linux-x86_64`, `linux-aarch64`,
  `windows-x86_64`, `windows-aarch64`, `macos-x86_64`, or `macos-aarch64` to
  select a cross-target driver, for example:

      java -jar tinycc-cross-cli.jar --target linux-aarch64 input.c -o output

  Linux and Windows target sysroots are selected from the matching native
  payload embedded in the JAR; an explicit `--sysroot` overrides that default.
  Windows cross drivers read LLVM-MinGW COFF import archives directly and
  include TinyCC's runtime `.def` files for the CRT support objects.
  macOS SDKs are not bundled, so cross-compiling to macOS requires the SDK to
  be provisioned by the user (and supplied with `--sysroot` when the host
  compiler cannot discover it).
- `tinycc-embed.jar` is the system-backed Java/Kotlin library. It includes the
  Java API and JNI bridge variants, but no TinyCC compiler libraries or
  sysroots. It loads the host's system `libtcc` through the dynamic loader and
  exposes `TinyCC.compileExecutable()` and `TinyCC.compileDynamicLibrary()`.
  Install `libtcc` where both Java's native library search and the OS dynamic
  loader can find it (or configure their search paths). Supply a
  `DiagnosticListener` for live diagnostics.
- `tinycc-cross-embed.jar` is the self-contained Java/Kotlin library. It
  bundles the JNI bridge and all six native TinyCC payloads, selects the
  current host, extracts it once, and exposes the same API without a separate
  TinyCC installation.
- Six host-specific CLI JARs (`tinycc-cli-<platform>.jar`) and six matching
  embed JARs (`tinycc-embed-<platform>.jar`) are also published. Each contains
  only the named host's native payload and bundled sysroot, if available
  (macOS SDKs remain user-provisioned); cross-target drivers and foreign
  sysroots are omitted. Use one when you only need a smaller same-host compiler
  package. The `tinycc-cross-*` JARs remain available for bundled
  cross-compilation.
  The Gradle task accepts `--target all` or one supported triple;
  `scripts/build-launchers.sh` supplies the staged native and output paths
  automatically.
- `tinycc.pyz` is runnable with `python3 tinycc.pyz exe input.c output` (or
  `dll`). It accepts the complete native TCC command line too, for example
  `python3 tinycc.pyz input.c -luuid -o output`, by calling the exported
  `main` in `tcc-driver` through `ctypes`. Its `tinycc.Compiler` class is
  also a direct `ctypes` API. The command line accepts the same `--target`
  triples as the JAR and calls the corresponding host-loadable cross driver.

The CLI can also compile a conventional C `main` into a shared library and
generate an adjacent Python, Java, or Kotlin launcher:

    java -jar tinycc-cli.jar --launcher python file.c -Iinclude -DDEBUG
    java -jar tinycc-cli.jar --launcher java file.c
    java -jar tinycc-cli.jar --launcher kotlin file.c

That creates `file.so` and a `file.py`/`FileLauncher.java`/`FileLauncher.kt`
companion on Linux, `file.dylib` on macOS, or `file.dll` on Windows. The
generated launcher forwards its own arguments to `int main(int argc, char
**argv)` and exits with the return value of `main`. `kotln` is accepted as an
alias for `kotlin`. Use `-o path/to/library` to choose a different native
library name. All remaining arguments are passed to TinyCC.

The direct `TinyCC.compile*()` Java API uses system `libtcc` in
`tinycc-embed.jar` and bundled `libtcc` in `tinycc-cross-embed.jar`; synchronous
diagnostic callbacks work in either. `TinyCC.executeTcc()` uses system `tcc`
from `PATH` in the system-backed JAR. Use `tinycc-cross-cli.jar` with
`--target` for bundled cross-target executable or shared-library output.

Java and Kotlin launcher sources use `tinycc-embed.jar` to call the exported
library entry point. Compile them with that JAR on the classpath; a Kotlin
launcher additionally needs the normal Kotlin runtime.

No source rewrite is performed: `main` remains `main`. TinyCC exports global
symbols from Unix shared libraries, and the facade adds `-rdynamic` so that
`main` is also exported from Windows DLLs. This mode requires the two-argument
`main` signature; if `main` calls `exit()`, it exits the Python process too.

The JARs and Python launcher do not need a Maven or PyPI repository. The
`tinycc-cross-*` JARs and `tinycc.pyz` carry the supported native targets; the
system-backed JARs require a system TinyCC installation.

Java and Kotlin use the same JNI API:

    int result = TinyCC.compileExecutable(source, outputPath, System.err::println);

    val result = TinyCC.compileDynamicLibrary(source, outputPath) { println(it) }

When invoking the Python API from an unpacked launcher, give it the directory
containing the `tinycc/` payload:

    from tinycc import Compiler

    result = Compiler("/path/to/unpacked-bundle").compile(source, "output", "exe", print)
