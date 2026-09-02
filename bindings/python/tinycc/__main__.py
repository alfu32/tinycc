from __future__ import annotations

import sys

from . import Compiler


def main() -> None:
    arguments = sys.argv[1:]
    if arguments[:1] == ["exe"]:
        arguments = arguments[1:]
    elif arguments[:1] == ["dll"]:
        arguments = ["-shared", *arguments[1:]]
    result = Compiler().execute_tcc(arguments)
    raise SystemExit(0 if result == 0 else 1)


if __name__ == "__main__":
    main()
