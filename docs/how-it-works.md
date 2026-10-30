# How it works

## Responsibilities

`src/font.rs` handles format detection, local WOFF/WOFF2 decoding, collection
face extraction, structural/checksum validation and metadata. It preserves
OpenType variation, color and layout tables rather than reducing fonts to
simple outlines. Android loads the prepared file for the preview.

`src/transaction.rs` builds the fixed font-only transaction. `src/android.rs`
implements JNI and the shell service's compatibility, staging and verification
steps. `src/ui.rs` is the Rust/egui interface.

The Java bridge is limited to Android APIs: NativeActivity, file selection,
Typeface/Canvas preview, Shizuku Binder connection, a temporary SurfaceControl,
and Vivo's native configuration refresh. The public Binder checks the caller
UID and service UID and has status/apply/restore methods, not a general shell
or arbitrary file-operation endpoint.

## Apply

1. Import and prepare the selected file in app-private cache. Collections are
   reduced to the chosen standalone face before preview.
2. Pass a read-only file descriptor and previewed checksum to the Shizuku user
   service. It requires shell UID 2000 and the app's caller UID.
3. Check the exact model, firmware and Game Cube version, active game session,
   current font link and checksum. Revalidate the prepared font and expected hash.
4. Create a uniquely named temporary Download file. No broad storage permission
   is requested; this is shell staging of a copy, not a user file overwrite.
5. Use the reviewed Game Cube command pathway with a fixed transaction payload.
   The app briefly re-enters its own game session so the performance pane uses
   a fresh layer. The user opens CPU/GPU/FPS to trigger the operation.
6. A one-use marker prevents cached-command replay. The payload checks paths,
   hashes and the old active target, creates a separate candidate only if absent,
   rechecks it, then atomically replaces the active link.
7. Verify the new target and content. Release the temporary surface and remove
   only the matching temporary Download copy. Read the current Vivo configuration,
   increment `typefaceChanged`, preserve `fontScale` and other fields, update the
   persistent configuration and check the read-back.

Restore follows the same guarded path and targets the existing system Roboto.
Candidate files and transaction markers remain in Vivo's data font directory.
Do not delete the active font or replay guards. Applying an already active hash
is a no-op. A timeout without a verified switch sends no font refresh.

## Boundaries

No root, bootloader unlock, flashing, system-partition writes, Themes downgrade
or uninstall is used. The APK requests only the Shizuku API permission and has
no networking, analytics, accessibility or broad storage permission. It still
depends on Game Cube's behavior; Shizuku does not independently grant write
access to Vivo's font directory.

The NativeActivity lifecycle is handled by narrowly patched winit/eframe
dependencies. See [vendor/PATCHES.md](../vendor/PATCHES.md). Those changes
affect VFont's UI shutdown/recreation, not installed Vivo applications.

Android's package/native-library identifiers retain their original prototype
names to preserve update compatibility. Fixed commands launch only VFont or
Home, inspect the checked device state, or implement the specific font refresh.
