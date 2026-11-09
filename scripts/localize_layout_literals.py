#!/usr/bin/env python3
"""One-time migration of UI literals in Android layout XML to localizable resources.

Do not modify permissions, package IDs, WebSocket methods, or any business logic.
The source layout file is modified only at android:text/hint/description attributes.
"""
from __future__ import annotations
from collections import defaultdict
from hashlib import sha256
from pathlib import Path
from xml.sax.saxutils import escape
import json
import re

ROOT = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res"
MAP = json.loads((Path(__file__).with_name("ui_literals_ko.json")).read_text(encoding="utf-8"))
ATTR = re.compile(r'(\bandroid:(?:text|hint|contentDescription|title|label)\s*=\s*")([^"]+)(")')
TECHNICAL = {"Mobilerun Portal"}
new_keys = {}
referenced = defaultdict(set)
unmatched = defaultdict(set)


def resource_name(value: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "_", value.lower()).strip("_")[:48].strip("_")
    return f"ko_ui_{slug}_{sha256(value.encode('utf-8')).hexdigest()[:8]}"


for path in sorted((ROOT / "layout").rglob("*.xml")):
    data = path.read_bytes().decode("utf-8")
    def replace(match: re.Match):
        literal = match.group(2)
        if literal.startswith(("@","?")):
            return match.group(0)
        if literal in MAP:
            key = resource_name(literal)
            new_keys[key] = (literal, MAP[literal])
            referenced[literal].add(str(path.relative_to(ROOT)))
            return match.group(1) + "@string/" + key + match.group(3)
        if re.search(r"[a-zA-Z]{3,}", literal) and literal not in TECHNICAL and "://" not in literal:
            unmatched[literal].add(str(path.relative_to(ROOT)))
        return match.group(0)

    output = ATTR.sub(replace, data)
    if output != data:
        path.write_bytes(output.encode("utf-8"))
    # Keep previously converted labels in the generated resource files.
    # Running this migration again must never delete resources.
    for literal, translated in MAP.items():
        key = resource_name(literal)
        if f"@string/{key}" in output:
            new_keys[key] = (literal, translated)
            referenced[literal].add(str(path.relative_to(ROOT)))

if unmatched:
    print("Untranslated visible layout literals:")
    for key, files in sorted(unmatched.items()):
        print(repr(key), sorted(files))
    raise SystemExit("Unhandled English layout string(s).")

for locale, i in (("values",0),("values-ko",1)):
    lines = ["<resources>", "    <!-- Generated resources for formerly hard-coded layout UI labels. -->"]
    for key,(source,ko) in sorted(new_keys.items()):
        lines.append(f'    <string name="{key}">{escape((source,ko)[i])}</string>')
    lines.extend(["</resources>",""])
    out = ROOT / locale / "strings_layout_ui.xml"
    out.write_bytes("\n".join(lines).encode("utf-8"))

print(f"Localized {len(new_keys)} distinct inline layout labels across {len(set(sum((list(x) for x in referenced.values()), [])))} layout files")
print(f"Missing dictionary entries: {len(unmatched)}")
