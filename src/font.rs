//! Bounded imports. Only checked, standalone OpenType data reaches the font service.
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::HashSet;

pub const MAX_FONT_BYTES: usize = 64 * 1024 * 1024;
const MAX_TABLES: usize = 256;
const MAX_FACES: usize = 64;

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct FontInfo {
    pub family: String,
    pub style: String,
    pub sha256: String,
    pub bytes: usize,
    pub glyphs: u16,
    pub format: String,
    pub variable: bool,
    pub color_formats: Vec<String>,
    pub has_emoji: bool,
    pub has_text: bool,
}

#[derive(Debug)]
pub struct PreparedFont {
    pub bytes: Vec<u8>,
    pub info: FontInfo,
    pub source_format: String,
    pub faces: Vec<String>,
    pub face_index: usize,
}

pub fn digest(bytes: &[u8]) -> String {
    format!("{:x}", Sha256::digest(bytes))
}

fn word(data: &[u8], at: usize) -> Result<u32, String> {
    Ok(u32::from_be_bytes(
        data.get(at..at + 4)
            .ok_or("Truncated font data.")?
            .try_into()
            .unwrap(),
    ))
}

fn short(data: &[u8], at: usize) -> Result<usize, String> {
    Ok(u16::from_be_bytes(
        data.get(at..at + 2)
            .ok_or("Truncated font data.")?
            .try_into()
            .unwrap(),
    ) as usize)
}

fn size_check(data: &[u8]) -> Result<(), String> {
    if !(12..=MAX_FONT_BYTES).contains(&data.len()) {
        return Err("Choose a font between 12 bytes and 64 MiB (also after decompression).".into());
    }
    Ok(())
}

fn checksum(data: &[u8], head: bool) -> u32 {
    data.chunks(4)
        .enumerate()
        .fold(0u32, |sum, (index, chunk)| {
            let mut bytes = [0; 4];
            bytes[..chunk.len()].copy_from_slice(chunk);
            if head && index == 2 {
                bytes = [0; 4];
            }
            sum.wrapping_add(u32::from_be_bytes(bytes))
        })
}

#[derive(Clone)]
struct Table {
    tag: [u8; 4],
    checksum: u32,
    offset: usize,
    length: usize,
}

/// Collections may share tables between faces, but a face cannot overlap itself
/// or any face's table directory.
fn tables(
    data: &[u8],
    directory: usize,
    reserved: &[(usize, usize)],
) -> Result<Vec<Table>, String> {
    if !matches!(
        word(data, directory)?,
        0x0001_0000 | 0x4f54_544f | 0x7472_7565
    ) {
        return Err("Unsupported font. Use OpenType/TrueType, TTC/OTC, WOFF or WOFF2.".into());
    }
    let count = short(data, directory + 4)?;
    if count == 0 || count > MAX_TABLES || directory + 12 + count * 16 > data.len() {
        return Err("Invalid font table directory.".into());
    }
    let mut tags = HashSet::new();
    let mut spans = Vec::new();
    let mut result = Vec::with_capacity(count);
    for index in 0..count {
        let entry = directory + 12 + index * 16;
        let tag: [u8; 4] = data[entry..entry + 4].try_into().unwrap();
        if !tags.insert(tag) {
            return Err("Duplicate font table.".into());
        }
        let expected = word(data, entry + 4)?;
        let offset = word(data, entry + 8)? as usize;
        let length = word(data, entry + 12)? as usize;
        let end = offset
            .checked_add(length)
            .ok_or("Font table is out of bounds.")?;
        if end > data.len()
            || (length > 0
                && (offset & 3 != 0 || reserved.iter().any(|&(a, b)| offset < b && end > a)))
        {
            return Err("Font table is out of bounds, misaligned, or overlaps a directory.".into());
        }
        if tag == *b"head" && (length < 54 || word(data, offset + 12)? != 0x5f0f_3cf5) {
            return Err("Invalid OpenType header.".into());
        }
        if checksum(&data[offset..end], tag == *b"head") != expected {
            return Err(format!(
                "Font table checksum failed: {}.",
                String::from_utf8_lossy(&tag)
            ));
        }
        if length > 0 {
            spans.push((offset, end));
        }
        result.push(Table {
            tag,
            checksum: expected,
            offset,
            length,
        });
    }
    spans.sort_unstable();
    if spans.windows(2).any(|p| p[0].1 > p[1].0) {
        return Err("Overlapping font tables.".into());
    }
    for required in [b"head", b"hhea", b"maxp", b"hmtx", b"cmap", b"name"] {
        if !tags.contains(required) {
            return Err("The font is missing required OpenType tables.".into());
        }
    }
    for (a, b) in [(b"glyf", b"loca"), (b"CBDT", b"CBLC"), (b"COLR", b"CPAL")] {
        if tags.contains(a) != tags.contains(b) {
            return Err("The font has an incomplete outline/color table pair.".into());
        }
    }
    if ![b"glyf", b"CFF ", b"CFF2", b"CBDT", b"sbix", b"SVG "]
        .iter()
        .any(|tag| tags.contains(*tag))
    {
        return Err("No supported outlines or bitmap glyphs were found.".into());
    }
    Ok(result)
}

