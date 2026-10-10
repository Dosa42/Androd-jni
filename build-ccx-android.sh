#!/usr/bin/env bash
set -euo pipefail

# Usage: ANDROID_NDK_HOME=/path/to/ndk bash build-ccx-android.sh /path/to/ccx/backend-go
APP_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${1:?Pass the CCX backend-go directory}"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to the installed Android NDK}"

# Set Android NDK Toolchain path
export NDK_TOOLCHAIN=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin
export CC=$NDK_TOOLCHAIN/aarch64-linux-android33-clang

failed=0

# Build shared library with CGO: arm64-v8a
if CGO_ENABLED=1 \
GOOS=android \
GOARCH=arm64 \
go build -v -buildmode=c-shared \
  -ldflags="-s -w" \
  -o libccx.so .; then
  mkdir -p "$APP_ROOT/app/src/main/jniLibs/arm64-v8a"
  cp libccx.so "$APP_ROOT/app/src/main/jniLibs/arm64-v8a/libccx.so"
else
  failed=1
fi

# Build shared library with CGO: armeabi-v7a
export CC=$NDK_TOOLCHAIN/armv7a-linux-androideabi33-clang
if CGO_ENABLED=1 \
GOOS=android \
GOARCH=arm \
GOARM=7 \
go build -v -buildmode=c-shared \
  -ldflags="-s -w" \
  -o libccx.so .; then
  mkdir -p "$APP_ROOT/app/src/main/jniLibs/armeabi-v7a"
  cp libccx.so "$APP_ROOT/app/src/main/jniLibs/armeabi-v7a/libccx.so"
else
  failed=1
fi

# Build shared library with CGO: x86_64
export CC=$NDK_TOOLCHAIN/x86_64-linux-android33-clang
if CGO_ENABLED=1 \
GOOS=android \
GOARCH=amd64 \
go build -v -buildmode=c-shared \
  -ldflags="-s -w" \
  -o libccx.so .; then
  mkdir -p "$APP_ROOT/app/src/main/jniLibs/x86_64"
  cp libccx.so "$APP_ROOT/app/src/main/jniLibs/x86_64/libccx.so"
else
  failed=1
fi

exit "$failed"
