# Themes and recovery

[User guide](guide.md) | [Compatibility](compatibility.md)

## Full themes and individual components

Import a local Vivo `.itz` under **Themes and wallpaper > Full themes**. Supported
archives use ZIP storage and root `description.xml`; archive names, paths, sizes,
XML, checksums and resource types are validated before native application.

Full Apply includes supported home/lock wallpapers, lock-screen resources, app
icons and native app/System UI styling. The summary states when no font is
included. Themes containing bundled fonts are rejected; choose fonts separately
through **Fonts and emoji**.

**Theme components** offers individual rows and a separate Review action.
Status-only artwork and full System UI are mutually exclusive. Home and lock
wallpapers can be selected independently. Icon masks/backgrounds belong to
**App icons**; folder artwork and launcher styling belong to **Home screen style**.
Unsupported components are explained instead of silently substituted.

## The first backup is retained

The first managed theme change saves the previous native setup. Applying another
theme or selected components retains that first backup. Full Restore returns to
that saved setup, not to the immediately preceding import.

**Restore selected** restores only checked components; the others remain active.
Components missing from the current import can appear as Restore-only rows when
an original backup exists. Removing an imported archive does not remove the
active theme, individual icon choices or the recovery backup.

Individual icon overrides can coexist with a full theme on the fast native icon
backend. **Use theme icon** removes one override. Full theme Restore returns the
original setup saved before the theme changes.

## Current limits

Static home/lock wallpapers must be available for guarded recovery. Live wallpaper
setups and unfamiliar lock-screen engines may be refused. Theme size/shape controls
require compatible native style-1 support; unsupported ROMs show an explanation.
Icon-related theme operations can briefly restart the launcher to refresh its
cache. Launcher data is not cleared.

Interrupted operations retain their journal and owned recovery files. Reopen
VFont and retry the same operation or use Restore. Changed or foreign files can
stop recovery instead of being overwritten. Keep VFont installed and its data
intact until you no longer need the original setup.
