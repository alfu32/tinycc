#!/bin/sh
# The release archive is relocatable.  The compiler's private headers and
# libtcc1.a live next to this launcher rather than at its configure-time path.
set -eu

bindir=$(CDPATH= cd "$(dirname "$0")" && pwd)
exec "$bindir/tcc-bin" -B "$bindir/../lib/tcc" "$@"
