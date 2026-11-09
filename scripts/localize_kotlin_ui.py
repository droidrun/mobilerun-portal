#!/usr/bin/env python3
"""Extract explicitly reviewed Kotlin UI messages into Android string resources.

Only changes user-visible text in three activity screens and existing dialogs.
Does not alter connection commands, security, permissions, or business flow.
"""
from __future__ import annotations
from pathlib import Path
import json
from xml.sax.saxutils import escape

ROOT=Path(__file__).resolve().parents[1]
STRINGS=json.loads((Path(__file__).with_name("kotlin_literals_ko.json")).read_text(encoding="utf-8"))
RES=ROOT/"app"/"src"/"main"/"res"
for lang,index in (("values",0),("values-ko",1)):
    lines=["<resources>","    <!-- Localized messages formerly embedded in Kotlin UI activities. -->"]
    for key,values in STRINGS.items():
        value=escape(values[index]).replace("\n",r"\n")
        lines.append(f'    <string name="{key}">{value}</string>')
    lines.extend(["</resources>",""])
    (RES/lang/"strings_kotlin_ui.xml").write_bytes("\n".join(lines).encode("utf-8"))

ui=ROOT/"app"/"src"/"main"/"java"/"com"/"mobilerun"/"portal"/"ui"
# Kotlin template forms are built with chr(36) to preserve source literally.
dollar=chr(36)
patches={
"MainActivity.kt":[
    ('"Auto-offset reset: $newOffset"', 'getString(R.string.ko_action_auto_offset_reset, newOffset.toString())'),
    ('"Service not available"', 'getString(R.string.service_not_available)'),
    ('"Device ID: '+dollar+'{configManager.deviceID}"', 'getString(R.string.ko_action_device_id, configManager.deviceID)'),
    ('"Please enter an API key"', 'getString(R.string.ko_action_enter_api_key)'),
    ('"Connecting..."', 'getString(R.string.connecting_subtitle)'),
    ('"Please enter a WebSocket URL"', 'getString(R.string.ko_action_enter_ws_url)'),
    ('"URL must start with ws:// or wss://"', 'getString(R.string.ko_action_ws_scheme)'),
    ('"Connecting to custom server..."', 'getString(R.string.ko_action_connect_custom)'),
    ('"Reconnecting..."', 'getString(R.string.reconnecting_subtitle)'),
    ('"Token copied"', 'getString(R.string.ko_action_token_copied)'),
    ('"Value adjusted to valid range"', 'getString(R.string.ko_action_value_adjusted)'),
    ('"Please enter a valid number"', 'getString(R.string.ko_action_valid_number)'),
    ('"Error: '+dollar+'{e.message}"', 'getString(R.string.ko_action_error_detail, e.message)'),
    ('"Error: $error"', 'getString(R.string.ko_action_error_detail, error)'),
    ('"Error fetching data: '+dollar+'{e.message}"', 'getString(R.string.ko_action_error_fetch, e.message)'),
    ('"Combined state received successfully!"', 'getString(R.string.ko_action_state_received)'),
    ('"Could not open file access settings"', 'getString(R.string.ko_action_file_access_settings_error)'),
    ('"Error opening keyboard settings"', 'getString(R.string.ko_action_keyboard_settings_error)'),
    ('"Please enable Mobilerun Portal in Accessibility Services"', 'getString(R.string.ko_action_enable_accessibility)'),
    ('"Error opening accessibility settings"', 'getString(R.string.ko_action_accessibility_settings_error)'),
    ('binding.socketServerStatus.text = "Error"', 'binding.socketServerStatus.text = getString(R.string.ko_action_error)'),
    ('binding.adbForwardCommand.text = "Error"', 'binding.adbForwardCommand.text = getString(R.string.ko_action_error)'),
    ('"Version: $version"', 'getString(R.string.update_current_version, version)'),
    ('"Version: N/A"', 'getString(R.string.update_current_version, getString(R.string.task_details_value_unavailable))'),
    ('"No logs available. Fetch data first."', 'getString(R.string.ko_action_no_logs)'),
    ('.setTitle("Response Logs")', '.setTitle(R.string.ko_action_response_logs)'),
    ('.setPositiveButton("Close")', '.setPositiveButton(R.string.ko_action_close)'),
    ('.setNeutralButton("Copy")', '.setNeutralButton(R.string.ko_action_copy)'),
    ('android.content.ClipData.newPlainText("Response Logs", responseText)', 'android.content.ClipData.newPlainText(getString(R.string.ko_action_response_logs), responseText)'),
    ('"Logs copied to clipboard"', 'getString(R.string.ko_action_logs_copied)'),
    ('"Error showing logs: '+dollar+'{e.message}"', 'getString(R.string.ko_action_logs_show_error, e.message)'),
],
"settings/SettingsActivity.kt":[
    ('.setTitle("Reset to Defaults")','.setTitle(R.string.ko_action_reset_title)'),
    ('.setMessage("This will reset all settings to their default values and disconnect any active connections. Continue?")',
     '.setMessage(R.string.ko_action_reset_message)'),
    ('.setPositiveButton("Reset")','.setPositiveButton(R.string.ko_action_reset_button)'),
    ('"Settings reset to defaults"', 'R.string.ko_action_reset_done'),
    ('.setNegativeButton("Cancel",','.setNegativeButton(R.string.cancel,'),
],
"triggers/TriggerRuleEditorActivity.kt":[
    ('"Trigger rule enabled"', 'getString(R.string.ko_action_trigger_enabled)'),
    ('"Exact alarms do not need extra access on this Android version"',
     'getString(R.string.ko_action_exact_alarm_not_required)'),
    ('"Exact alarms are already enabled"',
     'getString(R.string.ko_action_exact_alarm_enabled)'),
    ('.setTitle("Choose delay")','.setTitle(R.string.ko_action_choose_delay)'),
    ('.setNegativeButton("Cancel",','.setNegativeButton(R.string.cancel,'),
    ('.setPositiveButton("Apply")','.setPositiveButton(R.string.ko_action_apply)'),
    ('.setTitleText("Choose date")','.setTitleText(getString(R.string.ko_action_choose_date))'),
    ('.setTitleText("Choose time")','.setTitleText(getString(R.string.ko_action_choose_time))'),
    ('"Trigger rule saved"','getString(R.string.ko_action_trigger_saved)'),
    ('.setTitle("Notification access required")','.setTitle(R.string.ko_action_notification_required)'),
    ('.setMessage("This trigger needs notification access to detect messages. The rule has been saved but kept disabled.\\n\\nEnable notification access, then activate the rule.")',
      '.setMessage(R.string.ko_action_notification_saved_disabled)'),
    ('.setPositiveButton("Go to Settings")','.setPositiveButton(R.string.ko_action_go_settings)'),
    ('.setNegativeButton("OK")','.setNegativeButton(R.string.ko_action_ok)'),
    ('"Cannot test: notification listener access is not granted"',
      'getString(R.string.ko_action_cannot_test_notifications)'),
    ('"Test run requested for '+dollar+'{savedRule.name}"',
      'getString(R.string.ko_action_test_run_requested, savedRule.name)'),
    ('.setTitle("Delete trigger")','.setTitle(R.string.ko_action_delete_trigger)'),
    ('.setMessage("Delete \''+dollar+'{rule.name}\'?")',
      '.setMessage(getString(R.string.ko_action_delete_trigger_message, rule.name))'),
    ('.setPositiveButton("Delete")','.setPositiveButton(R.string.ko_action_delete)'),
],
"triggers/TriggerRulesActivity.kt":[
    ('"Trigger rule enabled"', 'getString(R.string.ko_action_trigger_enabled)'),
    ('"SMS permission already granted"', 'getString(R.string.ko_action_sms_permission_granted)'),
    ('"Contacts permission already granted"', 'getString(R.string.ko_action_contacts_permission_granted)'),
    ('.setTitle("Clear recent runs")','.setTitle(R.string.ko_action_clear_recent)'),
    ('.setMessage("Remove all recent trigger run records?")',
      '.setMessage(R.string.ko_action_clear_recent_message)'),
    ('.setNegativeButton("Cancel",','.setNegativeButton(R.string.cancel,'),
    ('.setPositiveButton("Clear")','.setPositiveButton(R.string.ko_action_clear)'),
    ('"Notification triggers disabled — notification access is not granted"',
      'getString(R.string.ko_action_notification_triggers_disabled)'),
    ('.setTitle("Notification access required")','.setTitle(R.string.ko_action_notification_required)'),
    ('.setMessage("This trigger needs notification access to detect messages. Enable notification access, then activate the rule.")',
      '.setMessage(R.string.ko_action_notification_required_message)'),
    ('.setPositiveButton("Go to Settings")','.setPositiveButton(R.string.ko_action_go_settings)'),
    ('.setNegativeButton("OK",','.setNegativeButton(R.string.ko_action_ok,'),
    ('"Exact alarms do not need extra access on this Android version"',
      'getString(R.string.ko_action_exact_alarm_not_required)'),
    ('"Exact alarms are already enabled"',
      'getString(R.string.ko_action_exact_alarm_enabled)'),
],
}
for name, changes in patches.items():
    path=ui/name
    text=path.read_bytes().decode("utf-8")
    missing=[]
    for old,new in changes:
        if old not in text and new not in text:
            missing.append(old)
    if missing:
        raise RuntimeError(f"{path}: expected source missing: {missing!r}")
    for old,new in changes:
        text=text.replace(old,new)
    path.write_bytes(text.encode("utf-8"))
    print(f"{name}: {len(changes)} matched UI replacements")
print("Wrote localized Kotlin message resources; core operation unchanged.")
