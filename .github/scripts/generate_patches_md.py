#!/usr/bin/env python3
"""
Generates PATCHES.md from patches-list.json.

Every app section gets an explicit anchor so the app table in README.md can link
straight to it, for example PATCHES.md#vtvgo-vnvtvvtvgo.

python3 generate_patches_md.py <owner/repo> <branch> [patches-list.json] [PATCHES.md]
"""

import json
import re
import sys
import os
from pathlib import Path


if len(sys.argv) < 3:
    print("Usage: generate_patches_md.py <owner/repo> <branch> [json] [patches.md]")
    sys.exit(1)

repo_full   = sys.argv[1]
branch      = sys.argv[2]
json_path   = Path(sys.argv[3]) if len(sys.argv) > 3 else Path("patches-list.json")
output_path = Path(sys.argv[4]) if len(sys.argv) > 4 else Path("PATCHES.md")


if "/" not in repo_full:
    raise ValueError(f"Invalid repo format: {repo_full} (expected owner/repo)")

owner, repo = repo_full.split("/", 1)


with open(json_path, encoding="utf-8") as f:
    data = json.load(f)


# Emoji and label suffix for each form factor. Mobile and unspecified use no suffix.
FORM_FACTORS = {
    "TV":         ("📺", "Android TV"),
    "WEARABLE":   ("⌚", "Wear OS"),
    "AUTOMOTIVE": ("🚗", "Android Automotive"),
}

# Relative paths to app icons shown in the generated patches list.
APP_ICONS = {
    "com.ezviz": ".github/assets/apps/com.ezviz.png",
    "com.cloudflare.onedotonedotonedotone": ".github/assets/apps/com.cloudflare.onedotonedotonedotone.png",
    "com.camel.corp.universalcopy": ".github/assets/apps/com.camel.corp.universalcopy.png",
    "cz.mroczis.netmonster": ".github/assets/apps/cz.mroczis.netmonster.png",
    "com.anetcapture.mock": ".github/assets/apps/com.anetcapture.mock.png",
    "vn.vtv.vtvgo": ".github/assets/apps/vn.vtv.vtvgo.png",
    "com.locket.Locket": ".github/assets/apps/com.locket.Locket.png",
    "com.pioneerdj.WeDJ": ".github/assets/apps/com.pioneerdj.WeDJ.png",
    "com.dubox.drive": ".github/assets/apps/com.dubox.drive.png",
    "com.proxyman.proxymanandroid": ".github/assets/apps/com.proxyman.proxymanandroid.png",
}


def pkg_emoji(form_factor):
    """Return the emoji for a form factor, or a standard package emoji."""
    return FORM_FACTORS.get(form_factor, ("📦", None))[0]


def pkg_icon(pkg, form_factor):
    """Return an <img> tag for a bundled app icon, or a form-factor emoji."""
    icon = APP_ICONS.get(pkg)
    if icon:
        return f'<img src="{icon}" alt="" height="20">'
    return pkg_emoji(form_factor)


def pkg_label(name, form_factor):
    """Return the app name, with the form factor appended if it is not mobile."""
    suffix = FORM_FACTORS.get(form_factor, (None, None))[1]
    return f"{name} ({suffix})" if suffix else name


# Group patches by package and form factor; patches with no compatiblePackages are universal.
# The same package name can be used by different apps for each form factor.
# JSON structure: compatiblePackages is a list of objects with
# { packageName, name, formFactor, targets: [{ version, isExperimental, description }] }
by_pkg = {}   # (packageName, formFactor) -> { name, emoji, patches, targets }
universal = {}

for patch in data["patches"]:
    cp = patch.get("compatiblePackages")
    if not cp:
        # Deduplicate universal patches by name
        if patch["name"] not in universal:
            universal[patch["name"]] = patch
        continue
    for pkg_entry in cp:
        pkg  = pkg_entry["packageName"]
        name = pkg_entry.get("name") or pkg  # fall back to package name if no label
        form_factor = pkg_entry.get("formFactor")
        key  = (pkg, form_factor)
        if key not in by_pkg:
            by_pkg[key] = {
                "name":    pkg_label(name, form_factor),
                "icon":    pkg_icon(pkg, form_factor),
                "pkg":     pkg,
                "patches": {},
                "targets": list(pkg_entry.get("targets", [])),
            }
        else:
            # The same app can declare several Compatibility entries (for
            # example APK and APKM builds); merge their supported versions.
            for target in pkg_entry.get("targets", []):
                if target not in by_pkg[key]["targets"]:
                    by_pkg[key]["targets"].append(target)
        # Deduplicate patches that appear across multiple packages
        if patch["name"] not in by_pkg[key]["patches"]:
            by_pkg[key]["patches"][patch["name"]] = patch


