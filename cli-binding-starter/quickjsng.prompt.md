# Codex handoff: QuickJS native releases, JVM artifacts, and Python launcher

## Starting point supplied with this prompt

The sibling TinyCC project includes a ~quickjsng-starter/~ directory alongside
this prompt. Copy the *contents* of that directory into the QuickJS repository
before starting, preserving its relative paths. It is a working TinyCC
implementation of the release plumbing and binding layout, deliberately
provided as an adaptation baseline:

~~~sh
cp -a /path/to/quickjsng-starter/. /path/to/quickjs-repository/
~~~

It contains:

- ~bindings/~: JNI C bridge, Java facade/CLI sources, and Python package;
- ~scripts/~: platform packaging, archive assembly, ZIP normalization, and
  launcher assembly scripts;
- ~.github/workflows/build.yml~ and ~.github/workflows/release.yml~;
- ~docs/embedding.md~ as a documentation baseline.

Every ~tinycc~, ~TCC~, compiler-specific library name, CLI option, source
rewrite, and package layout in these files is a placeholder to be deliberately
adapted. Do not blindly retain it. In particular, the TinyCC JNI bridge and
the generated C-program launchers do not represent an appropriate QuickJS API.

You are working in the QuickJS/QuickJS-NG amalgamated-source repository. Build
the same distribution family that exists in the TinyCC sibling project, but
adapt the facade to QuickJS semantics. QuickJS is a JavaScript engine, not a C
compiler: do not copy TinyCC's DLL-compilation or main(argc, argv) launcher
behavior unless the source tree proves it is applicable.

## Outcome

Create a manually dispatched GitHub Actions release workflow and ship one
self-contained GitHub Release with the following three user-facing assets:

1. ~quickjs-cli.jar~: runnable with Java. It contains the CLI facade and
   native QuickJS payloads for all six supported host triples. At runtime it
   selects, extracts, and runs only the host-appropriate payload.

2. ~quickjs-embed.jar~: a drop-in Java/Kotlin library containing the JVM
   facade, JNI bridge, headers/runtime resources where needed, and native
   QuickJS payloads for all six supported host triples. It must select the
   matching DLL/SO/dylib at runtime.

3. ~quickjs.pyz~: runnable with Python 3, containing the Python facade and
   native payloads for all six host triples. It selects and extracts the
   matching executable/library then forwards normal CLI arguments unchanged.

The payload matrix is:

   - Linux x86_64
   - Linux aarch64
   - Windows x86_64
   - Windows aarch64
   - macOS x86_64
   - macOS aarch64 / Apple Silicon

Every per-platform payload must contain the QuickJS command-line executable(s),
   dynamic QuickJS library, static QuickJS library where upstream supports it,
   public headers, runtime files/modules, and license/version/readme material.
   Normally this includes qjs and qjsc, but inspect the actual source tree.

Native ~tar.gz~ and ~zip~ files are allowed as *temporary GitHub Actions
artifacts* between platform jobs and the aggregate job, because that is the
safe way to collect six independently built payloads. Do not attach those
intermediate archives to the GitHub Release. The final release contains the
three portable artifacts above, each bundling its own six-platform native
resources. Do not publish to Maven Central, GitHub Packages, PyPI, or another
package registry.

## First: inspect, do not assume

Before editing:

1. Read README, build files, public headers, and existing workflows.
2. Identify actual upstream names for executable, shared/static libraries,
   public C API, and required runtime assets. QuickJS variants differ.
3. Build and run the native project once on the current host.
4. Preserve unrelated dirty-worktree changes.
5. Check for AGENTS.md and local contribution rules.

Expose useful QuickJS operations only when they map safely to the real public
C API: runtime/context creation, script/module evaluation, exception text,
and resource cleanup are good candidates. Keep the JNI ABI small and stable.
Java and Kotlin should share one JNI implementation.

## CLI design

Do not create a partial Java/Python clone of the QuickJS CLI parser.

For ordinary calls:

~~~text
java -jar quickjs-cli.jar <all normal qjs arguments>
python3 quickjs.pyz <all normal qjs arguments>
~~~

Extract the matching native payload and execute bundled qjs with the original
argv, inherited stdin/stdout/stderr, and its unmodified exit code. This
preserves every upstream option, modules, multiple inputs, bytecode behavior,
interactive mode, and future flags.

If adding generated launchers, make them QuickJS-specific. A generated
Python/Java/Kotlin launcher may execute adjacent JavaScript through embedded
QuickJS or native qjs. Do not invent a C-DLL ABI for a JavaScript source file.
Document every generated launcher's runtime dependencies.

