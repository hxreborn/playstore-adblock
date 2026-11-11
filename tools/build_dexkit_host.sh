#!/usr/bin/env bash
# Builds the DexKit JNI library for a Linux (or WSL) host into analysis/tools/dexkit-host,
# where the unit tests look for it (java.library.path). Without it, the APK-backed resolver
# tests are skipped. Requires git, a C++20 compiler, cmake and a JDK; zlib headers are fetched
# locally when the system has none, so no root access is needed.
#
# Usage: tools/build_dexkit_host.sh [dexkit-version]   (default: version from libs.versions.toml)
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
version="${1:-$(tr -d '\r' < "$root/gradle/libs.versions.toml" | sed -n 's/^dexkit = "\(.*\)"/\1/p')}"
work="${DEXKIT_HOST_WORK:-$HOME/.cache/gplayadblock-dexkit-host}"
output="$root/analysis/tools/dexkit-host"
mkdir -p "$work" "$output"
cd "$work"

if [ ! -d "DexKit-$version" ]; then
  git clone -q --depth 1 --branch "$version" --recurse-submodules --shallow-submodules \
    https://github.com/LuckyPray/DexKit.git "DexKit-$version"
fi

extra_flags=()
if ! printf '#include <zlib.h>\n' | c++ -E -x c++ - >/dev/null 2>&1; then
  if [ ! -f zlib/include/zlib.h ]; then
    mkdir -p zlib/include
    git clone -q --depth 1 --branch v1.3.1 https://github.com/madler/zlib.git zlib/src
    cp zlib/src/zlib.h zlib/src/zconf.h zlib/include/
  fi
  mkdir -p zlib/lib
  runtime="$({ ldconfig -p || /sbin/ldconfig -p; } 2>/dev/null \
    | awk '/libz\.so\.1 .*x86-64/ {print $NF; exit}' || true)"
  ln -sf "${runtime:-/usr/lib/x86_64-linux-gnu/libz.so.1}" zlib/lib/libz.so
  extra_flags+=(
    "-DCMAKE_CXX_FLAGS=-I$work/zlib/include"
    "-DCMAKE_C_FLAGS=-I$work/zlib/include"
    "-DCMAKE_SHARED_LINKER_FLAGS=-L$work/zlib/lib"
  )
fi

cmake -S "DexKit-$version/dexkit/src/main/cpp" -B "build-$version" -DCMAKE_BUILD_TYPE=Release \
  -DDISABLE_DEXKIT_LOG=ON "${extra_flags[@]}"
cmake --build "build-$version" -j "$(nproc)"
cp "build-$version/libdexkit.so" "$output/"
echo "built $output/libdexkit.so (DexKit $version)"
