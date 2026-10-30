# Android activity lifecycle patches

Vendored from the crates.io packages already pinned in Cargo.lock:

- winit 0.30.13 (Apache-2.0): exit the Android event loop on MainEvent::Destroy,
  and allow a subsequent Android Activity to construct a new loop.
  Upstream's Destroy arm only logs a TODO, which leaves NativeActivity.onDestroy
  waiting for android_main indefinitely.
- eframe 0.31.1 (MIT OR Apache-2.0): shutdown accepts an absent Android window
  after Suspended, and restores the EGL context without a surface for GL cleanup.

These patches affect the app UI, not Vivo or Shizuku. The device check in
android/app/src/androidTest/java/dev/fontlab/LifecycleCheck.java recreates the
real activity twice and waits for the Rust UI to initialize each time. It never
changes fonts or system settings.

Upstream sources:
https://github.com/rust-windowing/winit/tree/v0.30.13
https://github.com/emilk/egui/tree/0.31.1/crates/eframe