## Release workflow requirements

Add a workflow such as .github/workflows/release.yml.

- Trigger only through workflow_dispatch.
- Require a release_name input. Do not trigger on v-star tags.
- The final job creates or updates the GitHub Release named by that input and
  replaces duplicate assets on rerun.
- Keep existing test workflows manual unless the user explicitly wants
  push-triggered CI.
- Use checkout version 4, setup-java version 4 with Temurin 21, and artifact
  upload/download version 4.
- Grant actions read plus minimum contents permission. The final release job
  needs contents write.

Use these matching native runners:

~~~yaml
linux-x86_64:    ubuntu-22.04
linux-aarch64:   ubuntu-22.04-arm
windows-x86_64:  windows-2025
windows-aarch64: windows-11-arm
macos-x86_64:    macos-15-intel
macos-aarch64:   macos-15
~~~

Ubuntu 22.04 is deliberate: it has a lower glibc baseline than Ubuntu 24.04.
Do not use retired GitHub-hosted 20.04 or 18.04 labels. Record or check shared
library glibc requirements if feasible.

## Critical GitHub Actions pitfalls

These are real failures from the TinyCC project:

1. Checkout ordering: run checkout before downloading artifacts in the final
   job. Checkout cleans untracked files by default and otherwise deletes the
   downloaded release/native directory.

2. Artifact retrieval: download all artifacts into release/native with
   merge-multiple true; avoid a fragile name pattern. Immediately print the
   file tree and assert the directory exists.

3. Flat publish directory: build launchers into ~release/assets~. Publish
   exactly ~quickjs-cli.jar~, ~quickjs-embed.jar~, and ~quickjs.pyz~ from that
   directory; never a directory glob that could pass folders to the release
   command. Native archives remain only under ~release/native~ as assembly
   inputs.

4. Windows ZIP separators: PowerShell Compress-Archive stores entries with
   backslashes. Linux unzip can treat them as literal filename characters.
   Use a small safe Python zipfile extractor that normalizes backslash to
   slash while extracting.

5. Archive isolation: never unpack every platform archive into one directory.
   Extract each into a platform root such as:

~~~text
work/native/linux-x86_64/quickjs/...
work/native/windows-x86_64/quickjs/...
~~~

6. Windows build cwd: if upstream batch files assume relative paths, run them
   from the expected directory. Make packaging scripts derive repository root
   from percent-tilde-dp-zero, then pushd into the required build directory.
   Do not depend on the workflow caller's cwd.

7. Executable permissions: JARs/artifacts lose Unix executable mode. Restore
   executable permission before launching an extracted native binary. Pass any
   required private runtime path explicitly.

## Assembly architecture

Use per-platform Unix and Windows packaging scripts. Each creates one
temporary quickjs-platform tar.gz or zip archive with a top-level quickjs
directory. Its contents become an isolated payload resource directory while
the portable artifacts are assembled.

In the aggregate job:

1. Download six archives.
2. Extract each safely into a distinct platform resource directory.
3. Generate a files.list manifest per platform for Java/Python extraction.
4. Compile Java sources.
5. Create quickjs-embed.jar, quickjs-cli.jar with Main-Class, and quickjs.pyz.
   Each must embed all six platform resource trees, not merely the Linux host
   used by the aggregate job.
6. Put only these three portable artifacts in release/assets.
7. Create/update the input-named GitHub Release with exactly those three
   project assets.

The JAR should load the platform shared library before its JNI bridge. On
Unix link the bridge to find an adjacent QuickJS shared library: dollar-ORIGIN
on Linux and at-loader-path on macOS. On Windows colocate DLLs. The Python
launcher must use the same resource manifest/extraction scheme and not assume
wheel installation.

## Verification

Do not stop at compilation. Before handoff:

1. Run upstream native tests where available.
2. Build one local host bundle.
3. Assemble the JAR and pyz from representative archives.
4. Run:

~~~sh
java -jar quickjs-cli.jar -e 'print("ok")'
python3 quickjs.pyz -e 'print("ok")'
~~~

5. Exercise at least one JNI API call and a Java/Kotlin exception path.
6. Confirm release/assets contains exactly quickjs-cli.jar, quickjs-embed.jar,
   and quickjs.pyz; inspect each archive to confirm it embeds six platform
   payload trees.
7. Run git diff --check.

Report exactly what was locally verified versus what requires GitHub platform
runners. Do not claim the release was created unless the workflow completed.
