#!/usr/bin/env python3
"""Build host-loadable TCC drivers and target runtime archives for release bundles."""

from __future__ import annotations

import argparse
import ctypes
import os
import shutil
import subprocess
import sys
from pathlib import Path


TARGETS = {
    "linux-x86_64": (
        "x86_64-",
        [
            "-DTCC_TARGET_X86_64",
            "-DCONFIG_TCC_MUSL=1",
            '-DCONFIG_TCC_ELFINTERP="/lib/ld-musl-x86_64.so.1"',
        ],
    ),
    "linux-aarch64": (
        "arm64-",
        [
            "-DTCC_TARGET_ARM64",
            "-DCONFIG_TCC_MUSL=1",
            '-DCONFIG_TCC_ELFINTERP="/lib/ld-musl-aarch64.so.1"',
        ],
    ),
    "windows-x86_64": (
        "x86_64-win32-", ["-DTCC_TARGET_X86_64", "-DTCC_TARGET_PE"]
    ),
    "windows-aarch64": (
        "arm64-win32-", ["-DTCC_TARGET_ARM64", "-DTCC_TARGET_PE"]
    ),
    "macos-x86_64": (
        "x86_64-osx-", ["-DTCC_TARGET_X86_64", "-DTCC_TARGET_MACHO"]
    ),
    "macos-aarch64": (
        "arm64-osx-", ["-DTCC_TARGET_ARM64", "-DTCC_TARGET_MACHO"]
    ),
}


def runtime_sources(target: str, source_root: Path) -> list[Path]:
    arm64 = "aarch64" in target
    pe = target.startswith("windows-")
    files = ["lib-arm64.c" if arm64 else "libtcc1.c"]
    if target.startswith("linux-"):
        files.append("dsohandle.c")
    if (target.startswith("linux-") and not arm64) or target == "macos-x86_64":
        files.append("va_list.c")
    if target == "linux-aarch64":
        files.append("armflush.c")
    if pe:
        files.extend(
            [
                "chkstk.S",
                "crt1.c",
                "crt1w.c",
                "wincrt1.c",
                "wincrt1w.c",
                "dllcrt1.c",
                "dllmain.c",
                "winex.c",
            ]
        )
    files.extend(["stdatomic.c", "atomic.S", "builtin.c", "alloca.S", "alloca-bt.S"])
    base = source_root / "win32" / "lib" if pe else source_root / "lib"
    resolved = []
    for filename in files:
        path = base / filename
        if not path.is_file() and filename in {"libtcc1.c", "lib-arm64.c", "stdatomic.c", "atomic.S", "builtin.c", "alloca.S", "alloca-bt.S", "dsohandle.c", "va_list.c", "armflush.c"}:
            path = source_root / "lib" / filename
        if not path.is_file():
            raise FileNotFoundError(f"missing runtime source for {target}: {path}")
        resolved.append(path)
    return resolved


def call_driver(driver_path: Path, arguments: list[str]) -> int:
    library = ctypes.CDLL(str(driver_path))
    main = library.main
    main.argtypes = [ctypes.c_int, ctypes.POINTER(ctypes.c_char_p)]
    main.restype = ctypes.c_int
    encoded = [os.fsencode(driver_path), *(os.fsencode(argument) for argument in arguments)]
    argv = (ctypes.c_char_p * (len(encoded) + 1))()
    for index, argument in enumerate(encoded):
        argv[index] = argument
    return int(main(len(encoded), argv))


def build_runtime(
    target: str, driver_path: Path, target_directory: Path, source_root: Path, sysroots_root: Path | None
) -> None:
    sysroot = None
    if sysroots_root is not None:
        if target.startswith("linux-"):
            arch = "x86_64" if target.endswith("x86_64") else "aarch64"
            sysroot = sysroots_root / "linux" / arch
        if sysroot is not None and not sysroot.is_dir():
            raise FileNotFoundError(f"missing sysroot for {target}: {sysroot}")

    arguments = ["-B", str(target_directory)]
    if sysroot is not None:
        arguments.extend(["--sysroot", str(sysroot)])
    objects = []
    for source in runtime_sources(target, source_root):
        output = target_directory / f"{source.stem}.o"
        result = call_driver(
            driver_path,
            [*arguments, "-I", str(source_root), "-c", str(source), "-o", str(output)],
        )
        if result != 0 or not output.is_file():
            raise RuntimeError(f"TinyCC failed to compile runtime source {source} for {target}")
        objects.append(output)

    prefix, _ = TARGETS[target]
    archive = target_directory / f"{prefix}libtcc1.a"
    result = call_driver(
        driver_path,
        ["-ar", "rcs", str(archive), *(str(path) for path in objects)],
    )
    if result != 0 or not archive.is_file():
        raise RuntimeError(f"TinyCC failed to create runtime archive for {target}")
    for object_file in objects:
        object_file.unlink()
    if target.startswith("windows-"):
        library_directory = target_directory / "lib"
        library_directory.mkdir(exist_ok=True)
        shutil.copy2(archive, library_directory / archive.name)


