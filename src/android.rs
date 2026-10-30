use crate::{
    font,
    transaction::{self, FontState, ACTIVE, BUILD, GAME, ROBOTO},
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
    thread,
    time::{Duration, Instant},
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

fn compatibility() -> Result<(), String> {
    if command("/system/bin/getprop", &["ro.product.model"])? != "V2454A" {
        return Err("This version supports the tested Vivo X200 Ultra (V2454A).".into());
    }
    if command("/system/bin/getprop", &["ro.build.display.id"])? != BUILD {
        return Err("This firmware has not been verified. No font changes were made.".into());
    }
    let package = command("/system/bin/dumpsys", &["package", "com.vivo.gamecube"])?;
    if !package
        .lines()
        .any(|l| l.trim() == "versionName=15.0.05.01_de")
    {
        return Err("Game Cube version differs from the tested build.".into());
    }
    Ok(())
}

fn game_active() -> bool {
    command(
        "/system/bin/settings",
        &["--user", "0", "get", "system", "current_game_package"],
    )
    .is_ok_and(|name| name == GAME)
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

fn checked_status() -> Result<Value, String> {
    compatibility()?;
    let current = state()?;
    Ok(
        json!({"ok":true,"message":"Connected · X200 Ultra verified","current":current,"build":BUILD,"game_active":game_active()}),
    )
}

fn failure(message: String) -> Value {
    json!({"ok":false,"message":message,"current":state().ok()})
}

#[no_mangle]
pub extern "system" fn Java_dev_fontlab_FontUserService_nativeStatus(
    env: JNIEnv,
    _: JClass,
) -> jstring {
    string_result(&env, checked_status().unwrap_or_else(failure))
}

fn bridge_error(env: &mut JNIEnv, error: jni::errors::Error) -> String {
    let _ = env.exception_describe();
    let _ = env.exception_clear();
    format!("Android font bridge failed: {error}")
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
    input: Option<File>,
    expected: &str,
    restore: bool,
) -> Result<Value, String> {
    compatibility()?;
    if !game_active() {
        return Err(
            "Add VFont to Ultra Game Mode, return here and check setup before applying.".into(),
        );
    }
    let from = state()?;
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
        FontState {
            target: ROBOTO.into(),
            sha256: font::digest(&read_font(ROBOTO)?),
        }
    } else {
        FontState {
            target: transaction::candidate(expected)?,
            sha256: expected.into(),
        }
    };
    if from.sha256 == to.sha256 {
        return Ok(
            json!({"ok":true,"message":"This font is already active. No changes needed.","current":from}),
        );
    }
    let mut random = [0u8; 16];
    File::open("/dev/urandom")
        .and_then(|mut f| f.read_exact(&mut random))
        .map_err(|e| e.to_string())?;
    let tx: String = random.iter().map(|b| format!("{b:02x}")).collect();
    let script = transaction::script(&from, &to, &tx, !restore)?;
    let source_guard = if let Some(bytes) = bytes {
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
    let layer = format!("{GAME}/FontLab_{tx}$({script})");
    let name = env.new_string(layer).map_err(|e| e.to_string())?;
    let surface_result = env.call_static_method(
        "dev/fontlab/Platform",
        "createSurface",
        "(Ljava/lang/String;)Ljava/lang/Object;",
        &[JValue::Object(&name)],
    );
    let surface = surface_result
        .map_err(|e| bridge_error(env, e))?
        .l()
        .map_err(|e| e.to_string())?;
    let outcome = (|| -> Result<(), String> {
        // Re-enter our own game session so Game Cube discards its previously cached FPS layer.
        // Showing Home and returning is a normal UI transition; no game database is edited.
        command(
            "/system/bin/am",
            &[
                "start",
                "--user",
                "0",
                "-W",
                "-a",
                "android.intent.action.MAIN",
                "-c",
                "android.intent.category.HOME",
            ],
        )?;
        let left = Instant::now();
        while game_active() && left.elapsed() < Duration::from_secs(4) {
            thread::sleep(Duration::from_millis(200));
        }
        thread::sleep(Duration::from_millis(700));
        command(
            "/system/bin/am",
            &[
                "start",
                "--user",
                "0",
                "-W",
                "-a",
                "android.intent.action.MAIN",
                "-c",
                "android.intent.category.LAUNCHER",
                "-f",
                "0x10200000",
                "-n",
                "dev.fontlab/.MainActivity",
            ],
        )?;
        let start = Instant::now();
        while start.elapsed() < Duration::from_secs(35) {
            if fs::read_link(ACTIVE).is_ok_and(|p| p.to_str() == Some(&to.target)) {
                if state()? == to {
                    return Ok(());
                }
                return Err(
                    "Font link changed but its checksum did not match. Use Restore Roboto.".into(),
                );
            }
            thread::sleep(Duration::from_millis(350));
        }
        Err("Font not applied. Open Game Cube’s CPU/GPU/FPS performance panel and retry. No refresh was sent.".into())
    })();
    let released = env
        .call_static_method(
            "dev/fontlab/Platform",
            "releaseSurface",
            "(Ljava/lang/Object;)V",
            &[JValue::Object(&surface)],
        )
        .map_err(|e| bridge_error(env, e));
    drop(source_guard);
    outcome?;
    released?;
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
    Ok(
        json!({"ok":true,"message":if restore {"Roboto restored."} else {"Font applied. It now uses Vivo’s native rendering."},"current":to,"transaction":tx,"refresh":refresh}),
    )
}

#[no_mangle]
pub extern "system" fn Java_dev_fontlab_FontUserService_nativeRun(
    mut env: JNIEnv,
    _: JClass,
    fd: jint,
    expected: JString,
    restore: jboolean,
) -> jstring {
    // Ownership of this descriptor was transferred by ParcelFileDescriptor.detachFd().
    let input = if fd >= 0 {
        Some(unsafe { File::from_raw_fd(fd) })
    } else {
        None
    };
    let result =
        text(&mut env, expected).and_then(|expected| run(&mut env, input, &expected, restore != 0));
    let value = result.unwrap_or_else(failure);
    string_result(&env, value)
}
