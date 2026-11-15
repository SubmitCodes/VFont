use crate::{
    font,
    transaction::{self, FontState, ACTIVE},
    ui,
};
use jni::{
    objects::{GlobalRef, JByteArray, JClass, JIntArray, JObject, JString, JValue},
    sys::{jboolean, jint, jstring},
    JNIEnv, JavaVM,
};
use serde_json::{json, Value};
use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    os::{fd::FromRawFd, unix::fs::OpenOptionsExt},
    process::Command,
    sync::{Mutex, OnceLock},
    time::Instant,
};

static VM: OnceLock<JavaVM> = OnceLock::new();
static ACTIVITY: OnceLock<Mutex<Option<GlobalRef>>> = OnceLock::new();

pub fn action(action: &str) {
    let Some(vm) = VM.get() else {
        return;
    };
    let Ok(mut env) = vm.attach_current_thread() else {
        return;
    };
    let activity = ACTIVITY.get_or_init(Default::default).lock().unwrap();
    if let Some(activity) = activity.as_ref() {
        if let Ok(text) = env.new_string(action) {
            if env
                .call_method(
                    activity.as_obj(),
                    "dispatch",
                    "(Ljava/lang/String;)V",
                    &[JValue::Object(&text)],
                )
                .is_err()
            {
                let _ = env.exception_clear();
                ui::event(
                    json!({"message":"Could not open Android action. Reopen VFont.","error":true,"busy":false}),
                );
            }
        }
    }
}

