# QuickJS-NG adaptation starter

Copy this directory's contents into the QuickJS-NG repository next to
`quickjsng.prompt.md`, then give the prompt to a new Codex session.

This is the working TinyCC baseline for the machinery that must be adapted:

- `.github/workflows/build.yml` is the manual build/test workflow.
- `.github/workflows/release.yml` is the manual six-platform native build and
  release workflow.
- `scripts/` builds native payloads and assembles the portable launchers.
- `bindings/` supplies the Java JNI facade, CLI facade, and Python package.

It is intentionally not an immediately runnable QuickJS package. The target
repository's build system, library names, public C API, runtime assets, and
CLI behavior must be inspected and substituted by the receiving session.

The desired QuickJS release result is only:

- `quickjs-cli.jar`
- `quickjs-embed.jar`
- `quickjs.pyz`

Each contains payloads for Linux x86_64/aarch64, Windows x86_64/aarch64, and
macOS x86_64/aarch64. The per-platform tar/zip files are CI-only handoff
artifacts; they must not be uploaded as GitHub Release assets.
