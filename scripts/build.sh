#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk" ]]; then
    echo 'Set ANDROID_HOME to your Android SDK directory.' >&2
    exit 1
fi
case "$(uname -s)" in
    Linux) host=linux-x86_64 ;;
    Darwin) host=darwin-x86_64 ;;
    *) echo 'Use scripts/build.ps1 on Windows.' >&2; exit 1 ;;
esac
toolchain="$sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/$host/bin"
[[ -x "$toolchain/aarch64-linux-android34-clang" ]] || { echo 'Install Android NDK 28.2.13676358.' >&2; exit 1; }
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$toolchain/aarch64-linux-android34-clang"
export CC_aarch64_linux_android="$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"
export AR_aarch64_linux_android="$toolchain/llvm-ar"
export RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384'
cargo build --locked --release --target aarch64-linux-android
mkdir -p android/app/src/main/jniLibs/arm64-v8a dist
cp target/aarch64-linux-android/release/libfontlab.so android/app/src/main/jniLibs/arm64-v8a/
printf 'sdk.dir=%s\n' "$sdk" > android/local.properties
(cd android && bash gradlew assembleDebug)
version=$(sed -n 's/^version = "\([^"]*\)"$/\1/p' Cargo.toml | head -n 1)
apk="VFont-$version.apk"
cp android/app/build/outputs/apk/debug/app-debug.apk "dist/$apk"
if command -v sha256sum >/dev/null; then
    (cd dist && sha256sum "$apk" > SHA256SUMS.txt)
else
    (cd dist && shasum -a 256 "$apk" > SHA256SUMS.txt)
fi
printf 'Built dist/%s\n' "$apk"
