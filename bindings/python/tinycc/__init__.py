"""A self-contained ctypes launcher for the TinyCC release bundle."""

from __future__ import annotations

import ctypes
import importlib.resources
import os
import platform
import stat
import subprocess
import tempfile
from pathlib import Path
from typing import Callable, Literal

OutputKind = Literal["exe", "dll"]
DiagnosticListener = Callable[[str], None]


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


class Compiler:
    """Compiles an executable or shared library and streams diagnostics."""

    def __init__(self, bundle_directory: str | os.PathLike[str] | None = None):
        root = Path(bundle_directory) if bundle_directory else _unpack_bundle()
        self._bundle_root = root
        windows = _target().startswith("windows-")
        native_directory = root / ("tinycc/bin" if windows else "tinycc/lib")
        self._runtime_directory = native_directory if windows else native_directory / "tcc"
        suffix = ".dll" if windows else ".dylib" if _target().startswith("macos-") else ".so"
        self._library = ctypes.CDLL(str(native_directory / f"libtcc{suffix}"))
        self._configure_api()

    def _configure_api(self) -> None:
        library = self._library
        library.tcc_new.restype = ctypes.c_void_p
        library.tcc_delete.argtypes = [ctypes.c_void_p]
        library.tcc_set_lib_path.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
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
            if self._library.tcc_set_output_type(state, 2 if kind == "exe" else 4) != 0:
                return -1
            if self._library.tcc_compile_string(state, source.encode("utf-8")) != 0:
                return -1
            return self._library.tcc_output_file(state, os.fsencode(output_path))
        finally:
            self._library.tcc_delete(state)

    def execute_tcc(self, arguments: list[str]) -> int:
        """Run the bundled native tcc command with its complete CLI semantics."""
        windows = _target().startswith("windows-")
        if windows:
            command = [str(self._bundle_root / "tinycc/bin/tcc.exe")]
        else:
            compiler = self._bundle_root / "tinycc/bin/tcc-bin"
            compiler.chmod(compiler.stat().st_mode | stat.S_IXUSR)
            command = [str(compiler), "-B", str(self._runtime_directory)]
        return subprocess.run([*command, *arguments], check=False).returncode


__all__ = ["Compiler", "DiagnosticListener", "OutputKind"]