fn string_result(env: &JNIEnv, value: Value) -> jstring {
    env.new_string(value.to_string())
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn text(env: &mut JNIEnv, value: JString) -> Result<String, String> {
    env.get_string(&value)
        .map(|s| s.into())
        .map_err(|e| e.to_string())
}

#[no_mangle]
pub extern "system" fn Java_dev_fontlab_MainActivity_nativeInitialize(
    env: JNIEnv,
    _: JClass,
    activity: JObject,
) {
    if let Ok(vm) = env.get_java_vm() {
        let _ = VM.set(vm);
    }
    if let Ok(global) = env.new_global_ref(activity) {
        *ACTIVITY.get_or_init(Default::default).lock().unwrap() = Some(global);
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_fontlab_MainActivity_nativeUiGeneration(
    _: JNIEnv,
    _: JClass,
) -> jint {
    ui::UI_GENERATION.load(std::sync::atomic::Ordering::SeqCst) as jint
}

#[no_mangle]
pub extern "system" fn Java_dev_fontlab_MainActivity_nativeValidate(
    env: JNIEnv,
    _: JClass,
    bytes: JByteArray,
) -> jstring {
    let result = env
        .convert_byte_array(&bytes)
        .map_err(|e| e.to_string())
        .and_then(|b| font::validate(&b));
    let value = match result {
        Ok(info) => json!({"ok":true,"font":info}),
        Err(e) => json!({"ok":false,"message":e}),
    };
    string_result(&env, value)
}

#[no_mangle]
pub extern "system" fn Java_dev_fontlab_MainActivity_nativeValidateFd(
    env: JNIEnv,
    _: JClass,
    fd: jint,
) -> jstring {
    // Ownership of this descriptor was transferred by ParcelFileDescriptor.detachFd().
    let input = (fd >= 0).then(|| unsafe { File::from_raw_fd(fd) });
    let result = (|| -> Result<Value, String> {
        let mut bytes = Vec::new();
        input
            .ok_or("Cannot open font.")?
            .take(font::MAX_FONT_BYTES as u64 + 1)
            .read_to_end(&mut bytes)
            .map_err(|e| e.to_string())?;
        let info = font::validate(&bytes)?;
        Ok(json!({"ok":true,"font":info}))
    })();
    string_result(
        &env,
        result.unwrap_or_else(|message| json!({"ok":false,"message":message})),
    )
}

#[no_mangle]
pub extern "system" fn Java_dev_fontlab_MainActivity_nativePrepare(
    env: JNIEnv,
    _: JClass,
    input_fd: jint,
    output_fd: jint,
    face_index: jint,
) -> jstring {
    // Both descriptors are transferred by detachFd; this runs in the normal app,
    // before the service is involved. No device font is changed by importing.
    let input = (input_fd >= 0).then(|| unsafe { File::from_raw_fd(input_fd) });
    let output = (output_fd >= 0).then(|| unsafe { File::from_raw_fd(output_fd) });
    let result = (|| -> Result<Value, String> {
        if face_index < 0 {
            return Err("Invalid collection face.".into());
        }
        let mut bytes = Vec::new();
        input
            .ok_or("Cannot open selected font.")?
            .take(font::MAX_FONT_BYTES as u64 + 1)
            .read_to_end(&mut bytes)
            .map_err(|e| e.to_string())?;
        let prepared = font::prepare(&bytes, face_index as usize)?;
        let mut output = output.ok_or("Cannot create prepared font.")?;
        output
            .write_all(&prepared.bytes)
            .and_then(|_| output.sync_all())
            .map_err(|e| e.to_string())?;
        Ok(
            json!({"ok":true,"font":prepared.info,"source_format":prepared.source_format,
            "faces":prepared.faces,"face_index":prepared.face_index}),
        )
    })();
    string_result(
        &env,
        result.unwrap_or_else(|message| json!({"ok":false,"message":message})),
    )
}

#[no_mangle]
pub extern "system" fn Java_dev_fontlab_MainActivity_nativeEvent(
    mut env: JNIEnv,
    _: JClass,
    event: JString,
) {
    if let Ok(event) = text(&mut env, event) {
        if let Ok(value) = serde_json::from_str(&event) {
            ui::event(value);
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_fontlab_MainActivity_nativeSelected(
    mut env: JNIEnv,
    _: JClass,
    metadata: JString,
    name: JString,
    pixels: JIntArray,
    width: jint,
    height: jint,
) {
    if width <= 0 || height <= 0 || width > 1024 || height > 1024 {
        return;
    }
    let count = width as usize * height as usize;
    if env.get_array_length(&pixels).ok() != Some(count as i32) {
        return;
    }
    let Ok(metadata) = text(&mut env, metadata) else {
        return;
    };
    let Ok(info) = serde_json::from_str(&metadata) else {
        return;
    };
    let Ok(name) = text(&mut env, name) else {
        return;
    };
    let mut argb = vec![0i32; count];
    if env.get_int_array_region(pixels, 0, &mut argb).is_err() {
        return;
    }
    let rgba = argb
        .into_iter()
        .flat_map(|p| {
            let p = p as u32;
            [(p >> 16) as u8, (p >> 8) as u8, p as u8, (p >> 24) as u8]
        })
        .collect();
    ui::selected(info, name, rgba, [width as usize, height as usize]);
}

fn command(program: &str, args: &[&str]) -> Result<String, String> {
    let result = Command::new(program)
        .args(args)
        .output()
        .map_err(|e| format!("Android command failed: {e}"))?;
    if !result.status.success() {
        return Err(String::from_utf8_lossy(&result.stderr).trim().to_owned());
    }
    Ok(String::from_utf8_lossy(&result.stdout).trim().to_owned())
}

fn compatibility(env: &mut JNIEnv, service: &JObject) -> Result<Value, String> {
    let transport = env
        .call_method(service, "checkTransport", "()Ljava/lang/String;", &[])
        .map_err(|e| bridge_error(env, e))?
        .l()
        .map_err(|e| e.to_string())?;
    let transport: String = env
        .get_string(&JString::from(transport))
        .map_err(|e| e.to_string())?
        .into();
    let mut profile: Value = serde_json::from_str(&transport).map_err(|e| e.to_string())?;
    let maker = command("/system/bin/getprop", &["ro.product.manufacturer"])?;
    let sdk = command("/system/bin/getprop", &["ro.build.version.sdk"])?;
    if !matches!(maker.to_ascii_lowercase().as_str(), "vivo" | "iqoo")
        || command("/system/bin/am", &["get-current-user"])? != "0"
    {
        return Err("Requires a compatible Vivo/iQOO ROM and the main user.".into());
    }
    if sdk.parse::<u32>().unwrap_or(0) < 34 {
        return Err(
            "Native font changes currently require Android 14 or later. You can still export a Device Support report."
                .into(),
        );
    }
    for (path, target) in [
        ("/system/fonts/VivoFont.ttf", ACTIVE),
        ("/system/etc/fonts.xml", "/data/vfonts/fonts.xml"),
    ] {
        if !fs::read_link(path).is_ok_and(|p| p.to_str() == Some(target)) {
            return Err(
                "This ROM uses a different native font layout. No changes were made.".into(),
            );
        }
    }
    let config = fs::read_link("/data/vfonts/fonts.xml")
        .map_err(|e| format!("Cannot inspect Vivo's font configuration: {e}"))?;
    if !config.to_str().is_some_and(transaction::allowed_config) {
        return Err("Vivo's font configuration is outside the supported system files. No changes were made.".into());
    }
    font::validate(&read_font(ACTIVE)?)?;
    let active_target = fs::read_link(ACTIVE)
        .ok()
        .and_then(|p| p.to_str().map(str::to_owned));
    // Status and probes need the same saved recovery font as Apply/Restore.
    let recorded_stock = env
        .call_method(service, "readDefaultFont", "()Ljava/lang/String;", &[])
        .map_err(|e| bridge_error(env, e))?
        .l()
        .map_err(|e| e.to_string())?;
    let recorded_stock = text(env, JString::from(recorded_stock))?;
    let stock = transaction::system_font(active_target.as_deref(), &recorded_stock, |path| {
        std::path::Path::new(path).exists()
    })?;
    font::validate(&read_font(&stock)?)?;
    env.call_static_method("dev/fontlab/Platform", "checkFontRefresh", "()V", &[])
        .map_err(|e| bridge_error(env, e))?;
    let model = command("/system/bin/getprop", &["ro.product.model"])?;
    let build = command("/system/bin/getprop", &["ro.build.display.id"])?;
    profile["model"] = json!(model);
    profile["build"] = json!(build);
    Ok(profile)
}

fn read_font(path: &str) -> Result<Vec<u8>, String> {
    let mut bytes = Vec::new();
    File::open(path)
        .map_err(|e| format!("Cannot read font: {e}"))?
        .take(font::MAX_FONT_BYTES as u64 + 1)
        .read_to_end(&mut bytes)
        .map_err(|e| e.to_string())?;
    if bytes.len() > font::MAX_FONT_BYTES || bytes.is_empty() {
        return Err("Font size is unexpected.".into());
    }
    Ok(bytes)
}

fn state() -> Result<FontState, String> {
    let target = fs::read_link(ACTIVE)
        .map_err(|e| format!("Cannot inspect current font: {e}"))?
        .to_str()
        .ok_or("Font path is not valid text.")?
        .to_owned();
    if !transaction::allowed_target(&target) {
        return Err("Current font path is not supported; no changes made.".into());
    }
    Ok(FontState {
        target,
        sha256: font::digest(&read_font(ACTIVE)?),
    })
}

fn checked_status(env: &mut JNIEnv, service: &JObject) -> Result<Value, String> {
    let profile = compatibility(env, service)?;
    let current = state()?;
    let verified = profile["command_verified"] == true;
    Ok(
        json!({"ok":true,"message":if verified {"Connected. Native font transport checked."}
            else {"Experimental device support. Test native access before applying a font."},
        "current":current,"profile":profile,"command_verified":verified}),
    )
}

fn failure(message: String) -> Value {
    json!({"ok":false,"message":message,"current":state().ok(),"command_verified":false})
}

#[no_mangle]
pub extern "system" fn Java_dev_fontlab_FontUserService_nativeStatus(
    mut env: JNIEnv,
    service: JObject,
) -> jstring {
    let mut value = checked_status(&mut env, &service).unwrap_or_else(failure);
    value["supported"] = value["ok"].clone();
    if value["ok"] != true {
        value["command_verified"] = json!(false);
    }
    string_result(&env, value)
}

fn bridge_error(env: &mut JNIEnv, error: jni::errors::Error) -> String {
    let exception = env.exception_occurred().ok();
    let _ = env.exception_clear();
    let detail = exception
        .filter(|exception| !exception.is_null())
        .and_then(|exception| {
            env.call_method(exception, "toString", "()Ljava/lang/String;", &[])
                .ok()
        })
        .and_then(|value| value.l().ok())
        .and_then(|value| env.get_string(&JString::from(value)).ok().map(String::from));
    let _ = env.exception_clear();
    format!(
        "Android font bridge failed: {}",
        detail.unwrap_or_else(|| error.to_string())
    )
}

fn token() -> Result<String, String> {
    let mut random = [0u8; 16];
    File::open("/dev/urandom")
        .and_then(|mut f| f.read_exact(&mut random))
        .map_err(|e| e.to_string())?;
    Ok(random.iter().map(|b| format!("{b:02x}")).collect())
}

fn trigger(env: &mut JNIEnv, service: &JObject, script: &str, tx: &str) -> Result<(), String> {
    let script = env.new_string(script).map_err(|e| e.to_string())?;
    let transaction = env.new_string(tx).map_err(|e| e.to_string())?;
    env.call_method(
        service,
        "dispatchFont",
        "(Ljava/lang/String;Ljava/lang/String;)V",
        &[JValue::Object(&transaction), JValue::Object(&script)],
    )
    .map_err(|e| bridge_error(env, e))?;
    Ok(())
}

#[no_mangle]
pub extern "system" fn Java_dev_fontlab_FontUserService_nativeProbe(
    mut env: JNIEnv,
    service: JObject,
) -> jstring {
    let result = (|| -> Result<Value, String> {
        let profile = compatibility(&mut env, &service)?;
        let before = state()?;
        let tx = token()?;
        let _ = fs::create_dir("/storage/emulated/0/Download");
        let probe_path = transaction::probe_source(&tx)?;
        let probe_bytes = tx.as_bytes();
        let probe_hash = font::digest(probe_bytes);
        let mut probe_file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o644)
            .open(&probe_path)
            .map_err(|e| format!("Could not stage temporary access probe: {e}"))?;
        let probe_guard = SourceFile {
            path: probe_path,
            hash: probe_hash.clone(),
        };
        probe_file
            .write_all(probe_bytes)
            .and_then(|_| probe_file.sync_all())
            .map_err(|e| e.to_string())?;
        drop(probe_file);
        let script = transaction::probe_script(&tx, &probe_hash)?;
        let outcome = trigger(&mut env, &service, &script, &tx);
        drop(probe_guard);
        outcome?;
        if state()? != before {
            return Err(
                "Current font changed during the read-only test. Check it before applying.".into(),
            );
        }
        Ok(
            json!({"ok":true,"message":"Native font test passed.","current":before,
            "profile":profile,"command_verified":true,"supported":true}),
        )
    })();
    string_result(&env, result.unwrap_or_else(failure))
}

struct SourceFile {
    path: String,
    hash: String,
}
impl Drop for SourceFile {
    fn drop(&mut self) {
        // Only the exact, newly created shared-storage copy is eligible for cleanup.
        if fs::symlink_metadata(&self.path).is_ok_and(|m| m.is_file() && !m.is_symlink())
            && read_font(&self.path).is_ok_and(|b| font::digest(&b) == self.hash)
        {
            let _ = fs::remove_file(&self.path);
        }
    }
}

fn run(
    env: &mut JNIEnv,
    service: &JObject,
    input: Option<File>,
    expected: &str,
    restore: bool,
    default_target: &str,
    phase: &mut &'static str,
) -> Result<Value, String> {
    *phase = "layout";
    let profile = compatibility(env, service)?;
    if profile["command_verified"] != true {
        return Err(
            "Test native access before applying or restoring fonts on this software.".into(),
        );
    }
    let from = state()?;
    *phase = "input";
    let recovery_target = transaction::restore_target(default_target, |path| {
        read_font(path)
            .and_then(|bytes| font::validate(&bytes).map(|_| ()))
            .is_ok()
    })?;
    let bytes = if restore {
        None
    } else {
        let mut bytes = Vec::new();
        input
            .ok_or("Choose a font first.")?
            .take(font::MAX_FONT_BYTES as u64 + 1)
            .read_to_end(&mut bytes)
            .map_err(|e| e.to_string())?;
        let info = font::validate(&bytes)?;
        if !transaction::hex(expected, 64) || info.sha256 != expected {
            return Err("Selected file changed after preview. Choose it again.".into());
        }
        Some(bytes)
    };
    let to = if restore {
        let target = recovery_target;
        let sha256 = font::digest(&read_font(&target)?);
        FontState { target, sha256 }
    } else {
        FontState {
            target: transaction::candidate(expected)?,
            sha256: expected.into(),
        }
    };
    if from.sha256 == to.sha256 {
        *phase = "complete";
        return Ok(json!({"ok":true,"message":"Font already applied.","current":from}));
    }
    let tx = token()?;
    let script = transaction::script(&from, &to, &tx, !restore)?;
    *phase = "staging";
    let source_guard = if let Some(bytes) = bytes {
        let _ = fs::create_dir("/storage/emulated/0/Download");
        let path = transaction::source(&tx)?;
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o644)
            .open(&path)
            .map_err(|e| format!("Could not stage a temporary font copy: {e}"))?;
        let guard = SourceFile {
            path,
            hash: to.sha256.clone(),
        };
        file.write_all(&bytes)
            .and_then(|_| file.sync_all())
            .map_err(|e| e.to_string())?;
        Some(guard)
    } else {
        None
    };
    *phase = "dispatch";
    let outcome = trigger(env, service, &script, &tx);
    drop(source_guard);
    outcome?;
    *phase = "verify";
    if state()? != to {
        return Err(
            "Native font receipt did not match the active font. Restore is available.".into(),
        );
    }
    *phase = "refresh";
    let refreshed = env
        .call_static_method(
            "dev/fontlab/Platform",
            "refreshFont",
            "()Ljava/lang/String;",
            &[],
        )
        .map_err(|e| {
            format!(
                "Font file applied, but refresh failed. Restore is available. {}",
                bridge_error(env, e)
            )
        })?
        .l()
        .map_err(|e| e.to_string())?;
    let refresh: String = env
        .get_string(&JString::from(refreshed))
        .map_err(|e| e.to_string())?
        .into();
    let refresh: Value = serde_json::from_str(&refresh).map_err(|e| e.to_string())?;
    if state()? != to {
        return Err("Current font changed during refresh; inspect it before retrying.".into());
    }
    *phase = "complete";
    Ok(
        json!({"ok":true,"message":if restore {"System default font restored."} else {"Font applied."},"current":to,"transaction":tx,"refresh":refresh,"profile":profile}),
    )
}

#[no_mangle]
pub extern "system" fn Java_dev_fontlab_FontUserService_nativeRun(
    mut env: JNIEnv,
    service: JObject,
    fd: jint,
    expected: JString,
    restore: jboolean,
    default_target: JString,
) -> jstring {
    // Ownership of this descriptor was transferred by ParcelFileDescriptor.detachFd().
    let input = if fd >= 0 {
        Some(unsafe { File::from_raw_fd(fd) })
    } else {
        None
    };
    let started = Instant::now();
    let mut phase = "input";
    let result = text(&mut env, expected).and_then(|expected| {
        let default_target = text(&mut env, default_target).unwrap_or_default();
        run(
            &mut env,
            &service,
            input,
            &expected,
            restore != 0,
            &default_target,
            &mut phase,
        )
    });
    let mut value = result.unwrap_or_else(failure);
    value["diagnostic_stage"] = json!(phase);
    value["elapsed_ms"] = json!(started.elapsed().as_millis() as u64);
    string_result(&env, value)
}
