#!/usr/bin/env python3
"""Extract a Windows-created ZIP while normalizing its entry separators."""

from __future__ import annotations

import shutil
import sys
from pathlib import Path
from zipfile import ZipFile


def main(archive_name: str, destination_name: str) -> None:
    destination = Path(destination_name).resolve()
    with ZipFile(archive_name) as archive:
        for entry in archive.infolist():
            relative = entry.filename.replace("\\", "/").lstrip("/")
            if not relative:
                continue
            target = (destination / relative).resolve()
            if destination not in target.parents and target != destination:
                raise ValueError(f"unsafe archive entry: {entry.filename}")
            if entry.is_dir() or relative.endswith("/"):
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(entry) as source, target.open("wb") as output:
                shutil.copyfileobj(source, output)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(f"usage: {Path(sys.argv[0]).name} <archive.zip> <destination>")
    main(sys.argv[1], sys.argv[2])
