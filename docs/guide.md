# User guide

[Download VFont](https://github.com/SubmitCodes/VFont/releases/latest) |
[Compatibility](compatibility.md) | [Back to README](../README.md)

## Connect once

Start Shizuku through Wireless debugging or ADB, then connect it under
**Connection and recovery > Connection and checks**. Allow VFont access.
Root/Sui mode is unsupported; the service must run as shell UID 2000. If the ROM
stops either app in the background, allow background battery usage for both.

An unfamiliar software profile requires **Test native access** before font
changes. This checks the native dispatch without changing the active font.
Success is saved for that software profile; changing firmware can require a new
check. A successful check alone does not establish device compatibility.

## Apply a font

1. Open **Fonts and emoji > Text and emoji fonts**.
2. Choose a local file and inspect the preview. Collections offer a face chooser.
3. Tap **Apply font** and wait for completion.

One selection replaces the previous app-private font copy. Removing that copy
does not delete the source file or restore the phone's active font. Use
**Restore system default font** to restore. Text and emoji are not independently
stacked fonts. Applying a store font or another theme can replace the active font.
VFont imports do not appear in Vivo's font-store catalog.

## Change one icon

Open **Icons and home screen > App icons**, select an app, choose artwork and
inspect the preview. Artwork can come from a pack, another launchable app, a photo
or a previously imported theme. Tap **Apply icon** to change only that app.

For a new software/icon-style profile, VFont first changes its own icon to a blue
V. Check it on Home, verify other icons and a folder, then return to VFont. The
confirmation opens automatically, including after a fresh launcher return.
**Looks right - apply my icons** restores the trial and continues your request.
**Restore only** restores the trial without applying your requested icons.

The editor's original preview comes from the app APK; it may differ from the icon
currently drawn by your theme. Keep a chosen icon pack installed until Apply.
Imported photos are retained privately by VFont.

## Apply an icon pack to selected apps

Open **Icons and home screen > Icon packs** and choose a source. Select app rows,
then use **Review** to inspect the complete selection before Apply. Search can
hide selected apps; Review still includes them. **Select shown** and **Clear
shown** affect only the displayed rows. Apps without a match need manual artwork.

Use the icon options to restore selected originals or all icons managed by VFont.
Over a managed full theme, **Use theme icon** removes an individual override.

## Apply a local theme

Open **Themes and wallpaper > Full themes**, choose a `.itz` file and review its
summary or preview gallery. **Apply full theme** applies the supported included
components. **Theme components** provides a separate selection and Review flow.
See [theme support and restoration](themes.md) for the supported scope.

## Recover or report a problem

Keep the app installed and its data intact while a managed change or recovery
backup is needed. Try the corresponding Restore action and retain any failure
message. An interrupted operation may need VFont reopened to finish recovery.

For font recovery when VFont cannot restore, try the stock Themes app's system
default font and restart the phone normally if needed. Do not delete files from
Vivo's private theme/font directories or clear VFont's data to force a reset.

Open **Help and about > Device support**, reconnect Shizuku if possible and tap
**Check device** again. Use **Review report > Save support report**, then attach
the fresh JSON to a [GitHub issue](https://github.com/SubmitCodes/VFont/issues/new/choose).
Describe what you tapped, what appeared and whether Restore worked. Review any
screenshots and attachments for personal information before sharing.
