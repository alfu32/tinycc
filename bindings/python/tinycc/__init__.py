"""A self-contained ctypes launcher for the TinyCC release bundle."""

from __future__ import annotations

import ctypes
import importlib.resources
import os
import platform
import tempfile
from pathlib import Path
from typing import Callable, Literal

OutputKind = Literal["exe", "dll"]
DiagnosticListener = Callable[[str], None]
_CROSS_TARGETS = {
    "linux-x86_64": "linux-x86_64",
    "linux-aarch64": "linux-aarch64",
    "windows-x86_64": "windows-x86_64",
    "windows-aarch64": "windows-aarch64",
    "macos-x86_64": "macos-x86_64",
    "macos-aarch64": "macos-aarch64",
}


def _target() -> str:
    system = platform.system().lower()
    machine = platform.machine().lower()
    systems = {"linux": "linux", "darwin": "macos", "windows": "windows"}
    machines = {"x86_64": "x86_64", "amd64": "x86_64", "arm64": "aarch64", "aarch64": "aarch64"}
    try:
        return f"{systems[system]}-{machines[machine]}"
    except KeyError as error:
        raise RuntimeError(f"unsupported TinyCC host: {system}/{machine}") from error


def _unpack_bundle() -> Path:
    root = Path(tempfile.mkdtemp(prefix=f"tinycc-{_target()}-"))
    resources = importlib.resources.files(__package__).joinpath("native", _target())
    names = resources.joinpath("files.list").read_text(encoding="utf-8").splitlines()
    for name in names:
        if not name:
            continue
        destination = (root / name).resolve()
        if root not in destination.parents:
            raise RuntimeError("invalid native resource path")
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(resources.joinpath(name).read_bytes())
    return root


def _unpack_target_sysroot(root: Path, target: str) -> Path | None:
    if target.startswith("macos-"):
        return None
    if target == _target():
        candidate = root / "tinycc/sysroot"
        return candidate if candidate.is_dir() else None

    resources = importlib.resources.files(__package__).joinpath("native", target)
    names = resources.joinpath("files.list").read_text(encoding="utf-8").splitlines()
    sysroot = root / "sysroots" / target / "tinycc/sysroot"
    extracted = False
    for name in names:
        if not name.startswith("tinycc/sysroot/"):
            continue
        destination = (root / "sysroots" / target / name).resolve()
        if root not in destination.parents:
            raise RuntimeError("invalid sysroot resource path")
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(resources.joinpath(name).read_bytes())
        extracted = True
    return sysroot if extracted else None