def smoke_windows_imports(
    target: str, driver_path: Path, target_directory: Path, source_root: Path,
    sysroots_root: Path,
) -> None:
    arch = "x86_64" if target.endswith("x86_64") else "aarch64"
    sysroot = sysroots_root / "windows" / arch
    if not sysroot.is_dir():
        raise FileNotFoundError(f"missing Windows sysroot for {target}: {sysroot}")
    output = target_directory / "cross-import-smoke.exe"
    output.unlink(missing_ok=True)
    result = call_driver(
        driver_path,
        [
            "-std=c11", "-B", str(target_directory), "-L", str(target_directory),
            "-L", str(target_directory / "lib"),
            "--sysroot", str(sysroot), str(source_root / "scripts" / "cross-import-smoke.c"),
            "-lbcrypt", "-lucrt", "-lmsvcrt", "-o", str(output),
        ],
    )
    try:
        if result != 0 or not output.is_file():
            raise RuntimeError(f"Windows import-library link smoke test failed for {target}")
        image = output.read_bytes()
        pe_offset = int.from_bytes(image[0x3C:0x40], "little") if len(image) >= 0x40 else len(image)
        expected_machine = 0x8664 if arch == "x86_64" else 0xAA64
        if image[:2] != b"MZ" or image[pe_offset : pe_offset + 4] != b"PE\0\0":
            raise RuntimeError(f"invalid Windows executable produced for {target}")
        machine = int.from_bytes(image[pe_offset + 4 : pe_offset + 6], "little")
        if machine != expected_machine:
            raise RuntimeError(
                f"Windows executable for {target} has machine type 0x{machine:04x}, "
                f"expected 0x{expected_machine:04x}"
            )
    finally:
        output.unlink(missing_ok=True)
    if arch == "x86_64":
        output = target_directory / "cross-crt-import-smoke.exe"
        output.unlink(missing_ok=True)
        result = call_driver(
            driver_path,
            [
                "-B", str(target_directory), "-L", str(target_directory),
                "-L", str(target_directory / "lib"), "--sysroot", str(sysroot),
                str(source_root / "scripts" / "cross-crt-import-smoke.c"),
                "-l:libmsvcrt.a", "-lmsvcrt", "-o", str(output),
            ],
        )
        try:
            if result != 0 or not output.is_file():
                raise RuntimeError(f"composite Windows CRT import smoke test failed for {target}")
            if b"api-ms-win-crt-stdio-l1-1-0.dll" not in output.read_bytes():
                raise RuntimeError(f"Windows import archive member DLL name was not preserved for {target}")
        finally:
            output.unlink(missing_ok=True)


