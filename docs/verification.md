# Verification

Verified on the owner-authorized Vivo X200 Ultra, Chinese ROM, OriginOS 7,
firmware PD2454B_A_17.2.10.6.W10 and Game Cube 15.0.05.01_de, on 2026-10-31.

## Portable checks

- Generated TTF, CFF OTF, TTC, OTC, variable TTF, WOFF, WOFF2 and COLRv0/v1
  fixtures import correctly. Extracted faces validate as standalone fonts.
- Color font bytes are preserved for standalone SFNT imports.
- Corrupt checksums, invalid bounds/face indices and excessive webfont sizes
  are rejected. Truncated/mutated webfont samples do not escape as panics.
- Transaction checks reject unintended paths, traversal, shell metacharacters,
  malformed checksums/IDs and staging over protected font files.
- Fixture regeneration was checked for deterministic output. No private test
  font is required for the public checks.

## Device checks

- All ten synthetic fixtures imported through the real Rust JNI preparation
  path and loaded with Android's native Typeface builder.
- COLRv0 and COLRv1 glyphs rendered the expected green pixels, not fallback emoji.
- Two activity recreations restarted the Rust UI without the previous ANR.
- The locally built VFont 0.2.0 APK (version code 7) repeated the ten-import,
  color-pixel and two-recreation checks successfully. Its installed bytes
  matched the PC build output. The separate test APK was removed afterward.
- The user's original CFF OTF applied through Vivo's native font slot and was
  restored to the prior TTF afterward. The active file/hash were verified.
- A combined text/COLRv0 emoji probe changed a glyph in both default Android
  rendering and Vivo's separate file picker. The original TTF was restored and
  the two temporary Download test files removed.
- The 0.9 font-size setting was preserved across the controlled transactions.
- Earlier tests verified system Roboto restore and custom TTF reapply, and the
  owner confirmed native-font persistence after reboot.

These results describe the tested files/build. Variable-axis controls, every
bitmap/SVG color encoding, full emoji-set merges, all apps/locales, other Vivo
firmware and firmware-update persistence are not verified. The Android import
test changes no system fonts or phone settings; the live round-trips above were
separate, explicitly controlled checks.

Raw phone screenshots, logs and user fonts stay outside the public repository.
