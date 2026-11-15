# Device reports and privacy

[User guide](guide.md) | [Back to README](../README.md)

## Save a support report

Open **Help and about > Device support** or the same entry in Icon options.
Tap **Check device**, **Review report**, then **Save support report**. You choose
the destination and whether to share it. Nothing is uploaded automatically.

Basic collection works without Shizuku. A connected, authorized Shizuku service
adds read-only shell evidence. A report does not run a native Apply/Restore or
enable unsupported writes. Results distinguish missing, inaccessible, not tested,
failed and positively observed capabilities.

## What the report contains

- App version, device model, Android/firmware identifiers, CPU ABIs and page size.
- Fixed ROM properties and main/secondary-user classification.
- Shizuku status, execution UID and allowlisted stock package/API checks.
- Stock component hashes, native protocol information and bounded appearance settings.
- Known native path metadata, with custom font paths redacted.
- A bounded, sanitized VFont operation history with result categories and timing.

It excludes serial/IMEI, accounts, installed-app lists, selected file names/URIs,
custom font contents or hashes, custom icon images, raw logcat and settings
databases. The resolved default launcher package is classified separately.
Firmware/model information still describes your device; review the JSON before
posting it publicly.

History stays in private app storage, capped at 200 events, 128 KiB and seven days.
The formatted report is capped at 384 KiB. **Clear recent history** removes local
history and the cached report; it does not remove selections, appearance or files
already exported elsewhere. An interrupted record means no completion was saved;
it does not establish that the native operation made no changes.

## Optional system-files ZIP

A separate optional export copies only allowlisted readable stock system
components, with path, count and byte limits plus a manifest. It does not include
app data, custom fonts, photos or arbitrary file paths. It can still contain
proprietary vendor code. Keep the ZIP local; do not attach it to GitHub issues or
redistribute it without the relevant rights. A local export does not grant them.

## App and repository

VFont has no Internet, accessibility-service or broad storage permission. Font
imports and images use Android's picker. No ads, telemetry or automatic uploads
are included. Private captures, local validation files, device evidence, signing
keys and machine-specific configuration are excluded from the public repository
and distributed APK.
