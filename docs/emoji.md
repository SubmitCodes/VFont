# Text and emoji together

Android can use text fonts and emoji fallbacks in the same layout. The problem
here is gaining a controlled customization path on this particular Vivo build,
not a fundamental Android restriction on mixed text and emoji.

## Observed on the X200 Ultra

- Vivo's primary font resolves through `/system/fonts/VivoFont.ttf` to the
  active link in `/data/vfonts`.
- The system emoji fallback remains separate, in `NotoColorEmoji.ttf` and its
  flags companion. VFont does not replace those system files.
- A temporary combined font retained the selected text outlines and added a
  distinct COLRv0 green glyph for U+1F600 (😀).
- The chosen-font preview rendered the green glyph before apply. The default
  system preview retained the original yellow emoji at that point.
- After a guarded apply and Vivo's native refresh, Android's default rendering
  used the green glyph while the custom text stayed intact.
- Vivo's separate file picker also rendered that glyph in a temporary filename.
  This confirmed the result was not confined to VFont's selected-font preview.
- The original text font and font scale were restored, and the temporary
  Download files were removed. Neither system emoji file was modified.

**Conclusion:** importing a correctly combined text/emoji font is a viable
approach on the tested build. This one-glyph experiment does not prove every
emoji font or sequence will work, or that apps with independent emoji renderers
will follow the system choice.

## What VFont currently does

It imports existing compatible combined fonts and emoji-only fonts, preserves
their color/layout tables, and shows a native comparison. An emoji-only font
replaces the primary-font selection too, so text can return to system fallback.
Use a combined font to retain custom text. Source color formats still need to
be supported by Android's renderer.

## What an in-app merger would require

A general merger is not implemented in this release. It must allocate new
glyph IDs and consistently rebuild references in outlines, metrics, cmap,
GSUB/GPOS, variation data and color/bitmap tables. It must preserve emoji
variation selectors, skin tones, keycaps, regional flags and ZWJ ligatures, and
resolve overlaps without replacing ordinary digits/punctuation accidentally.

The controlled test constructed one glyph specifically for diagnosis. It is
not a general font-merging implementation and is not bundled with VFont.
Separate emoji-slot controls may be another approach if a writable Vivo path
is established later; none is claimed here.

Background: [AOSP font customization](https://source.android.com/docs/core/fonts/custom-font-fallback).
