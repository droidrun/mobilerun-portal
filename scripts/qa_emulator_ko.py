#!/usr/bin/env python3
"""Read-only Korean locale UI smoke test on an Android emulator.

Safely installs only onto a verified Android emulator, navigates only the
Mobilerun Portal UI, and does not grant permissions or save any trigger rules.
Requires the debug APK to have already been built.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import tempfile
import time
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "com.mobilerun.portal-0.8.2-debug.apk"
PACKAGE = "com.mobilerun.portal"
BOUNDS = re.compile(r"^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$")


def run_adb(serial: str, *args: str, binary: bool = False):
    result = subprocess.run(
        ["adb", "-s", serial, *args], capture_output=True,
        text=not binary, timeout=45, check=True,
    )
    return result.stdout


def inspect(serial: str) -> ET.Element:
    dump_path = "/sdcard/ko-qa-hierarchy.xml"
    run_adb(serial, "shell", "uiautomator", "dump", dump_path)
    with tempfile.TemporaryDirectory() as folder:
        path = Path(folder) / "hierarchy.xml"
        run_adb(serial, "pull", dump_path, str(path))
        return ET.parse(path).getroot()


def visible_texts(root: ET.Element) -> list[str]:
    return [node.attrib["text"] for node in root.iter()
            if node.attrib.get("text")]


def find_node(root: ET.Element, *, resource_id: str | None = None, text: str | None = None):
    for node in root.iter():
        attr = node.attrib
        if resource_id and not attr.get("resource-id", "").endswith("/" + resource_id):
            continue
        if text is not None and attr.get("text") != text:
            continue
        if attr.get("bounds") and attr.get("visible-to-user", "true") != "false":
            return node
    return None


def tap(serial: str, node: ET.Element):
    match = BOUNDS.fullmatch(node.attrib.get("bounds", ""))
    if not match:
        raise AssertionError("Target has no usable bounds.")
    x1, y1, x2, y2 = map(int, match.groups())
    if x2 <= x1 or y2 <= y1:
        raise AssertionError("Target bounds are invalid.")
    run_adb(serial, "shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(1.1)


def scroll_find(serial: str, *, resource_id=None, text=None, tries=8):
    for _ in range(tries):
        root = inspect(serial)
        node = find_node(root, resource_id=resource_id, text=text)
        if node is not None:
            return node
        run_adb(serial, "shell", "input", "swipe", "170", "525", "170", "150", "370")
        time.sleep(.5)
    raise AssertionError(f"Unable to locate UI element: {resource_id or text}")


def screenshot(serial: str, path: Path):
    path.parent.mkdir(parents=True, exist_ok=True)
    data = run_adb(serial, "exec-out", "screencap", "-p", binary=True)
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise AssertionError("Invalid screenshot response")
    path.write_bytes(data)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True, help="ADB serial of an isolated Android emulator")
    parser.add_argument("--evidence", type=Path, required=True, help="Local folder for QA screenshots")
    args = parser.parse_args()
    serial = args.serial
    if not serial.startswith("emulator-") or run_adb(serial, "shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise RuntimeError("Refusing UI test on a physical Android device.")
    if not APK.is_file():
        raise RuntimeError("Build the debug APK before running this smoke test.")

    assert "Success" in run_adb(serial, "install", "-r", str(APK))
    run_adb(serial, "shell", "cmd", "locale", "set-app-locales", PACKAGE, "--locales", "ko-KR")
    assert "ko-KR" in run_adb(serial, "shell", "cmd", "locale", "get-app-locales", PACKAGE)

    run_adb(serial, "shell", "am", "force-stop", PACKAGE)
    run_adb(serial, "shell", "am", "start", "-n", PACKAGE + "/.ui.MainActivity")
    time.sleep(2)
    home = inspect(serial)
    assert find_node(home, resource_id="settings_button") is not None, "Portal home did not launch"
    screenshot(serial, args.evidence / "home-ko.png")
    print("PASS: Korean home screen launched")

    tap(serial, find_node(home, resource_id="settings_button"))
    settings = inspect(serial)
    assert "설정" in visible_texts(settings), "Settings toolbar not localized"
    screenshot(serial, args.evidence / "settings-ko.png")
    print("PASS: Settings toolbar localized")

    tap(serial, scroll_find(serial, text="트리거 규칙 열기", tries=9))
    page = inspect(serial)
    text = visible_texts(page)
    assert "트리거" in text, "Trigger toolbar not visible or not localized"
    summary = find_node(page, resource_id="permissionsSummaryText")
    assert summary is not None
    summary_text = summary.attrib.get("text", "")
    assert "알림:" in summary_text and "연락처:" in summary_text and "Notification:" not in summary_text, summary_text
    for field in ("notificationAccessStatusText", "smsPermissionStatusText", "contactsPermissionStatusText"):
        value = find_node(page, resource_id=field)
        assert value is not None and value.attrib.get("text") in ("허용됨", "미허용"), field
    alarm = find_node(page, resource_id="exactAlarmSummaryText")
    if alarm is not None:
        assert alarm.attrib.get("text", "").startswith("정확한 알람"), "Exact alarm status not localized"
    screenshot(serial, args.evidence / "triggers-ko.png")
    print("PASS: Trigger status, permission and exact-alarm labels localized")

    tap(serial, scroll_find(serial, resource_id="addRuleButton", tries=5))
    editor = inspect(serial)
    editor_text = visible_texts(editor)
    assert "트리거 규칙 추가" in editor_text, "Trigger editor title not localized"
    source = find_node(editor, resource_id="inputTriggerSource")
    if source is None:
        raise AssertionError("Trigger source dropdown missing")
    assert source.attrib.get("text", "") == "알림 도착", source.attrib.get("text")
    desc = find_node(editor, resource_id="triggerSourceDescriptionText")
    assert desc is not None and "조건에 맞는 알림" in desc.attrib.get("text", ""), "Source description not localized"
    screenshot(serial, args.evidence / "trigger-editor-ko.png")
    print("PASS: Trigger editor and selected source localized")

    tap(serial, source)
    choices = visible_texts(inspect(serial))
    assert "지연 시간 후" in choices and "매일 반복" in choices, "Trigger source options not localized"
    screenshot(serial, args.evidence / "trigger-choices-ko.png")
    run_adb(serial, "shell", "input", "keyevent", "4")
    print("PASS: Trigger source dropdown localized")
    print(json.dumps({"result": "PASS", "steps": 5, "serial": serial,
                      "evidence": str(args.evidence)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
