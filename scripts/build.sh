#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
release=0
if [[ "${1:-}" == "--release" ]]; then
    release=1
fi
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
[[ -x "$toolchain/aarch64-linux-android31-clang" ]] || { echo 'Install Android NDK 28.2.13676358.' >&2; exit 1; }
printf -v CARGO_ENCODED_RUSTFLAGS '%s\x1f' '-C' 'link-arg=-Wl,-z,max-page-size=16384' \
    "--remap-path-prefix=$PWD=." "--remap-path-prefix=${CARGO_HOME:-$HOME/.cargo}=cargo"
export CARGO_ENCODED_RUSTFLAGS=${CARGO_ENCODED_RUSTFLAGS%$'\x1f'}
while read -r target abi compiler; do
    target_key=${target//-/_}
    target_upper=$(printf '%s' "$target_key" | tr '[:lower:]' '[:upper:]')
    export "CARGO_TARGET_${target_upper}_LINKER=$toolchain/${compiler}31-clang"
    export "CC_${target_key}=$toolchain/${compiler}31-clang"
    export "AR_${target_key}=$toolchain/llvm-ar"
    cargo build --locked --release --target "$target"
    mkdir -p "android/app/src/main/jniLibs/$abi"
    cp "target/$target/release/libfontlab.so" "android/app/src/main/jniLibs/$abi/"
done <<'ARCHITECTURES'
aarch64-linux-android arm64-v8a aarch64-linux-android
armv7-linux-androideabi armeabi-v7a armv7a-linux-androideabi
x86_64-linux-android x86_64 x86_64-linux-android
i686-linux-android x86 i686-linux-android
ARCHITECTURES
mkdir -p dist
printf 'sdk.dir=%s\n' "$sdk" > android/local.properties
if [[ "$release" -eq 1 ]]; then
    (cd android && bash gradlew assembleRelease)
    built_apk="android/app/build/outputs/apk/release/app-release.apk"
else
    (cd android && bash gradlew assembleDebug)
    built_apk="android/app/build/outputs/apk/debug/app-debug.apk"
fi
version=$(sed -n 's/^version = "\([^"]*\)"$/\1/p' Cargo.toml | head -n 1)
if [[ "$release" -eq 1 ]]; then
    apk="VFont-$version-universal.apk"
else
    apk="VFont-$version-debug-universal.apk"
fi
cp "$built_apk" "dist/$apk"
if command -v sha256sum >/dev/null; then
    (cd dist && sha256sum "$apk" > SHA256SUMS.txt)
else
    (cd dist && shasum -a 256 "$apk" > SHA256SUMS.txt)
fi
printf 'Built dist/%s\n' "$apk"
