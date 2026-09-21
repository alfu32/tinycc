# Repository Guidelines

## Project Structure & Module Organization

TinyCC's compiler sources live at the repository root: `tcc.c` is the CLI,
`libtcc.c` exposes the embeddable API, and target-specific back ends use names
such as `x86_64-gen.c`, `arm64-link.c`, and `tccpe.c`. Public headers include
`libtcc.h` and `include/`. Compiler runtime support is under `lib/`; Windows
build support is under `win32/`. Keep new regression inputs in `tests/`:
`tests/tests2/` pairs numbered `.c` files with `.expect` output, and
`tests/pp/` does the same for preprocessor cases.

The optional distribution facades are in `bindings/` (JNI, Java/Kotlin, and
Python). Release assembly scripts live in `scripts/`, and manual GitHub
workflows live in `.github/workflows/`.

## Build, Test, and Development Commands

On Linux or macOS, configure and test from a clean checkout:

```sh
./configure
make -j2
make test -k
```

Use `make clean` for object/test output and `make distclean` before changing
configuration. Build a specific test group with `make -C tests tests2-dir` or
`make -C tests pp-dir`. Windows builds use `win32/build-tcc.bat`; consult the
manual build workflow for supported MSVC commands. Run
`bash scripts/package-unix.sh <platform> <archive>` only when producing a
release payload; it rebuilds and tests the tree.

## Coding Style & Naming Conventions

Follow the surrounding C style exactly: short static helpers, existing macro
conventions, and the file's established indentation. Do not apply wholesale
formatting. Preserve target naming patterns (`arm64-*`, `x86_64-*`) and name
new tests numerically when extending `tests/tests2/`. Shell scripts use Bash
with `set -euo pipefail`; keep paths quoted. Generated `config.h` and
`config.mak` come from `configure` and should not be hand-edited.

## Testing Guidelines

Add a focused regression test for every compiler or preprocessor fix, with its
expected output where applicable. Run the narrow test first, then `make test
-k`; report platform-specific tests that were not run. For binding or release
changes, also exercise the relevant CLI/JAR/Python entry point locally.

## Commit & Pull Request Guidelines

Recent history uses concise Conventional Commit-style subjects, for example
`fix(ci): handle Windows ZIP separators` and `feat(compiler): add launcher`.
Use an imperative scoped subject. PRs should explain behavior and target
impact, link related issues when available, list commands/platforms tested,
and call out generated artifacts or release-workflow changes.


## Agent-Specific Instructions

Preserve user-authored project content and unrelated worktree changes. Do not reorganize modules or rename specification files unless explicitly required.

# response guidelines

- always respond in the sum up in the commitizen format

All commits must follow the Commitizen / Conventional Commits standard using the structural layout below:

## Commitizen / Conventional Commits standard
```text
<type>(<scope>): <subject>

<body>
```

### Field Definitions

* **`<type>`**: Must be one of the following lowercase tokens:
    * `feat`: A new feature or capability.
    * `fix`: A bug fix.
    * `docs`: Documentation changes only.
    * `style`: Changes that do not affect the meaning of the code (white-space, formatting, missing semi-colons, etc).
    * `refactor`: A code change that neither fixes a bug nor adds a feature.
    * `perf`: A code change that improves performance.
    * `test`: Adding missing tests or correcting existing tests.
    * `chore`: Changes to the build process, auxiliary tools, or libraries/dependencies.
* **`<scope>`**: Optional. A noun naming the specific codebase component or module affected, wrapped in parentheses (e.g., `(parser)`, `(auth)`, `(runtime)`).
* **`<subject>`**: A brief, imperative-mood summary of the change. Do not capitalize the first letter. Do not end with a period.
* **`<body>`**: Optional. Separate from the subject with exactly one blank line. Provides the motivation for the change and contrasts it with previous behavior.

additionally the body should be structured as folows:
- summary of what was asked/demande
- summary of the solution or answer
- bulleted list of technical/functional modifications or planning steps ( what you print out by default in the summary )



### Examples

```text
fix(editor): persist and reveal mapped compiler diagnostics

  - Diagnostics are persisted on each node and restored with the project.
  - New validation/compilation clears previous diagnostics.
  - Gutter markers now reveal the mapped editor, section, and source line automatically.
  - Nodes with diagnostics show a red warning badge in the diagram.
  - Runtime/override errors without source-map entries are retained and shown as unmapped instead of being discarded.
  - The status bar now shows:
    generated-file:line:column -> node section source-line:column

  git diff --check passes. Full Gradle compilation remains blocked by the environment’s existing wildcard-IP Gradle startup failure.

```

```text
fix(compiler): resolve memory leaks on dynamic execution evaluation loops
```
