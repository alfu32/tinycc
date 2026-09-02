# Embedding TinyCC

`libtcc` is the in-process API for a compiler-backed editor or application.
Every release bundle contains the command-line compiler, the shared library,
the static library, `libtcc.h`, and the private compiler runtime tree.  Keep
that tree with the native library: it supplies TinyCC's headers and
`libtcc1.a`.

For a relocatable application, find that private tree at runtime and pass it
to every compiler state with `tcc_set_lib_path()`.  In the Unix bundles it is
`lib/tcc`; in the Windows bundles it is `bin`, because the Windows DLL locates
its runtime relative to itself.

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

- `tinycc-cli.jar` is a complete native `tcc` driver facade. Arguments are
  passed verbatim to the bundled compiler, so this works exactly as with
  `tcc`: `java -jar tinycc-cli.jar input.c -luuid -o output`. The optional
  leading `exe` is ignored and leading `dll` adds `-shared`.
- `tinycc-embed.jar` is the drop-in Java/Kotlin library. It bundles the JNI
  bridge and all six native TinyCC payloads, selects the current host, extracts
  it once, and exposes `TinyCC.compileExecutable()` and
  `TinyCC.compileDynamicLibrary()`. Supply a `DiagnosticListener` to receive
  errors and warnings as compilation happens.
- `tinycc.pyz` is runnable with `python3 tinycc.pyz exe input.c output` (or
  `dll`). It accepts the complete native TCC command line too, for example
  `python3 tinycc.pyz input.c -luuid -o output`. Its `tinycc.Compiler` class
  is also a direct `ctypes` API.

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

Java and Kotlin launcher sources use `tinycc-embed.jar` to call the exported
library entry point. Compile them with that JAR on the classpath; a Kotlin
launcher additionally needs the normal Kotlin runtime.

No source rewrite is performed: `main` remains `main`. TinyCC exports global
symbols from Unix shared libraries, and the facade adds `-rdynamic` so that
`main` is also exported from Windows DLLs. This mode requires the two-argument
`main` signature; if `main` calls `exit()`, it exits the Python process too.

The JAR and Python launcher deliberately carry all supported native targets;
they do not need a Maven or PyPI repository. For a smaller application
distribution, unpack the platform-specific native release archive and point
the Python `Compiler` constructor at it instead.

Java and Kotlin use the same JNI API:

    int result = TinyCC.compileExecutable(source, outputPath, System.err::println);

    val result = TinyCC.compileDynamicLibrary(source, outputPath) { println(it) }

When invoking the Python API from an unpacked launcher, give it the directory
containing the `tinycc/` payload:

    from tinycc import Compiler

    result = Compiler("/path/to/unpacked-bundle").compile(source, "output", "exe", print)