def smoke_linux_link(
    target: str, driver_path: Path, target_directory: Path, source_root: Path,
    sysroots_root: Path,
) -> None:
    arch = "x86_64" if target.endswith("x86_64") else "aarch64"
    sysroot = sysroots_root / "linux" / arch
    if not sysroot.is_dir():
        raise FileNotFoundError(f"missing Linux sysroot for {target}: {sysroot}")
    output = target_directory / "cross-link-smoke"
    output.unlink(missing_ok=True)
    result = call_driver(
        driver_path,
        [
            "-B", str(target_directory), "-L", str(target_directory),
            "--sysroot", str(sysroot), str(source_root / "scripts" / "cross-linux-smoke.c"),
            "-o", str(output),
        ],
    )
    try:
        if result != 0 or not output.is_file():
            raise RuntimeError(f"Linux cross-link smoke test failed for {target}")
        image = output.read_bytes()
        expected_machine = 62 if arch == "x86_64" else 183
        machine = int.from_bytes(image[18:20], "little") if len(image) >= 20 else -1
        interpreter = f"/lib/ld-musl-{arch}.so.1".encode() + b"\0"
        if image[:4] != b"\x7fELF" or machine != expected_machine:
            raise RuntimeError(
                f"Linux executable for {target} has machine type {machine}, "
                f"expected {expected_machine}"
            )
        if interpreter not in image:
            raise RuntimeError(f"Linux executable for {target} does not use the bundled musl loader")
    finally:
        output.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compiler", required=True, type=Path, help="native TinyCC executable")
    parser.add_argument("--source-root", required=True, type=Path)
    parser.add_argument("--native-runtime", required=True, type=Path)
    parser.add_argument("--output-root", required=True, type=Path)
    parser.add_argument("--host", required=True, choices=["linux", "macos", "windows"])
    parser.add_argument("--prebuilt-runtime-root", type=Path)
    parser.add_argument("--sysroots-root", type=Path)
    options = parser.parse_args()

    source_root = options.source_root.resolve()
    native_runtime = options.native_runtime.resolve()
    output_root = options.output_root.resolve()
    windows_host = options.host == "windows"
    suffix = ".dll" if windows_host else ".dylib" if options.host == "macos" else ".so"

    for target, (prefix, defines) in TARGETS.items():
        target_directory = output_root / target
        target_directory.mkdir(parents=True, exist_ok=True)
        (target_directory / "include").mkdir(exist_ok=True)
        generic_include = native_runtime / "include"
        windows_include = native_runtime / "win32" / "include"
        if target.startswith("windows-") and windows_include.is_dir():
            shutil.copytree(windows_include, target_directory / "include", dirs_exist_ok=True)
        if generic_include.is_dir():
            shutil.copytree(generic_include, target_directory / "include", dirs_exist_ok=True)
        shutil.copytree(source_root / "include", target_directory / "include", dirs_exist_ok=True)
        shutil.copy2(source_root / "tcclib.h", target_directory / "include" / "tcclib.h")

        driver_path = target_directory / f"tcc-driver{suffix}"
        driver_arguments = [
            str(options.compiler.resolve()),
            "-B",
            str(native_runtime),
            "-I",
            str(source_root / "include"),
            "-I",
            str(source_root),
            "-shared",
            "-rdynamic",
            "-DTCC_DRIVER_DLL",
            "-DONE_SOURCE=1",
            *defines,
            f'-DCONFIG_TCC_CROSSPREFIX="{prefix}"',
        ]
        if not windows_host:
            driver_arguments.append("-fPIC")
        driver_arguments.extend([str(source_root / "tcc.c"), "-o", str(driver_path)])
        subprocess.run(driver_arguments, check=True)
        if call_driver(driver_path, ["--version"]) != 0:
            raise RuntimeError(f"exported main failed its smoke test for {target}")

        archive_name = f"{prefix}libtcc1.a"
        if options.prebuilt_runtime_root is not None:
            archive_source = options.prebuilt_runtime_root / archive_name
            if not archive_source.is_file():
                raise FileNotFoundError(f"missing cross runtime archive: {archive_source}")
            shutil.copy2(archive_source, target_directory / archive_name)
            if target.startswith("windows-"):
                library_directory = target_directory / "lib"
                library_directory.mkdir(exist_ok=True)
                shutil.copy2(archive_source, library_directory / archive_name)
        else:
            build_runtime(target, driver_path, target_directory, source_root, options.sysroots_root)

        if target.startswith("windows-"):
            library_directory = target_directory / "lib"
            library_directory.mkdir(exist_ok=True)
            for definition in (source_root / "win32" / "lib").glob("*.def"):
                shutil.copy2(definition, library_directory / definition.name)

        if target.startswith("windows-") and options.sysroots_root is not None:
            smoke_windows_imports(
                target, driver_path, target_directory, source_root,
                options.sysroots_root.resolve(),
            )
        if target.startswith("linux-") and options.sysroots_root is not None:
            smoke_linux_link(
                target, driver_path, target_directory, source_root,
                options.sysroots_root.resolve(),
            )

        print(f"built {target}: {driver_path}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, subprocess.CalledProcessError, RuntimeError) as error:
        print(f"cross bundle build failed: {error}", file=sys.stderr)
        raise SystemExit(1)
