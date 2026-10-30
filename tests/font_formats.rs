use fontlab::font::{prepare, validate};

const TTF: &[u8] = include_bytes!("fixtures/generated/regular.ttf");

#[test]
fn imports_common_formats_without_dropping_tables() {
    for (data, format) in [
        (TTF, "TTF"),
        (&include_bytes!("fixtures/generated/cff.otf")[..], "OTF"),
        (
            &include_bytes!("fixtures/generated/regular.woff")[..],
            "WOFF",
        ),
        (
            &include_bytes!("fixtures/generated/regular.woff2")[..],
            "WOFF2",
        ),
    ] {
        let prepared = prepare(data, 0).unwrap();
        assert_eq!(prepared.source_format, format);
        assert!(prepared.info.has_emoji && prepared.info.has_text);
        assert_eq!(prepared.faces.len(), 1);
        assert_eq!(validate(&prepared.bytes).unwrap().glyphs, 8);
    }
    for data in [
        &include_bytes!("fixtures/generated/collection.ttc")[..],
        &include_bytes!("fixtures/generated/collection.otc")[..],
    ] {
        let first = prepare(data, 0).unwrap();
        let second = prepare(data, 1).unwrap();
        assert_eq!(first.faces.len(), 2);
        assert_ne!(first.info.family, second.info.family);
        assert_ne!(first.info.sha256, second.info.sha256);
        assert!(validate(&second.bytes).is_ok());
        assert!(prepare(data, 2).is_err());
    }
    assert!(
        prepare(include_bytes!("fixtures/generated/variable.ttf"), 0)
            .unwrap()
            .info
            .variable
    );
    for data in [
        include_bytes!("fixtures/generated/color-v0.ttf").as_slice(),
        include_bytes!("fixtures/generated/color-v1.ttf").as_slice(),
    ] {
        let prepared = prepare(data, 0).unwrap();
        assert_eq!(prepared.info.color_formats, ["COLR/CPAL"]);
        assert_eq!(prepared.bytes, data); // Color tables, glyph IDs and layout stay intact.
    }
}

#[test]
fn rejects_corruption_wrong_faces_and_webfont_size_bombs() {
    for length in 0..12 {
        assert!(prepare(&TTF[..length], 0).is_err());
    }
    assert!(prepare(TTF, 1).is_err());
    let mut damaged = TTF.to_vec();
    let offset = u32::from_be_bytes(damaged[20..24].try_into().unwrap()) as usize;
    damaged[offset] ^= 1;
    assert!(prepare(&damaged, 0).unwrap_err().contains("checksum"));
    for input in [
        include_bytes!("fixtures/generated/regular.woff").as_slice(),
        include_bytes!("fixtures/generated/regular.woff2").as_slice(),
    ] {
        let mut damaged = input.to_vec();
        damaged[16..20].copy_from_slice(&u32::MAX.to_be_bytes());
        assert!(prepare(&damaged, 0).is_err());
    }
    let mut damaged = include_bytes!("fixtures/generated/regular.woff").to_vec();
    damaged[56..60].copy_from_slice(&u32::MAX.to_be_bytes());
    assert!(prepare(&damaged, 0).is_err());
    assert!(prepare(b"STARTFONT 2.1\nFONT legacy bitmap\n", 0).is_err());
}

#[test]
fn malformed_webfonts_never_escape_as_panics() {
    for input in [
        include_bytes!("fixtures/generated/regular.woff").as_slice(),
        include_bytes!("fixtures/generated/regular.woff2").as_slice(),
    ] {
        for length in 0..input.len() {
            assert!(std::panic::catch_unwind(|| prepare(&input[..length], 0)).is_ok());
        }
        for index in (0..input.len()).step_by(7) {
            let mut damaged = input.to_vec();
            damaged[index] ^= 0xff;
            assert!(std::panic::catch_unwind(|| prepare(&damaged, 0)).is_ok());
        }
    }
}
