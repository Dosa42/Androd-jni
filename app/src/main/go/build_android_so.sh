#!/usr/bin/env bash
set -euo pipefail

# Build script to cross-compile backend-go into an Android JNI native shared library (.so)
# Requires Go 1.21+ and Android NDK (r25+ / r26+)

echo "=========================================================="
echo "  Building CCX Go Backend into Android JNI (libccx.so)    "
echo "=========================================================="

# 1. Resolve Android NDK Path
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${ANDROID_NDK_LATEST_HOME:-""}}}"
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
    echo "Looking for NDK in standard locations..."
    NDK=$(ls -d /usr/local/lib/android/sdk/ndk/* 2>/dev/null | tail -n 1 || echo "")
fi

if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
    echo "❌ Error: Android NDK not found. Set ANDROID_NDK_HOME or ANDROID_NDK_ROOT."
    exit 1
fi

echo "Using Android NDK: $NDK"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
API_LEVEL=24

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT_DIR="${OUTPUT_JNI_DIR:-$ROOT_DIR/app/src/main/jniLibs}"

# 2. Build for Target ABIs
build_abi() {
    local abi=$1
    local goarch=$2
    local cc_name=$3
    local extra_goarm=${4:-""}

    local target_dir="$OUTPUT_DIR/$abi"
    mkdir -p "$target_dir"

    echo "==> Compiling for $abi (GOARCH=$goarch)..."
    export CGO_ENABLED=1
    export GOOS=android
    export GOARCH="$goarch"
    export CC="$TOOLCHAIN/$cc_name"

    if [ -n "$extra_goarm" ]; then
        export GOARM="$extra_goarm"
    else
        unset GOARM || true
    fi

    go build -buildmode=c-shared \
        -ldflags="-s -w" \
        -o "$target_dir/libccx.so" \
        .

    echo "✅ Generated $target_dir/libccx.so ($(stat -c%s "$target_dir/libccx.so" 2>/dev/null || stat -f%z "$target_dir/libccx.so") bytes)"
}

cd "$(dirname "${BASH_SOURCE[0]}")"

# Compile arm64-v8a (Standard modern Android devices)
build_abi "arm64-v8a" "arm64" "aarch64-linux-android${API_LEVEL}-clang"

# Compile armeabi-v7a (32-bit ARM)
build_abi "armeabi-v7a" "arm" "armv7a-linux-androideabi${API_LEVEL}-clang" "7"

# Compile x86_64 (64-bit Android emulators)
build_abi "x86_64" "amd64" "x86_64-linux-android${API_LEVEL}-clang"

echo "=========================================================="
echo "  All Android JNI shared libraries compiled successfully! "
echo "=========================================================="
