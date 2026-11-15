use serde::{Deserialize, Serialize};

pub const ACTIVE: &str = "/data/vfonts/VivoFont.ttf";
pub const ROBOTO: &str = "/system/fonts/Roboto-Regular.ttf";

pub fn allowed_config(path: &str) -> bool {
    matches!(
        path,
        "/system/etc/fonts_online.xml" | "/system/etc/fonts_default.xml"
    )
}

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

pub fn allowed_system_font(path: &str) -> bool {
    let Some(name) = path.strip_prefix("/system/fonts/") else {
        return false;
    };
    !name.is_empty()
        && name.len() <= 120
        && !name.contains("..")
        && name != "VivoFont.ttf"
        && name.ends_with(".ttf")
        && name
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || "_-.".contains(c))
}

pub fn system_font(
    active: Option<&str>,
    recorded: &str,
    exists: impl Fn(&str) -> bool,
) -> Result<String, String> {
    if exists(ROBOTO) {
        return Ok(ROBOTO.into());
    }
    active
        .filter(|path| allowed_system_font(path))
        .or_else(|| (allowed_system_font(recorded) && exists(recorded)).then_some(recorded))
        .map(str::to_owned)
        .ok_or_else(|| "System default font is unavailable.".into())
}

pub fn allowed_target(path: &str) -> bool {
    if allowed_system_font(path) {
        return true;
    }
    let Some(name) = path.strip_prefix("/data/vfonts/") else {
        return false;
    };
    !name.is_empty()
        && name.len() <= 200
        && !name.contains("..")
        && name != "VivoFont.ttf"
        && name.ends_with(".ttf")
        && name
            .chars()
            .all(|c| c.is_alphanumeric() || "_-. ".contains(c))
}

pub fn restore_target(recorded: &str, readable: impl Fn(&str) -> bool) -> Result<String, String> {
    [recorded, ROBOTO]
        .into_iter()
        .find(|path| allowed_system_font(path) && readable(path))
        .map(str::to_owned)
        .ok_or_else(|| {
            "A verified system default font could not be saved or read. No font changes were made."
                .into()
        })
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

pub fn probe_source(tx: &str) -> Result<String, String> {
    if !hex(tx, 32) {
        return Err("Invalid transaction ID.".into());
    }
    Ok(format!(
        "/storage/emulated/0/Download/fontlab-probe-{tx}.tmp"
    ))
}

pub fn probe_script(tx: &str, hash: &str) -> Result<String, String> {
    if !hex(tx, 32) || !hex(hash, 64) {
        return Err("Invalid probe transaction.".into());
    }
    let result = format!(
        "[ \"$(id -u)\" = 1000 ] || exit 1;P={};D='/data/vfonts/fontlab-probe-{tx}';[ -f $P ] && [ ! -L $P ] && [ \"$(sha256sum $P | cut -d' ' -f1)\" = {} ] || exit 1;mkdir $D && rmdir $D || exit 1;",
        quote(&probe_source(tx)?),
        quote(hash),
    );
    if result.len() > 3400 {
        return Err("Probe transaction is too long.".into());
    }
    Ok(result)
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
    if !stage && !allowed_system_font(&to.target) {
        return Err("Restore only targets a verified system font.".into());
    }
    let mut result = format!(
        "[ \"$(id -u)\" = 1000 ] || exit 1;A={};D={};F={};T='/data/vfonts/fontlab-link-{tx}.tmp';K='/data/vfonts/fontlab-tx-{tx}';[ -e $K ] && exit 0;mkdir $K || exit 1;[ -L $A ] && [ ! -L $D ] && [ ! -e $T ] && [ ! -L $T ] || exit 1;",
        quote(ACTIVE), quote(&to.target), quote(&from.target));
    result += &format!("[ \"$(readlink $A)\" = \"$F\" ] && [ \"$(sha256sum $A | cut -d' ' -f1)\" = {} ] || exit 1;", quote(&from.sha256));
    if stage {
        result += &format!("S={};[ \"$(sha256sum $S | cut -d' ' -f1)\" = {} ] || exit 1;if [ ! -e $D ];then (set -C;cat $S >$D) && chmod 644 $D || exit 1;fi;", quote(&source(tx)?), quote(&to.sha256));
    }
    result += &format!("[ -f $D ] && [ ! -L $D ] && [ \"$(sha256sum $D | cut -d' ' -f1)\" = {} ] && [ \"$(readlink $A)\" = \"$F\" ] && [ \"$(sha256sum $A | cut -d' ' -f1)\" = {} ] || exit 1;if ln -s \"$D\" $T && mv -fT $T $A;then case \"$F\" in /data/vfonts/fontlab-*.ttf) [ \"$F\" != \"$D\" ] && rm -f \"$F\";; esac;rmdir $K 2>/dev/null || true;else rm -f $T 2>/dev/null;rmdir $K 2>/dev/null;exit 1;fi;", quote(&to.sha256), quote(&from.sha256));
    if result.len() > 3400 {
        return Err("Font transaction is too long.".into());
    }
    Ok(result)
}
