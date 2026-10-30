//! Read-only developer helper: cargo run --example inspect -- path/to/font [face-index]
use std::{env, fs::File, io::Read};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let path = env::args()
        .nth(1)
        .ok_or("Usage: inspect FONT [FACE_INDEX]")?;
    let index = env::args()
        .nth(2)
        .map(|s| s.parse())
        .transpose()?
        .unwrap_or(0);
    let mut input = Vec::new();
    File::open(path)?
        .take(fontlab::font::MAX_FONT_BYTES as u64 + 1)
        .read_to_end(&mut input)?;
    let font = fontlab::font::prepare(&input, index)?;
    println!(
        "{}",
        serde_json::to_string_pretty(&serde_json::json!({
            "font":font.info,"source_format":font.source_format,"faces":font.faces,"face_index":font.face_index
        }))?
    );
    Ok(())
}
