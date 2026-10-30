use serde::{Deserialize, Serialize};

pub const ACTIVE: &str = "/data/vfonts/VivoFont.ttf";
pub const ROBOTO: &str = "/system/fonts/Roboto-Regular.ttf";
pub const BUILD: &str = "PD2454B_A_17.2.10.6.W10";
pub const GAME: &str = "dev.fontlab";

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct FontState {
    pub target: String,
    pub sha256: String,
}

pub fn hex(value: &str, size: usize) -> bool {
    value.len() == size
        && value
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

pub fn allowed_target(path: &str) -> bool {
    if path == ROBOTO {
        return true;
    }
    let Some(name) = path.strip_prefix("/data/vfonts/") else {
        return false;
    };
    !name.is_empty()
        && name.len() <= 200
        && name != "VivoFont.ttf"
        && name.ends_with(".ttf")
        && name
            .chars()
            .all(|c| c.is_alphanumeric() || "_-. ".contains(c))
}

pub fn quote(value: &str) -> String {
    format!("'{}'", value.replace('\'', "'\\''"))
}

pub fn source(tx: &str) -> Result<String, String> {
    if !hex(tx, 32) {
        return Err("Invalid transaction ID.".into());
    }
    Ok(format!("/storage/emulated/0/Download/fontlab-{tx}.ttf"))
}

pub fn candidate(hash: &str) -> Result<String, String> {
    if !hex(hash, 64) {
        return Err("Invalid font checksum.".into());
    }
    Ok(format!("/data/vfonts/fontlab-{hash}.ttf"))
}

/// Only the font directory link and a verified, separately named copy may be written.
pub fn script(from: &FontState, to: &FontState, tx: &str, stage: bool) -> Result<String, String> {
    if !allowed_target(&from.target)
        || !allowed_target(&to.target)
        || !hex(&from.sha256, 64)
        || !hex(&to.sha256, 64)
        || !hex(tx, 32)
    {
        return Err("Unexpected font path, checksum or transaction ID.".into());
    }
    if stage && to.target != candidate(&to.sha256)? {
        return Err("Only a separate VFont copy can be staged.".into());
    }
    if !stage && to.target != ROBOTO {
        return Err("Restore only targets system Roboto.".into());
    }
    let mut result = format!(
        "A={};D={};F={};T='/data/vfonts/fontlab-link-{tx}.tmp';K='/data/vfonts/fontlab-tx-{tx}';mkdir $K 2>/dev/null || exit 0;[ -L $A ] && [ ! -L $D ] && [ ! -e $T ] && [ ! -L $T ] || exit 1;",
        quote(ACTIVE), quote(&to.target), quote(&from.target));
    result += &format!("[ \"$(readlink $A)\" = \"$F\" ] && [ \"$(sha256sum $A | cut -d' ' -f1)\" = {} ] || exit 1;", quote(&from.sha256));
    if stage {
        result += &format!("S={};[ \"$(sha256sum $S | cut -d' ' -f1)\" = {} ] || exit 1;if [ ! -e $D ];then (set -C;cat $S >$D) && chmod 644 $D || exit 1;fi;", quote(&source(tx)?), quote(&to.sha256));
    }
    result += &format!("[ -f $D ] && [ ! -L $D ] && [ \"$(sha256sum $D | cut -d' ' -f1)\" = {} ] && [ \"$(readlink $A)\" = \"$F\" ] && [ \"$(sha256sum $A | cut -d' ' -f1)\" = {} ] || exit 1;ln -s \"$D\" $T && mv -fT $T $A && log -t FontLab {tx}_APPLIED;", quote(&to.sha256), quote(&from.sha256));
    if result.len() > 3400 {
        return Err("Font transaction is too long.".into());
    }
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn confine_inputs_and_preserve_originals() {
        let hash = "a".repeat(64);
        let from = FontState {
            target: ROBOTO.into(),
            sha256: hash.clone(),
        };
        let mut to = FontState {
            target: candidate(&hash).unwrap(),
            sha256: hash,
        };
        assert!(script(&from, &to, &"b".repeat(32), true).is_ok());
        for path in [
            ACTIVE,
            "/data/vfonts/../fonts/x.ttf",
            "/data/vfonts/x$(id).ttf",
            "/system/build.prop",
            "/data/vfonts/a\nb.ttf",
        ] {
            assert!(!allowed_target(path));
            to.target = path.into();
            assert!(script(&from, &to, &"b".repeat(32), true).is_err());
        }
        assert!(allowed_target("/data/vfonts/23小时59分60秒想你.ttf"));
        assert!(source("../escape").is_err());
        assert!(candidate("$(id)").is_err());
        to.target = "/data/vfonts/store-font.ttf".into();
        assert!(script(&from, &to, &"b".repeat(32), true).is_err());
        assert!(script(&from, &to, &"b".repeat(32), false).is_err());
    }
}
