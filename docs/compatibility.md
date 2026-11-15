# Compatibility

[User guide](guide.md) | [Back to README](../README.md)

## Supported installation and native changes

| Area | Requirement |
| --- | --- |
| Installation, font preview and basic reports | Android 12+; ARM64, ARM32, x86-64 or x86. |
| Native transport | ARM64 Vivo/iQOO on Android 14+, main Android user, Shizuku shell UID 2000. |
| Stock service | Enabled, persistent system UpSlide at UID 1000, compatible native backup interfaces and no custom backup agent. |
| Font changes | Vivo's supported font links, readable active/default font and compatible refresh API. |
| Icon changes | Stock Vivo launcher, supported native icon storage and refresh APIs, successful trial for the software/style profile. |
| Full themes | Compatible native theme/wallpaper/keyguard APIs, supported resources and recoverable static wallpapers. |

Universal describes the APK's packaged CPU architectures. It does not bypass
firmware checks or enable native writes on ARM32/x86 phones and emulators.
Installing UpSlide alone cannot make an unsupported ROM compatible.

## Physically verified scope

Vivo X200 Ultra / V2454A, Chinese OriginOS 7, including builds
`PD2454B_A_17.2.10.6.W10` and `PD2454B_A_17.2.11.5.W10.V000L1`.
Native font changes, individual and cumulative icon edits, theme application,
component operations and recovery have been exercised on that device.
Freeform icon transparency was visually checked on its native 1x1 icon layout.

Other devices, Global/Funtouch ROMs, larger icon layouts and every possible theme
or pack have not been physically verified. Runtime checks establish the required
capabilities, not complete compatibility. Untested profiles need the relevant
native-access check or icon trial. Firmware, component and icon-style changes
invalidate the corresponding approval. Restore remains available where its
required native capabilities are present.

## Font formats

TTF and CFF OTF, variable fonts, TTC/OTC face selection, WOFF and WOFF2 are accepted.
Web fonts are decoded locally. Table structure and input/output sizes are bounded;
malformed data is rejected before native Apply. Preview is not proof that every
system app will render a font correctly. Color emoji depend on the font's tables
and Android's renderer.

One file is active at a time; VFont does not merge text and emoji fonts. Restore
uses the existing system default font, rather than a previously selected store
font. Firmware-update persistence is not established.

## Icons and themes

Pack/app/image imports are static artwork. Live custom Calendar/Clock activation
is disabled; existing experimental Calendar recovery remains available.
Third-party launchers are unsupported for native icon writes.

Supported themes are bounded ZIP-based Vivo `.itz` archives with root metadata.
Unsupported, unsafe or bundled-font themes are rejected. Static wallpapers and
compatible lock-screen engines are required for the relevant operations. See
[theme behavior](themes.md).

Missing or inaccessible APIs block the corresponding feature. VFont does not
patch, downgrade or replace stock packages to force support. A successful check
or one successful Apply is not proof of safe behavior on every future firmware.
