# Build a universal APK

[Back to README](../README.md) | [Implementation](how-it-works.md)

## Prerequisites

- Stable Rust with Cargo and rustup.
- JDK 17 or newer, Android SDK platform **36**, and Android NDK **28.2.13676358**.
- Android SDK Build Tools and the Gradle wrapper included in the repository.
- `ANDROID_HOME` pointing to your SDK. Windows also checks the usual SDK location.

Install all four Rust Android targets:

```sh
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
```

## Build

Windows PowerShell:

```powershell
.\scripts\build.ps1 -Release
```

Linux/macOS, with `ANDROID_HOME` set:

```sh
bash scripts/build.sh --release
```

Both wrappers compile the native library for **arm64-v8a, armeabi-v7a, x86_64 and
x86**, then package all four in one standalone APK. They use API 31 for linking
and 16 KiB native segment alignment. The Windows wrapper is locally validated;
Linux/macOS execution has not been verified for this release.

Output:

```text
dist/VFont-1.0.0-universal.apk
dist/SHA256SUMS.txt
```

Omit `-Release` / `--release` for a development APK named
`VFont-1.0.0-debug-universal.apk`. It has debugging enabled and is not the published
release. No device is needed to build.

## Version and signing

`Cargo.toml` owns the display version; Gradle reads it for Android's version name.
Android's separately increasing `versionCode` is in `android/app/build.gradle.kts`.
The 1.0.0 release uses code **100**, above the earlier development builds.

For your own release signing, create ignored `android/keystore.properties`:

```properties
storeFile=/absolute/path/to/your-private-keystore.p12
storePassword=YOUR_STORE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_KEY_PASSWORD
```

Alternatively set `VFONT_KEYSTORE`, `VFONT_KEYSTORE_PASS`, `VFONT_KEY_ALIAS` and
`VFONT_KEY_PASS`. Without release signing configuration, a local build warns and
uses the developer's Android debug key. Official publishing requires the retained
project key. Never commit or upload a keystore, its configuration or passwords.
Back up the private key securely; a different key cannot update an existing install.

The official 1.0.0 retains the identity used by earlier development previews for
update compatibility. Its certificate subject is still **Android Debug**; the APK
itself is a non-debuggable release build. The signing key is never distributed.

## Source and dependencies

Cargo and Gradle dependency versions are locked/pinned in the repository.
`vendor/PATCHES.md` explains the two required UI dependency patches. Regenerate
notices after dependency changes with `python scripts/notices.py`.

Public source checks run Rust formatting and library Clippy. Local device and
validation tooling is kept outside the published source. Compilation and package
verification do not establish compatibility with additional phones.