def anchor(name):
    """Convert a patch name to a GitHub-compatible anchor slug."""
    return re.sub(r"-+", "-", re.sub(r"[^a-z0-9]+", "-", name.lower())).strip("-")


def slug(text):
    """Build a stable app anchor, e.g. 'VTVGo vn.vtv.vtvgo' -> vtvgo-vnvtvvtvgo."""
    stripped = re.sub(r"[^\w\s]", "", text.lower())
    return re.sub(r"[^a-z0-9]+", "-", stripped).strip("-")


def patches_table(patches):
    """Render a sorted markdown table of patches with name, description, and options."""
    rows = [
        "| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |",
        "|----------|----------------|-----------|",
    ]
    for p in sorted(patches, key=lambda x: x["name"]):
        a = anchor(p["name"])
        options = p.get("options") or []
        if options:
            # Show only option titles as a bullet list
            parts = [opt.get("title") or opt.get("key") or "" for opt in options]
            opts_cell = "<br>".join(f"• {t}" for t in parts)
        else:
            opts_cell = ""
        desc = (p.get("description") or "").replace("\n", "<br>")
        rows.append(f"| [{p['name']}](#{a}) | {desc} | {opts_cell} |")
    return "\n".join(rows)


def versions_table(targets):
    """Render a markdown table of supported versions.
    Experimental versions get a 🧪 prefix.
    Versions with a description get it shown in a second row below.
    """
    if not targets:
        return ""

    cells = []
    for t in targets:
        ver   = t["version"]
        if ver is None:
            continue
        label = f"🧪&nbsp;{ver}" if t.get("isExperimental") else ver
        cells.append(label)

    if not cells:
        return ""

    header = "| " + " | ".join(cells) + " |"
    sep = "| " + " | ".join(":---:" for _ in cells) + " |"
    rows = [header, sep]

    # Optional description row — only rendered if at least one target has one
    descs = [(t.get("description") or "").replace("\n", "<br>") for t in targets]
    if any(descs):
        rows.append("| " + " | ".join(descs) + " |")

    return "\n".join(rows)


def spoiler(label, count, targets, tbl, expanded=False):
    """Wrap a patches table in a <details> spoiler with a versions sub-table.
    If expanded=True, the spoiler is open by default (for repos with few patches).
    """
    noun = "patch" if count == 1 else "patches"
    vtbl = versions_table(targets)
    versions_section = f"**🎯 Supported versions:**\n\n{vtbl}\n\n" if vtbl else ""
    tag = "<details open>" if expanded else "<details>"
    return f"""{tag}
<summary>{label}&nbsp;&nbsp;•&nbsp;&nbsp;{count} {noun}</summary>
<br>

{versions_section}{tbl}

</details>"""


def build_content(expanded=False):
    """Build the full generated patches section."""
    lines = [
        f"> **[v{ver}](https://github.com/{owner}/{repo}/releases/tag/v{ver})**"
        f"&nbsp;&nbsp;•&nbsp;&nbsp;`{branch}`&nbsp;&nbsp;•&nbsp;&nbsp;"
        f"{total} patches total"
    ]

    # One spoiler per app, in the order they appear in the JSON
    for entry in by_pkg.values():
        patches   = list(entry["patches"].values())
        label     = f"{entry['icon']} {entry['name']}"
        anchor_id = slug(f"{entry['name']} {entry['pkg']}")
        lines.append(f'<a id="{anchor_id}"></a>')
        lines.append(spoiler(label, len(patches), entry["targets"], patches_table(patches), expanded))
        lines.append("")

    # Universal patches (no specific app)
    if universal:
        uni_patches = list(universal.values())
        noun = "patch" if len(uni_patches) == 1 else "patches"
        tag  = "<details open>" if expanded else "<details>"
        lines.append('<a id="universal"></a>')
        lines.append(f"""{tag}
<summary>🌐 Universal&nbsp;&nbsp;•&nbsp;&nbsp;{len(uni_patches)} {noun}</summary>
<br>

{patches_table(uni_patches)}

</details>""")
        lines.append("")

    return "\n".join(lines)


# Build the standalone document
raw_ver = data["version"]
# Strip leading "v" if present
ver   = raw_ver.lstrip("v")
total = sum(len(e["patches"]) for e in by_pkg.values()) + len(universal)

header = (
    "# Patches list\n\n"
    "Every patch bundled in this source, grouped by app. See the "
    "[README](README.md) for the supported apps table.\n\n"
)

# PATCHES.md is a dedicated details document, so keep every app section open.
content = build_content(expanded=True)

output_path.write_text(header + content + "\n", encoding="utf-8")
print(f"Wrote {output_path} (v{ver}, branch={branch}, {total} patches)")
