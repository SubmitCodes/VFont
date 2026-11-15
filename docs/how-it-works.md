# How native integration works

[Compatibility](compatibility.md) | [Building](building.md)

## App structure

Rust validates and prepares font data and renders the font page. Java handles
Android pickers, native previews, installed icon sources, theme imports and the
Shizuku connection. The app uses package ID `dev.fontlab` and native library
`libfontlab.so` to preserve update compatibility.

```text
src/                      Rust font preparation, policy, JNI and font UI
android/app/src/main/     Android UI, native integration and app resources
scripts/                  Universal builds and dependency notices
docs/                     Usage, requirements and implementation notes
vendor/                   Two patched Rust UI dependencies and their licenses
```

The private Shizuku Binder checks the app caller's UID and runs as shell UID 2000.
It accepts bounded font descriptors/hashes, fixed restore operations, validated
icon/theme jobs and fixed diagnostic exports. It exposes no arbitrary-command or
arbitrary-source-path interface.

## Native transport and checks

VFont discovers the required Vivo backup interface signatures and transaction IDs
from the installed framework. It verifies stock UpSlide's identity, required
features and current state before dispatching a fixed bounded worker as system
UID 1000. Matching interfaces establish capabilities; they do not certify every
firmware's execution or recovery behavior.

ARM64, manufacturer, Android version and main-user requirements remain enforced
independently of universal APK packaging. Unfamiliar profiles require a font
execution check or an icon trial. Their proofs are tied to the relevant software
and style identities, so changes require new verification.

## Fonts

The selected bytes are validated and hashed, staged through an exclusively
created temporary file, then revalidated before Apply. The fixed transaction
checks the old target and hash, saves a separate candidate, claims a one-use
marker and atomically switches the supported active font link. A transaction-bound
receipt confirms completion. Refresh changes Vivo's typeface generation while
preserving font scale, then verifies the result.

Restore selects a readable, validated system font. Original system font files
are retained. No system partition is modified and imported files do not enter
Vivo's font-store catalog.

## Icons and themes

Icon edits retain originals and compose the complete managed selection. Supported
native refresh preserves unrelated choices. Transparent 1x1 artwork uses a small
corner adjustment only when native mask/render calibration accepts it; uncertain
renderers retain the unadjusted path. A software/style change requires a new trial.

Theme installation uses bounded archive validation, staged resources and a
persistent recovery journal. The first saved setup remains the restore target
across later applications. Selective operations merge only the requested resource
groups. Cleanup is limited to recorded owned files; foreign changes stop recovery.
Full-theme and font restoration are separate operations.

Stock package code, fonts, themes and icon packs are read from the device or
selected by the user. They are not bundled with VFont.
