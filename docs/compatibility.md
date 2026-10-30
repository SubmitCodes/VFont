# Compatibility

VFont accepts font **content**, not just a filename extension. Preparation runs
inside the ordinary app process. Only standalone, checked OpenType bytes are
passed to the Shizuku service, which validates them again before staging.

| Input | Handling | Verification on the tested phone |
| --- | --- | --- |
| TrueType TTF | Preserve original bytes | Native import, apply, restore and earlier reboot persistence |
| CFF OpenType OTF | Preserve original bytes; Vivo's fixed filename still ends in `.ttf` | Native import and live apply/restore |
| TTC / OTC | Choose a face, extract its tables, repair offsets/checksum adjustment, omit obsolete DSIG | Two-face TrueType and mixed CFF/TrueType native imports |
| Variable TTF / OTF | Preserve variation tables; use default axis values | Synthetic variable TTF native import; axis controls not implemented |
| WOFF / WOFF2 | Decode locally with wuff, then run the same table checks | Native imports of generated webfonts |
| COLRv0 / COLRv1 | Preserve COLR, CPAL, glyph mapping and layout tables | Both versions rendered a checked custom green emoji glyph |
| CBDT / CBLC, sbix, SVG OpenType color tables | Preserve tables; bitmap-only OpenType data is accepted | Rendering depends on the Android build; not all encodings/strikes have been device-tested |

The color formats above are **tables inside a font**, not independent font-file
extensions. Android accepting a font file does not establish that it supports
every rendering mechanism in that file. Check the native preview. In
particular, Apple-specific bitmap/color data and SVG glyphs may need conversion
to an Android-supported representation.

Legacy Type 1/PFB/PFA, EOT, FON/FNT, BDF/PCF, dfont and standalone SVG-font
documents are not imported. ZIP/APK/theme packages are containers, not fonts;
extract a compatible font first. The app rejects unsupported content with an
error instead of treating it as a TTF.

Limits: 64 MiB input/prepared data, 64 collection faces and 256 tables per face.
WOFF2's decoder also has its own bounded intermediate-output limit. The app
checks directories, offsets, alignment, overlap, required tables and table
checksums, and contains decoder panics as import errors. These checks are not
a guarantee of correct rendering for every glyph in every file.

Some apps use bundled typefaces or independent emoji graphics. Missing glyphs
and unsupported sequences may fall back to the existing system fonts. Store
fonts/themes can replace VFont's selection; firmware updates can change or
close the underlying Game Cube pathway.

The firmware guard is deliberately stricter than the APK's minimum Android
version. A working preview on another model or firmware does not enable apply.