class Compiler:
    """Compiles an executable or shared library and streams diagnostics."""

    def __init__(self, bundle_directory: str | os.PathLike[str] | None = None):
        root = Path(bundle_directory) if bundle_directory else _unpack_bundle()
        self._bundle_root = root
        windows = _target().startswith("windows-")
        native_directory = root / ("tinycc/bin" if windows else "tinycc/lib")
        self._runtime_directory = native_directory if windows else native_directory / "tcc"
        candidate_sysroot = root / "tinycc/sysroot"
        self._sysroot_directory = candidate_sysroot if candidate_sysroot.is_dir() else None
        suffix = ".dll" if windows else ".dylib" if _target().startswith("macos-") else ".so"
        self._driver_suffix = suffix
        self._library = ctypes.CDLL(str(native_directory / f"libtcc{suffix}"))
        self._driver_path = native_directory / f"tcc-driver{suffix}"
        self._driver = self._load_driver(self._driver_path)
        self._configure_api()

    @staticmethod
    def _load_driver(path: Path) -> ctypes.CDLL:
        driver = ctypes.CDLL(str(path))
        driver.main.argtypes = [ctypes.c_int, ctypes.POINTER(ctypes.c_char_p)]
        driver.main.restype = ctypes.c_int
        return driver

    def _configure_api(self) -> None:
        library = self._library
        library.tcc_new.restype = ctypes.c_void_p
        library.tcc_delete.argtypes = [ctypes.c_void_p]
        library.tcc_set_lib_path.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
        library.tcc_set_sysroot.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
        library.tcc_set_output_type.argtypes = [ctypes.c_void_p, ctypes.c_int]
        library.tcc_compile_string.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
        library.tcc_compile_string.restype = ctypes.c_int
        library.tcc_output_file.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
        library.tcc_output_file.restype = ctypes.c_int
        self._error_function_type = ctypes.CFUNCTYPE(None, ctypes.c_void_p, ctypes.c_char_p)
        library.tcc_set_error_func.argtypes = [ctypes.c_void_p, ctypes.c_void_p, self._error_function_type]

    def compile(
        self,
        source: str,
        output_path: str | os.PathLike[str],
        kind: OutputKind = "exe",
        diagnostics: DiagnosticListener | None = None,
    ) -> int:
        if kind not in ("exe", "dll"):
            raise ValueError("kind must be 'exe' or 'dll'")
        state = self._library.tcc_new()
        if not state:
            raise RuntimeError("could not create a TinyCC compilation state")

        def report(_opaque: int, message: bytes) -> None:
            if diagnostics:
                diagnostics(message.decode("utf-8", errors="replace"))

        error_callback = self._error_function_type(report)
        try:
            self._library.tcc_set_lib_path(state, os.fsencode(self._runtime_directory))
            self._library.tcc_set_error_func(state, None, error_callback)
            if self._sysroot_directory is not None:
                self._library.tcc_set_sysroot(state, os.fsencode(self._sysroot_directory))
            if self._library.tcc_set_output_type(state, 2 if kind == "exe" else 4) != 0:
                return -1
            if self._library.tcc_compile_string(state, source.encode("utf-8")) != 0:
                return -1
            return self._library.tcc_output_file(state, os.fsencode(output_path))
        finally:
            self._library.tcc_delete(state)

    def execute_tcc(self, arguments: list[str]) -> int:
        """Run a native or cross-target TCC driver's exported main through ctypes."""
        target: str | None = None
        tcc_arguments: list[str] = []
        index = 0
        while index < len(arguments):
            argument = arguments[index]
            if argument == "--":
                tcc_arguments.extend(arguments[index:])
                break
            if argument == "--target":
                index += 1
                if index == len(arguments):
                    raise ValueError("--target requires a target triple")
                if target is not None:
                    raise ValueError("--target may be specified only once")
                target = arguments[index]
            elif argument.startswith("--target="):
                if target is not None:
                    raise ValueError("--target may be specified only once")
                target = argument[len("--target=") :]
            else:
                tcc_arguments.append(argument)
            index += 1

        if target is not None and target not in _CROSS_TARGETS:
            choices = ", ".join(_CROSS_TARGETS)
            raise ValueError(f"unsupported target {target!r}; expected one of {choices}")

        has_sysroot = any(
            argument == "--sysroot" or argument.startswith("--sysroot=") for argument in tcc_arguments
        )

        driver = self._driver
        runtime_directory = self._runtime_directory
        sysroot = None if has_sysroot else self._sysroot_directory
        if target is not None:
            driver_path = self._bundle_root / "tinycc" / ("bin" if _target().startswith("windows-") else "lib")
            driver_path = driver_path / "cross" / _CROSS_TARGETS[target] / f"tcc-driver{self._driver_suffix}"
            if not driver_path.is_file():
                raise RuntimeError(f"cross-target driver is missing: {driver_path}")
            driver = self._load_driver(driver_path)
            runtime_directory = driver_path.parent
            sysroot = None if has_sysroot else _unpack_target_sysroot(self._bundle_root, target)

        encoded = [
            os.fsencode(self._driver_path if target is None else driver_path),
            b"-B",
            os.fsencode(runtime_directory),
        ]
        if target is not None:
            encoded.extend((b"-L", os.fsencode(runtime_directory)))
            if target.startswith("windows-") and not has_sysroot:
                encoded.extend((b"-L", os.fsencode(runtime_directory / "lib")))
        if sysroot is not None:
            encoded.extend((b"--sysroot", os.fsencode(sysroot)))
        encoded.extend(os.fsencode(argument) for argument in tcc_arguments)
        argv = (ctypes.c_char_p * (len(encoded) + 1))()
        for index, argument in enumerate(encoded):
            argv[index] = argument
        return int(driver.main(len(encoded), argv))


__all__ = ["Compiler", "DiagnosticListener", "OutputKind"]
