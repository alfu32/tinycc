from __future__ import annotations

import argparse
from pathlib import Path
import sys

from . import Compiler


def main() -> None:
    parser = argparse.ArgumentParser(description="TinyCC Python launcher")
    parser.add_argument("kind", choices=("exe", "dll"))
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    source_path = args.source.resolve()
    source = f'#line 1 "{source_path}"\n' + source_path.read_text(encoding="utf-8")
    result = Compiler().compile(source, args.output, args.kind, print)
    raise SystemExit(0 if result == 0 else 1)


if __name__ == "__main__":
    main()
