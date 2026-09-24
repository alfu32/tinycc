#!/usr/bin/env bash
# Prepare one Raylib 6.0 target payload for the release workflow.
set -euo pipefail

target=${1:?usage: prepare-raylib.sh <linux-x86_64|linux-aarch64|windows-x86_64|windows-aarch64|macos-universal> <output-root>}
output_root=${2:?output root is required}
version=6.0
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

case "$target" in
  linux-x86_64|linux-aarch64)
    arch=${target#linux-}
    docker_arch=amd64
    [[ "$arch" == aarch64 ]] && docker_arch=arm64
    source_archive="$work/raylib.tar.gz"
    curl --fail --location --retry 5 --retry-delay 2 \
      "https://github.com/raysan5/raylib/archive/refs/tags/$version.tar.gz" \
      --output "$source_archive"
    mkdir "$work/source" "$work/out"
    tar -xzf "$source_archive" -C "$work/source" --strip-components=1
    host_uid=$(id -u)
    host_gid=$(id -g)
    docker run --rm --platform "linux/$docker_arch" \
      --env "HOST_UID=$host_uid" --env "HOST_GID=$host_gid" \
      -v "$work:/work" alpine:3.23 sh -euxc '
        # The container builds as root; return the mounted tree to the runner
        # before the host EXIT trap removes it, even when a build step fails.
        trap "chown -R \${HOST_UID}:\${HOST_GID} /work" EXIT
        apk add --no-cache build-base linux-headers mesa-dev libx11-dev \
          libxrandr-dev libxinerama-dev libxcursor-dev libxi-dev
        mkdir -p /work/out/include /work/out/lib /work/out/sysroot/usr/lib
        cd /work/source/src
        make PLATFORM=PLATFORM_DESKTOP PLATFORM_OS=LINUX \
          RAYLIB_LIBTYPE=STATIC RAYLIB_RELEASE_PATH=/work/out/lib
        cp raylib.h raymath.h rlgl.h rcamera.h rgestures.h /work/out/include/
        for pattern in libGL.so* libX11.so* libXrandr.so* libXinerama.so* \
          libXcursor.so* libXi.so* libXxf86vm.so* libXext.so* libxcb.so* \
          libXau.so* libXdmcp.so*; do
          for library in /usr/lib/$pattern; do
            if [ -e "$library" ]; then cp -a "$library" /work/out/sysroot/usr/lib/; fi
          done
        done
      '
    destination="$output_root/linux/$arch"
    mkdir -p "$destination"
    cp -a "$work/out/." "$destination/"
    ;;
  windows-x86_64|windows-aarch64)
    arch=${target#windows-}
    triple=x86_64-w64-mingw32
    [[ "$arch" == aarch64 ]] && triple=aarch64-w64-mingw32
    toolchain_archive="$work/llvm-mingw.tar.xz"
    curl --fail --location --retry 5 --retry-delay 2 \
      https://github.com/mstorsjo/llvm-mingw/releases/download/20260908/llvm-mingw-20260908-ucrt-ubuntu-22.04-x86_64.tar.xz \
      --output "$toolchain_archive"
    printf '%s  %s\n' 2258c745e3155870c80793f3e8c80b28fbde11b9ff73c4c78783635b3440b092 "$toolchain_archive" | sha256sum --check
    mkdir "$work/toolchain" "$work/source" "$work/out" "$work/out/lib"
    tar -xJf "$toolchain_archive" -C "$work/toolchain" --strip-components=1
    curl --fail --location --retry 5 --retry-delay 2 \
      "https://github.com/raysan5/raylib/archive/refs/tags/$version.tar.gz" \
      --output "$work/raylib.tar.gz"
    tar -xzf "$work/raylib.tar.gz" -C "$work/source" --strip-components=1
    make -C "$work/source/src" PLATFORM=PLATFORM_DESKTOP PLATFORM_OS=WINDOWS \
      CC="$work/toolchain/bin/$triple-clang" \
      AR="$work/toolchain/bin/$triple-ar" \
      RAYLIB_LIBTYPE=STATIC RAYLIB_RELEASE_PATH="$work/out/lib"
    mkdir -p "$work/out/include"
    cp "$work/source/src/"{raylib.h,raymath.h,rlgl.h,rcamera.h,rgestures.h} "$work/out/include/"
    destination="$output_root/windows/$arch"
    mkdir -p "$destination"
    cp -a "$work/out/." "$destination/"
    ;;
  macos-universal)
    curl --fail --location --retry 5 --retry-delay 2 \
      https://github.com/raysan5/raylib/releases/download/6.0/raylib-6.0_macos.tar.gz \
      --output "$work/raylib-macos.tar.gz"
    mkdir "$work/unpacked"
    tar -xzf "$work/raylib-macos.tar.gz" -C "$work/unpacked"
    destination="$output_root/macos/universal"
    mkdir -p "$destination"
    cp -a "$work/unpacked/raylib-6.0_macos/include" "$destination/"
    cp -a "$work/unpacked/raylib-6.0_macos/lib" "$destination/"
    ;;
  *)
    echo "unsupported Raylib target: $target" >&2
    exit 2
    ;;
esac

test -d "$destination/include"
test -d "$destination/lib"
test -n "$(find "$destination/lib" -maxdepth 1 -type f -print -quit)"
echo "Prepared Raylib $version payload: $destination"
