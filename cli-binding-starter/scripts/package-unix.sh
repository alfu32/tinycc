#!/usr/bin/env bash
# Build a self-contained TinyCC release payload on Linux or macOS.
#
# Usage: scripts/package-unix.sh <platform-id> <archive-path>
set -euo pipefail

platform_id=${1:?"platform id is required"}
archive_path=${2:?"archive path is required"}
source_root=$(pwd)
release_root="$source_root/.release/$platform_id"
payload_root="$release_root/tinycc"
sysroot_bundle="${SYSROOT_BUNDLE:-$source_root/sysroots-bundle-2026.09.21.tar.zst}"

case "$(uname -s)" in
  Linux)  shared_name=libtcc.so ;;
  Darwin) shared_name=libtcc.dylib ;;
  *) echo "unsupported host: $(uname -s)" >&2; exit 2 ;;
esac

make distclean
rm -rf "$release_root"
mkdir -p "$payload_root"

if [[ "$(uname -s)" == Linux ]]; then
  test -f "$sysroot_bundle" || {
    echo "missing Linux sysroot bundle: $sysroot_bundle" >&2
    exit 2
  }
  sysroot_arch=${platform_id#linux-}
  sysroot_stage=$(mktemp -d)
  trap 'rm -rf "$sysroot_stage"' EXIT
  tar --zstd -xf "$sysroot_bundle" -C "$sysroot_stage"
  sysroot_source="$sysroot_stage/sysroots-bundle-2026.09.21/sysroots/linux/$sysroot_arch"
  test -d "$sysroot_source/usr/include" || {
    echo "sysroot has no headers for $platform_id" >&2
    exit 2
  }
  mkdir -p "$payload_root/sysroot"
  cp -a "$sysroot_source/." "$payload_root/sysroot/"
  cp "$sysroot_stage/sysroots-bundle-2026.09.21/MANIFEST.json" \
    "$payload_root/sysroot/MANIFEST.json"
fi

# Build the command-line compiler against static libtcc.  That makes the
# executable itself relocatable; the launcher below supplies its runtime tree.
./configure --prefix=/tinycc
make -j2
make test -k
make DESTDIR="$release_root" install

mv "$payload_root/bin/tcc" "$payload_root/bin/tcc-bin"
cp scripts/tcc-launcher.sh "$payload_root/bin/tcc"
chmod 755 "$payload_root/bin/tcc"
cp COPYING README VERSION "$payload_root/"

# Expose the complete tcc.c command-line driver as a shared-library export.
# The Java/Python facades call its main function through FFI.
"$payload_root/bin/tcc-bin" -B"$payload_root/lib/tcc" \
  -I"$source_root/include" -I"$source_root" -shared -fPIC -rdynamic \
  "$source_root/tcc.c" "$payload_root/lib/libtcc.a" \
  -o "$payload_root/lib/tcc-driver.so"

# libtcc's objects need a separate PIC build for the shared library.  Keep the
# already-installed static archive and runtime tree from the first build.
make distclean
./configure --prefix=/tinycc --disable-static
make -j2 "$shared_name"
cp "$shared_name" "$payload_root/lib/$shared_name"

: "${JAVA_HOME:?JAVA_HOME must point to a JDK to build the JNI bridge}"
case "$(uname -s)" in
  Linux)
    cc -shared -fPIC bindings/native/tcc_jni.c -I. \
      -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
      -L"$payload_root/lib" -ltcc -ldl -Wl,-rpath,'$ORIGIN' \
      -o "$payload_root/lib/libtinycc_jni.so"
    ;;
  Darwin)
    cc -dynamiclib bindings/native/tcc_jni.c -I. \
      -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/darwin" \
      -L"$payload_root/lib" -ltcc -Wl,-rpath,@loader_path \
      -o "$payload_root/lib/libtinycc_jni.dylib"
    ;;
esac

# Verify that the packaged executable finds its own include/runtime directory.
"$payload_root/bin/tcc" -run examples/ex1.c

archive_dir=$(dirname "$archive_path")
archive_name=$(basename "$archive_path")
mkdir -p "$archive_dir"
archive_dir=$(cd "$archive_dir" && pwd)
tar -czf "$archive_dir/$archive_name" -C "$release_root" tinycc