fn metadata(data: &[u8], face_index: u32, records: &[Table]) -> Result<FontInfo, String> {
    let face = ttf_parser::Face::parse(data, face_index).map_err(|_| "OpenType parsing failed.")?;
    if face.number_of_glyphs() == 0 {
        return Err("The font has no glyphs.".into());
    }
    let name = |id| {
        face.names()
            .into_iter()
            .find_map(|n| (n.name_id == id).then(|| n.to_string()).flatten())
            .map(|s| s.chars().filter(|c| !c.is_control()).take(120).collect())
    };
    let has = |tag: &[u8; 4]| records.iter().any(|t| &t.tag == tag);
    let mut color_formats = Vec::new();
    for (tag, label) in [
        (b"COLR", "COLR/CPAL"),
        (b"CBDT", "CBDT/CBLC"),
        (b"sbix", "sbix"),
        (b"SVG ", "SVG"),
    ] {
        if has(tag) {
            color_formats.push(label.into());
        }
    }
    Ok(FontInfo {
        family: name(1).unwrap_or_else(|| "Custom font".into()),
        style: name(2).unwrap_or_else(|| "Regular".into()),
        sha256: digest(data),
        bytes: data.len(),
        glyphs: face.number_of_glyphs(),
        format: if has(b"CFF ") || has(b"CFF2") {
            "OTF"
        } else {
            "TTF"
        }
        .into(),
        variable: face.is_variable(),
        color_formats,
        has_emoji: (0x1f300..=0x1faff)
            .filter_map(char::from_u32)
            .any(|c| face.glyph_index(c).is_some()),
        has_text: ['A', 'a', 'ا', '中']
            .iter()
            .any(|&c| face.glyph_index(c).is_some()),
    })
}

/// The privileged service rechecks prepared bytes; it never decompresses webfonts.
pub fn validate(data: &[u8]) -> Result<FontInfo, String> {
    size_check(data)?;
    let end = 12 + short(data, 4)? * 16;
    let records = tables(data, 0, &[(0, end)])?;
    metadata(data, 0, &records)
}

fn unpack(data: &[u8]) -> Result<(Vec<u8>, &'static str), String> {
    size_check(data)?;
    let magic = word(data, 0)?;
    if matches!(magic, 0x774f_4646 | 0x774f_4632) {
        let declared = word(data, 16)? as usize;
        let count = short(data, 12)?;
        if !(12..=MAX_FONT_BYTES).contains(&declared) || count == 0 || count > MAX_TABLES {
            return Err("Webfont declares an invalid or excessive output size.".into());
        }
        // Bound WOFF1 table allocations independently of the untrusted output-size hint.
        if magic == 0x774f_4646 {
            if 44 + count * 20 > data.len() {
                return Err("Truncated WOFF directory.".into());
            }
            let mut total = 12 + count * 16;
            for index in 0..count {
                let entry = 44 + index * 20;
                let length = word(data, entry + 12)? as usize;
                total = total
                    .checked_add((length + 3) & !3)
                    .ok_or("WOFF output is too large.")?;
                if total > MAX_FONT_BYTES || word(data, entry + 8)? as usize > length {
                    return Err("WOFF table sizes are invalid or too large.".into());
                }
            }
            if total != declared {
                return Err("WOFF output size does not match its tables.".into());
            }
        }
        let output = if magic == 0x774f_4646 {
            wuff::decompress_woff1(data)
        } else {
            wuff::decompress_woff2(data)
        }
        .map_err(|e| format!("Webfont decompression failed: {e}"))?;
        size_check(&output)?;
        return Ok((
            output,
            if magic == 0x774f_4646 {
                "WOFF"
            } else {
                "WOFF2"
            },
        ));
    }
    Ok((
        data.to_vec(),
        if magic == 0x7474_6366 {
            "TTC/OTC"
        } else if magic == 0x4f54_544f {
            "OTF"
        } else {
            "TTF"
        },
    ))
}

