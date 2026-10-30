"""Regenerate dependency notices from the locked Android dependency graph."""

import json
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parent.parent
metadata = json.loads(
    subprocess.check_output(
        [
            "cargo", "metadata", "--locked", "--format-version", "1",
            "--filter-platform", "aarch64-linux-android",
        ],
        cwd=ROOT,
        text=True,
    )
)
active = {node["id"] for node in metadata["resolve"]["nodes"]}
packages = sorted(
    (p for p in metadata["packages"] if p["id"] in active and p["name"] != "vfont"),
    key=lambda p: (p["name"], p["version"]),
)
parts = [
    "VFont third-party notices\n"
    "=========================\n\n"
    "These notices apply to third-party components, not to VFont's own code.\n"
    "The resolved Rust graph includes runtime and build-time dependencies.\n"
    "Where an upstream package offers multiple licenses, its original terms apply.\n\n"
    "Android bridge: Shizuku API/provider 13.1.5, Apache-2.0\n"
    "https://github.com/RikkaApps/Shizuku-API\n"
    "Gradle wrapper 8.14.3, Apache-2.0: https://github.com/gradle/gradle\n\n"
    "Resolved Rust packages\n----------------------\n"
]
licenses = {}


def add_license(label, path):
    content = "\n".join(
        line.rstrip()
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines()
    ).strip()
    licenses.setdefault(content, []).append(label)


for package in packages:
    name, version = package["name"], package["version"]
    parts.append(f"{name} {version}: {package['license'] or 'see upstream notices'}\n")
    if package["repository"]:
        parts.append(f"  {package['repository']}\n")
    directory = pathlib.Path(package["manifest_path"]).parent
    # Some crates omit workspace license files from the crates.io archive.
    for path in sorted(directory.iterdir()):
        if path.is_file() and path.name.lower().startswith(
            ("license", "licence", "copying", "copyright", "notice")
        ):
            add_license(f"{name} {version} / {path.name}", path)
    if name == "epaint_default_fonts":
        for filename in (
            "emoji-icon-font-mit-license.txt", "Hack-Regular.txt", "OFL.txt", "UFL.txt"
        ):
            add_license(f"{name} {version} / fonts/{filename}", directory / "fonts" / filename)

for path in sorted((ROOT / "docs" / "third-party").glob("*.txt")):
    add_license(f"Supplemental upstream text / {path.name}", path)

parts.append("\nIncluded license texts\n======================\n")
for content, labels in licenses.items():
    parts.append("\n" + "\n".join(labels) + "\n" + "-" * 72 + "\n" + content + "\n")

result = "".join(parts)
(ROOT / "THIRD_PARTY_NOTICES.txt").write_text(result, encoding="utf-8", newline="\n")
assets = ROOT / "android" / "app" / "src" / "main" / "assets"
assets.mkdir(exist_ok=True)
(assets / "THIRD_PARTY_NOTICES.txt").write_text(result, encoding="utf-8", newline="\n")
print(f"Notices: {len(packages)} packages, {len(licenses)} distinct texts")
