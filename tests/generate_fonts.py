"""Rebuild tiny synthetic fixtures. Dev dependency: fonttools[woff]==4.60.1.

Every shape here is a simple rectangle created for VFont's tests. No device,
commercial, downloaded or user-provided font is included in these fixtures.
"""
from pathlib import Path
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.pens.t2CharStringPen import T2CharStringPen
from fontTools.ttLib import TTCollection, TTFont
from fontTools.colorLib.builder import buildCOLR, buildCPAL

DEST = Path(__file__).parent / "fixtures" / "generated"
DEST.mkdir(parents=True, exist_ok=True)
ORDER = [".notdef", "space", "A", "a", "alef", "han", "grinning", "emoji.layer"]
CMAP = {32: "space", 65: "A", 97: "a", 0x627: "alef", 0x4E2D: "han", 0x1F600: "grinning"}


def build(path, family, cff=False):
    builder = FontBuilder(1000, isTTF=not cff)
    builder.setupGlyphOrder(ORDER)
    builder.setupCharacterMap(CMAP)
    glyphs = {}
    for name in ORDER:
        pen = T2CharStringPen(600, None) if cff else TTGlyphPen(None)
        if name != "space":
            pen.moveTo((80, 0))
            pen.lineTo((520, 0))
            pen.lineTo((520, 700))
            pen.lineTo((80, 700))
            pen.closePath()
        glyphs[name] = pen.getCharString() if cff else pen.glyph()
    if cff:
        builder.setupCFF(family.replace(" ", "") + "-Regular", {"FullName": family, "FamilyName": family, "Weight": "Regular"}, glyphs, {})
    else:
        builder.setupGlyf(glyphs)
    builder.setupHorizontalMetrics({name: (600, 0) for name in ORDER})
    builder.setupHorizontalHeader(ascent=800, descent=-200)
    builder.setupNameTable({"familyName": family, "styleName": "Regular", "uniqueFontIdentifier": family + "-Regular", "fullName": family + " Regular", "psName": family.replace(" ", "") + "-Regular", "version": "Version 1.000"})
    builder.setupOS2(sTypoAscender=800, sTypoDescender=-200, usWinAscent=800, usWinDescent=200)
    builder.setupPost()
    builder.setupMaxp()
    builder.font["head"].created = 2082844800
    builder.font["head"].modified = 2082844800
    builder.font.recalcTimestamp = False
    builder.save(path)


build(DEST / "regular.ttf", "VFont Test")
build(DEST / "alternate.ttf", "VFont Alternate")
build(DEST / "cff.otf", "VFont CFF", cff=True)
collection = TTCollection()
collection.fonts = [TTFont(DEST / "regular.ttf", recalcTimestamp=False), TTFont(DEST / "alternate.ttf", recalcTimestamp=False)]
collection.save(DEST / "collection.ttc")
collection.fonts = [TTFont(DEST / "cff.otf", recalcTimestamp=False), TTFont(DEST / "regular.ttf", recalcTimestamp=False)]
collection.save(DEST / "collection.otc")

for flavor in ["woff", "woff2"]:
    font = TTFont(DEST / "regular.ttf", recalcTimestamp=False)
    font.flavor = flavor
    font.save(DEST / ("regular." + flavor))

variable = FontBuilder(font=TTFont(DEST / "regular.ttf", recalcTimestamp=False))
variable.setupFvar([("wght", 100, 400, 900, "Weight")], [])
variable.setupGvar({name: [] for name in ORDER})
variable.save(DEST / "variable.ttf")

for version in [0, 1]:
    font = TTFont(DEST / "regular.ttf", recalcTimestamp=False)
    paint = [("emoji.layer", 0)] if version == 0 else {"Format": 10, "Glyph": "emoji.layer", "Paint": {"Format": 2, "PaletteIndex": 0, "Alpha": 1.0}}
    font["COLR"] = buildCOLR({"grinning": paint}, version=version, glyphMap=font.getReverseGlyphMap())
    font["CPAL"] = buildCPAL([[(0.0, 1.0, 0.0, 1.0)]])
    font.save(DEST / f"color-v{version}.ttf")

print("Generated", len(list(DEST.iterdir())), "synthetic font fixtures")
