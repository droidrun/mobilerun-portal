#!/usr/bin/env python3
"""Check locale parity and runtime string interpolation safety (stdlib only)."""
from __future__ import annotations
from collections import Counter
from pathlib import Path
import re
import sys
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res"
BASE = ROOT / "values" / "strings.xml"
KO = ROOT / "values-ko" / "strings.xml"
FORMAT = re.compile(r"%(?:\d+\$)?[sdfocxXeEgG]|%%")
INTERPOLATIONS = re.compile(r"%(?:\d+\$)?[sdfocxXeEgG]|%%")


def parse(path: Path):
    elements = ET.parse(path).getroot().findall("string")
    names = [element.attrib["name"] for element in elements]
    if len(names) != len(set(names)):
        raise AssertionError(f"{path}: duplicate string resources")
    return {item.attrib["name"]: item.text or "" for item in elements}


def merged(locale: str):
    result = {}
    for path in sorted((ROOT / locale).glob("strings*.xml")):
        data = parse(path)
        duplicate = set(result) & set(data)
        if duplicate:
            raise AssertionError(f"Duplicate resource keys in {locale}: {sorted(duplicate)}")
        result.update(data)
    return result


def main():
    source, ko = merged("values"), merged("values-ko")
    missing = sorted(source.keys() - ko.keys())
    unexpected = sorted(ko.keys() - source.keys())
    wrong_format = [
        key for key, text in source.items()
        if key in ko and Counter(FORMAT.findall(text)) != Counter(INTERPOLATIONS.findall(ko[key]))
    ]
    wrong_linebreaks = [
        key for key, text in source.items()
        if key in ko and text.count("\\n") != ko[key].count("\\n")
    ]
    unlocalized = [
        key for key, text in source.items()
        if key in ko and text == ko[key]
        and not (
            key == "app_name" or key.startswith("endpoint_")
            or key.startswith("http_method_")
            or key in {
                "default_offset_value", "default_port", "ip_label",
                "adb_forward_command_placeholder", "sms_cloud_sim_label",
                "ko_ui_websocket_url_e09731b4",
            }
        )
    ]
    # Ensure app-specific toolbar titles resolve through localized resources.
    title_attr = "{http://schemas.android.com/apk/res-auto}title"
    inline_titles = []
    for layout_path in sorted((ROOT.parent / "layout").glob("*.xml")):
        for element in ET.parse(layout_path).getroot().iter():
            title = element.attrib.get(title_attr)
            if title and not title.startswith(("@", "?")):
                inline_titles.append(f"{layout_path.name}: {title}")
    print(f"Inline app:title strings: {inline_titles}")
    print(f"Source: {len(source)} | Korean: {len(ko)}")
    print(f"Missing: {missing}")
    print(f"Unexpected: {unexpected}")
    print(f"Placeholder mismatches: {wrong_format}")
    print(f"Line break mismatches: {wrong_linebreaks}")
    print(f"Untranslated, excluding protocol tokens: {unlocalized}")
    if missing or unexpected or wrong_format or wrong_linebreaks or unlocalized or inline_titles:
        return 1
    print("Korean locale: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
