# Development

Start with the portable Rust checks in README.md. Cargo.lock is committed;
vendored dependency edits are limited to the changes in vendor/PATCHES.md.

## Android verification

Build and install the matching app APK first. With an explicitly selected,
authorized test device, install the **separate test APK** and run:

```sh
cd android
./gradlew :app:assembleDebugAndroidTest
adb -s YOUR_SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s YOUR_SERIAL shell am instrument -w dev.fontlab.test/dev.fontlab.LifecycleCheck
```

On Windows, use `gradlew.bat`. The phone must be unlocked. The test imports ten
synthetic fixtures through the real Rust JNI path, loads them with Android's
Typeface builder, checks custom green COLRv0/COLRv1 glyph pixels, and recreates
the activity twice. It never changes the system font or phone settings.
The test APK can be removed afterward with `adb -s YOUR_SERIAL uninstall dev.fontlab.test`.

An optional Android lint check is:

```sh
cargo clippy --locked --target aarch64-linux-android --lib -- -D warnings
```

Live font transactions require a separately agreed test plan, a verified
baseline target/hash, a native preview, and restoration afterward. Do not widen
the firmware guard based only on a model name or a successful import preview.
Never use flashing, system partition changes, package downgrades, or destructive
commands to test this workflow. Preserve transaction replay markers.

## Fixtures

The committed fixtures contain simple rectangles created by
`tests/generate_fonts.py`, with TTF/CFF, a two-face collection, WOFF/WOFF2,
a variable axis and COLRv0/COLRv1 color tables. They contain no user/device fonts.
Regenerate in an isolated Python environment with `fonttools[woff]==4.60.1`:

```sh
python tests/generate_fonts.py
```

Android tests read this same fixture directory through Gradle's test assets
source set; no duplicate asset copies are maintained. Keep generated fixtures
small and deterministic. The public APK does not bundle the test fonts.

## Scope

Keep font parsing and transaction decisions in Rust; Java handles Android-only
APIs. Do not add arbitrary command execution, exported command receivers,
telemetry, networking, or generalized privileged file operations. Avoid adding
support for a format merely by accepting its extension: prepare and validate
its actual content and test Android loading.

Do not commit phone screenshots, raw logs, serial numbers, private fonts,
compiled APKs, machine-specific SDK paths, credentials or signing keys. The
ignored `verification/` and `dist/` directories are for local evidence/builds.
This repository currently has no project license; retain all third-party notices.
After changing dependencies, regenerate the root and APK notices with
`python scripts/notices.py`. Supplemental upstream texts are kept in
`docs/third-party/`; bundled UI-font licenses are included in the generated notices.
