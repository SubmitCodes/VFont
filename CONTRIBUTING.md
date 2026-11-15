# Contributing

Start with the [build guide](docs/building.md), [compatibility requirements](docs/compatibility.md)
and [native integration notes](docs/how-it-works.md).

## Report a problem

Use the [device-support issue form](https://github.com/SubmitCodes/VFont/issues/new/choose).
Include the app version, phone model, ROM/build, steps, visible result and whether
Restore worked. Attach a fresh support report if possible. Read the
[report privacy notes](docs/diagnostics.md) and remove personal material from any
screenshots before posting. Share optional system-code bundles privately only
when requested.

## Propose a change

Keep changes focused and describe the problem and resulting behavior. Separate
build/static checks from actual device observations. For native changes, explain
how originals and recovery remain protected, including the failure path.
Do not claim new-device compatibility from a matching API alone.

Preserve caller checks, path/size/hash validation, transaction journals and
restoration. Do not add arbitrary shell commands, exported command receivers,
telemetry or networking. Keep font preparation in Rust and platform integration
in Java. Existing package/library identities must stay compatible with updates.

Private validation suites and device investigations are intentionally not part
of the public repository. Do not submit personal fonts, screenshots, device
identifiers, raw logs, stock-code bundles, APKs, signing material or machine paths.
Distributable APKs belong in GitHub Releases.

No project license has been selected for VFont's own code. Third-party licenses
and notices must be retained.
