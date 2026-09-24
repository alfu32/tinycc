#!/usr/bin/env bash
# Assemble the JVM and Python launchers from the six native release archives.
# Usage: scripts/build-launchers.sh <native-archive-dir> <output-dir>
set -euo pipefail

archive_directory=${1:?"native archive directory is required"}
output_directory=${2:?"output directory is required"}
work_directory=$(mktemp -d)
trap 'rm -rf "$work_directory"' EXIT

mkdir -p "$work_directory/native" "$output_directory"
mapfile -d '' archives < <(
    find "$archive_directory" -type f \( -name 'tinycc-*.tar.gz' -o -name 'tinycc-*.zip' \) \
        -print0 | LC_ALL=C sort -z
)
if [ "${#archives[@]}" -ne 6 ]; then
    echo "expected six TinyCC native archives below $archive_directory, found ${#archives[@]}" >&2
    exit 2
fi

for archive in "${archives[@]}"; do
    filename=$(basename "$archive")
    cp "$archive" "$output_directory/$filename"
    platform=${filename#tinycc-}
    platform=${platform%.tar.gz}
    platform=${platform%.zip}
    destination="$work_directory/native/$platform"
    mkdir -p "$destination"
    case "$archive" in
        *.tar.gz) tar -xzf "$archive" -C "$destination" ;;
        *.zip) python3 scripts/extract-windows-archive.py "$archive" "$destination" ;;
    esac
    test -d "$destination/tinycc"
    case "$platform" in
        linux-*)
            test -d "$destination/tinycc/sysroot/usr/include" \
                && test -d "$destination/tinycc/sysroot/usr/lib" || {
                echo "missing bundled Linux sysroot in $filename" >&2
                exit 2
            }
            ;;
        windows-*)
            test -d "$destination/tinycc/sysroot/include" \
                && test -d "$destination/tinycc/sysroot/lib" \
                && test -f "$destination/tinycc/sysroot/include/mm_malloc.h" || {
                echo "missing bundled Windows sysroot in $filename" >&2
                exit 2
            }
            ;;
    esac
    (
        cd "$destination"
        find tinycc -type f -print | LC_ALL=C sort > files.list
    )

    case "$platform" in
        windows-*) native_dir="$destination/tinycc/bin"; suffix=dll ;;
        macos-*)   native_dir="$destination/tinycc/lib"; suffix=dylib ;;
        *)         native_dir="$destination/tinycc/lib"; suffix=so ;;
    esac
    for target in linux-x86_64 linux-aarch64 windows-x86_64 windows-aarch64 macos-x86_64 macos-aarch64; do
        test -f "$native_dir/cross/$target/tcc-driver.$suffix" || {
            echo "missing $platform FFI driver for cross target $target" >&2
            exit 2
        }
        case "$target" in
            linux-x86_64) runtime=x86_64-libtcc1.a ;;
            linux-aarch64) runtime=arm64-libtcc1.a ;;
            windows-x86_64) runtime=x86_64-win32-libtcc1.a ;;
            windows-aarch64) runtime=arm64-win32-libtcc1.a ;;
            macos-x86_64) runtime=x86_64-osx-libtcc1.a ;;
            macos-aarch64) runtime=arm64-osx-libtcc1.a ;;
        esac
        test -f "$native_dir/cross/$target/$runtime" || {
            echo "missing $platform runtime archive for cross target $target" >&2
            exit 2
        }
    done
done

gradle -p bindings/jvm packageLaunchers --target all \
    -PnativeRoot="$work_directory/native" \
    -PjarOutputDirectory="$work_directory/jars" \
    --no-daemon
cp "$work_directory/jars/"*.jar "$output_directory/"
test -f "$output_directory/tinycc-cross-cli-no-sysroots.jar"
if jar tf "$output_directory/tinycc-cross-cli-no-sysroots.jar" | grep -E '(^|/)tinycc/sysroot(/|$)' >/dev/null; then
    echo "sysroot content unexpectedly present in tinycc-cross-cli-no-sysroots.jar" >&2
    exit 2
fi

python_root="$work_directory/python"
mkdir -p "$python_root"
cp -R bindings/python/tinycc "$python_root/tinycc"
cp -R "$work_directory/native" "$python_root/tinycc/native"
python3 -m zipapp "$python_root" -m tinycc.__main__:main \
    -o "$output_directory/tinycc.pyz"
