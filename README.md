# VFont

Native font imports for the **Chinese-ROM Vivo X200 Ultra**, with a Rust UI and
core, a small Android bridge, and Shizuku. No Themes downgrade, root, bootloader
unlock or manual file-manager modifications are required.

**Device-specific preview:** the backend only permits font changes on the
verified firmware below. This is not a universal Vivo compatibility claim.

| Checked item | Verified value |
| --- | --- |
| Device | Vivo X200 Ultra / V2454A |
| Firmware | PD2454B_A_17.2.10.6.W10 / OriginOS 7 |
| Game Cube | 15.0.05.01_de |
| Shizuku | API 13+, started as shell UID 2000 |

## Font support

- **TTF and OTF**, including CFF outlines and variable fonts at their default axis values.
- **TTC and OTC collections**, with a face chooser and standalone face extraction.
- **WOFF and WOFF2**, decoded locally in Rust before Android loads them.
- **Color and emoji fonts**, preserving their OpenType color, mapping and shaping tables.
- Native preview, emoji comparison, checksum validation and a **Restore system Roboto** button.

Files are identified by content, not their extension. Imports and prepared output
are limited to 64 MiB; collections are limited to 64 faces. Legacy Type 1, EOT,
BDF/PCF, FON and SVG-font documents require conversion outside this version.
An OpenType font containing SVG glyphs is different from an SVG-font document.
See [format compatibility](docs/compatibility.md) for renderer limits.

## Text and emoji together

**A combined text/emoji font works in the tested Vivo native font slot.** A
controlled test retained the chosen text font and changed one emoji's color
glyph in Android's default rendering and Vivo's separate file picker. The
original font and size were restored afterward.

VFont can import an existing combined font. **A general in-app font merger is
not implemented yet.** Emoji-only fonts may return text to system fallback;
some apps use their own emoji regardless of the system font. The experiment
does not establish compatibility with every emoji font, sequence or app.
See [emoji findings](docs/emoji.md).

## Install and use

1. Build and install a local APK using the instructions below. **No APK is
   published yet.** Local preview builds use Android's debug signing certificate.
2. Start Shizuku through your manager, open **VFont**, tap **Connect**, and grant access.
3. Once, tap **Set up Game Cube**, find **VFont** in the supported-games list and
   enable its switch. The APK advertises itself as a game; this firmware still
   requires the one-time switch.
4. Choose a font with Android's file picker. For a collection, choose its face.
   Review the native preview before applying.
5. Tap **Apply font**. VFont briefly shows Home and reopens itself. Swipe in
   Game Cube, open its performance pane, and leave **CPU / GPU / FPS** visible
   until the result appears (up to 35 seconds). Close the sidebar to view it.

**Restore system Roboto** uses the same performance-pane step. It restores the
phone's system Roboto, not a previously selected store font. A native refresh
can restart other apps; restart Shizuku if Vivo stops it. Applying a font does
not require a reboot. The native method survived a reboot on the tested phone;
firmware-update persistence is untested.

Selecting a store font or applying a theme can replace the custom selection.
Missing glyphs use Android's existing fallback fonts. Custom imports are not
added to Vivo's font-store catalog.

## Build

Requirements: current stable Rust, JDK 17+, Android SDK platform 36 and
NDK **28.2.13676358**. Install the Rust target:

```sh
rustup target add aarch64-linux-android
```

Windows:

```powershell
.\scripts\build.ps1
```

Linux/macOS, with `ANDROID_HOME` set:

```sh
bash scripts/build.sh
```

Output: `dist/VFont-0.2.0.apk` and `dist/SHA256SUMS.txt`, arm64, min SDK 34.
The Windows build is device-tested; the Unix wrapper needs validation on those
hosts. Gradle reads the version from Cargo.toml. No signing key is included.

## Development

```sh
cargo fmt --check
cargo test --locked
cargo clippy --locked --all-targets -- -D warnings
```

Tests use committed, synthetic fixtures and need no private font or phone.
[CONTRIBUTING.md](CONTRIBUTING.md) covers Android checks and fixture regeneration.
The CI workflow runs these host checks on a standard public-repository runner,
without paid runners, artifact uploads or cache storage.

```text
src/                    Rust font imports, guarded transaction, Android JNI, UI
android/app/src/main/   Android file picker, preview and Shizuku Binder bridge
android/app/src/androidTest/  Device import/rendering and lifecycle check
tests/                  Portable format/corruption tests and generated fixtures
examples/inspect.rs     Read-only local font inspection helper
scripts/                Windows and Unix build entry points
docs/                   Compatibility, mechanism and verification findings
vendor/                 Two narrowly patched UI dependencies and their licenses
```

The Android package and native library remain `dev.fontlab` / `libfontlab.so`
to preserve upgrades, Shizuku authorization and Game Cube registration from the
original local prototype. The visible name and repository are **VFont**.

## Mechanism and boundaries

Shizuku supplies shell transport. The reviewed Game Cube pathway is still
needed for a fixed, guarded transaction in Vivo's data font directory. It
creates a separately named, verified candidate, atomically switches the active
font link, then invokes Vivo's native configuration refresh while preserving
font size. It does not modify system partitions or uninstall Themes.

The app has no network, accessibility or broad storage permission and exposes
no arbitrary-command interface. Font structure checks and a native preview do
not guarantee that every file behaves correctly in every system renderer.
See [how it works](docs/how-it-works.md) and [verified behavior](docs/verification.md).

## License and credits

**No project license has been selected.** Publishing this repository does not
grant a general license to reuse VFont's own code. Bundled dependencies retain
their original licenses and notices; see [third-party notices](THIRD_PARTY_NOTICES.txt)
and [local dependency patches](vendor/PATCHES.md).

The initial research used [GC-Tune](https://github.com/Bebrazui/GC-Tune) and the
[Vivo custom-font guide](https://ads776.github.io/vivo-x300u-custom-font/), followed
by static inspection and controlled checks on the owner's phone. VFont does not
bundle either project's APK or any user-provided font.