fn extract_face(data: &[u8], directory: usize, records: &[Table]) -> Result<Vec<u8>, String> {
    let mut records: Vec<_> = records
        .iter()
        .filter(|t| t.tag != *b"DSIG")
        .cloned()
        .collect();
    records.sort_by_key(|t| t.tag);
    let count = records.len();
    let mut output = vec![0u8; 12 + count * 16];
    output[..4].copy_from_slice(&data[directory..directory + 4]);
    output[4..6].copy_from_slice(&(count as u16).to_be_bytes());
    let selector = count.ilog2() as u16;
    let range = (1u16 << selector) * 16;
    output[6..8].copy_from_slice(&range.to_be_bytes());
    output[8..10].copy_from_slice(&selector.to_be_bytes());
    output[10..12].copy_from_slice(&((count * 16) as u16 - range).to_be_bytes());
    let mut head = None;
    for (index, table) in records.iter().enumerate() {
        let offset = output.len();
        let entry = 12 + index * 16;
        output[entry..entry + 4].copy_from_slice(&table.tag);
        output[entry + 4..entry + 8].copy_from_slice(&table.checksum.to_be_bytes());
        output[entry + 8..entry + 12].copy_from_slice(&(offset as u32).to_be_bytes());
        output[entry + 12..entry + 16].copy_from_slice(&(table.length as u32).to_be_bytes());
        output.extend_from_slice(&data[table.offset..table.offset + table.length]);
        output.resize((output.len() + 3) & !3, 0);
        if output.len() > MAX_FONT_BYTES {
            return Err("Extracted face is too large.".into());
        }
        if table.tag == *b"head" {
            output[offset + 8..offset + 12].fill(0);
            head = Some(offset);
        }
    }
    let adjustment = 0xb1b0_afbau32.wrapping_sub(checksum(&output, false));
    let head = head.ok_or("Missing head table.")?;
    output[head + 8..head + 12].copy_from_slice(&adjustment.to_be_bytes());
    Ok(output)
}

pub fn prepare(input: &[u8], face_index: usize) -> Result<PreparedFont, String> {
    // Decoders process untrusted files in the unprivileged app. A decoder bug
    // must become an import error, never unwind across JNI or alter a phone font.
    std::panic::catch_unwind(|| prepare_checked(input, face_index))
        .map_err(|_| "The font decoder rejected malformed input.".to_string())?
}

fn prepare_checked(input: &[u8], face_index: usize) -> Result<PreparedFont, String> {
    let (data, source_format) = unpack(input)?;
    if word(&data, 0)? != 0x7474_6366 {
        if face_index != 0 {
            return Err("This file contains only one font face.".into());
        }
        let info = validate(&data)?;
        let faces = vec![format!("{} — {}", info.family, info.style)];
        return Ok(PreparedFont {
            bytes: data,
            info,
            source_format: source_format.into(),
            faces,
            face_index,
        });
    }
    if !matches!(word(&data, 4)?, 0x0001_0000 | 0x0002_0000) {
        return Err("Unsupported font collection version.".into());
    }
    let count = word(&data, 8)? as usize;
    if count == 0 || count > MAX_FACES || face_index >= count || 12 + count * 4 > data.len() {
        return Err("Invalid font collection or face index (maximum 64 faces).".into());
    }
    let mut directories = Vec::with_capacity(count);
    let mut reserved = vec![(
        0,
        12 + count * 4
            + if word(&data, 4)? == 0x0002_0000 {
                12
            } else {
                0
            },
    )];
    for index in 0..count {
        let start = word(&data, 12 + index * 4)? as usize;
        let end = start + 12 + short(&data, start + 4)? * 16;
        if start & 3 != 0 || end > data.len() {
            return Err("Invalid collection table directory.".into());
        }
        directories.push(start);
        reserved.push((start, end));
    }
    let mut ranges = reserved.clone();
    ranges.sort_unstable();
    if ranges.windows(2).any(|pair| pair[0].1 > pair[1].0) {
        return Err("Collection directories overlap.".into());
    }
    let mut faces = Vec::with_capacity(count);
    let mut chosen = None;
    for (index, &directory) in directories.iter().enumerate() {
        let records = tables(&data, directory, &reserved)?;
        let info = metadata(&data, index as u32, &records)?;
        faces.push(format!("{} — {}", info.family, info.style));
        if index == face_index {
            chosen = Some(extract_face(&data, directory, &records)?);
        }
    }
    let bytes = chosen.ok_or("Collection face was not found.")?;
    let info = validate(&bytes)?;
    Ok(PreparedFont {
        bytes,
        info,
        source_format: source_format.into(),
        faces,
        face_index,
    })
}
