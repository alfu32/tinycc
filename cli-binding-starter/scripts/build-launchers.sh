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
    (
        cd "$destination"
        find tinycc -type f -print | LC_ALL=C sort > files.list
    )
done

classes="$work_directory/classes"
resources="$work_directory/resources"
mkdir -p "$classes" "$resources/native"
find bindings/jvm/src/main/java -name '*.java' -print0 | xargs -0 javac -d "$classes"
cp -R "$work_directory/native/." "$resources/native/"

jar --create --file "$output_directory/tinycc-embed.jar" \
    -C "$classes" . -C "$resources" .
jar --create --file "$output_directory/tinycc-cli.jar" \
    --main-class org.tinycc.cli.Main -C "$classes" . -C "$resources" .

python_root="$work_directory/python"
mkdir -p "$python_root"
cp -R bindings/python/tinycc "$python_root/tinycc"
cp -R "$work_directory/native" "$python_root/tinycc/native"
python3 -m zipapp "$python_root" -m tinycc.__main__:main \
    -o "$output_directory/tinycc.pyz"
