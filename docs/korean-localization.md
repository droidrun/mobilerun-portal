# Korean localization

This change adds Korean (ko) interface strings for Mobilerun Portal v0.8.2.

## Scope

- Localizes all 355 existing strings from values/strings.xml.
- Extracts 81 user-facing layout labels previously hard-coded in XML to paired English and Korean resources.
- Extracts 52 user-facing UI messages from Kotlin activities into paired English and Korean resources.
- Localizes 105 additional trigger screen and editor resources (92 trigger UI strings and 13 trigger editor strings).
- Keeps English resources as the default. Android selects Korean when the current language is Korean.
- Preserves package identifiers, URLs, HTTP/WebSocket methods, accessibility service behavior, permissions, authentication and application workflows.

All Android resource identifiers and formatted arguments remain identical between English and Korean. Technical values such as GET, POST, API endpoints, version numbers and brand names remain unchanged intentionally.

## Validation

Use JDK 21 and an installed Android SDK.

    python scripts/validate_ko.py
    python scripts/audit_hardcoded_ui.py
    ./gradlew :app:assembleDebug :app:testDebugUnitTest --no-daemon

Results from a Windows build:

| Check | Result |
| --- | --- |
| English string resources | 593 |
| Korean string resources | 593 |
| Missing/extra string keys | 0 |
| Format argument mismatches | 0 |
| Line-break mismatches | 0 |
| Android build | Passed |
| JVM unit tests | 1,125 passed, 0 failed, 0 skipped |
| APK locale metadata | ko present |

The built APK is a debug-signed test artifact and has not been installed over the official release app. The official signing key may differ; do not uninstall a working Portal installation just to test localization because this may remove saved pairing, permission and connection settings.

## Maintainer notes

- Translation sources are values-ko/strings.xml, values-ko/strings_layout_ui.xml, and values-ko/strings_kotlin_ui.xml.
- scripts/ui_literals_ko.json and scripts/kotlin_literals_ko.json record reviewed English/Korean mappings.
- scripts/localize_layout_literals.py and scripts/localize_kotlin_ui.py show how hard-coded strings were extracted; code changes are already committed.
- scripts/validate_ko.py checks locale parity and formatting without external dependencies.
- Some errors returned by Android or external services may still appear in English. Device-level visual and accessibility testing remains desirable before release.

The upstream project's AGPL-3.0-or-later license and existing author attribution are preserved.

## Existing Fold7 installation: pre-approval check (2026-11-10)

- The user's request targets the Portal already installed on Fold7, not SAVE PILOT or the emulator.
- Fetched upstream `main` (`49d9211`) and fork `feature/korean-ui` (`acd86bd`) before the check. The Korean branch is 13 commits ahead of upstream with no missing upstream commits; the worktree was clean.
- Re-ran only `scripts/validate_ko.py` and `scripts/audit_hardcoded_ui.py`: 593/593 localized strings, no missing keys, format or newline mismatches. The remaining literal matches are the brand name and an ADB command.
- Existing candidate: `app/build/outputs/apk/debug/com.mobilerun.portal-0.8.2-debug.apk`, version `0.8.2` / code `104`, Korean locale present.
- Candidate SHA-256: `DC72944CE9ABC326EF408FF9DEAE288071C3C199511B6E993D011BF4F227751F`.
- Candidate signer SHA-256: `ab3f5d21e1e20185ecfbfd68573021da957c55849935c54b7f088b16cb278071`.
- Official `0.8.2` APK previously used for the phone has signer SHA-256 `f425a9af490803f5e10512c4cc47b9bedefa802b04ba8712a39a001efa033406`. This is a comparison of the local official APK and candidate; the phone's installed base.apk was not extracted for an independent certificate comparison.
- The local official APK has no Korean application UI resources; changing language alone does not add translations. The official signing keystore is not configured in this environment.
- Android requires a compatible signing identity for an update. See [Android app signing considerations](https://developer.android.com/studio/publish/app-signing#considerations).
- Phone replacement is NOT RUN. No uninstall, app-data clearing, pairing reset, or permission change was performed. Replacing the official installation may clear Portal's saved connection and permissions, so informed user approval is pending before removal. SAVE PILOT data is outside this operation.
- No rebuild, full test run, temporary worktree, additional APK copy, or test profile was needed. The existing candidate is retained in the build output directory.

## Actual Fold7 replacement and permission restoration (2026-11-10)

The user subsequently approved replacement and explicitly required the old permissions to be restored. This section supersedes the earlier NOT RUN deployment status.

- Verified the authorized physical Fold7 (`SM-F966N`, Android API 36, owner user 0) over USB before removing the old app. Emulator connections were not used as phone evidence.
- Created an owner/SYSTEM-only protected backup at `%LOCALAPPDATA%\MobilerunPortalBackup\20261110-before-korean`. It retains the original installed APK, permission/app-op snapshots, accessibility/IME/listener/battery state, connection metadata and private settings evidence. Credentials and device IDs were not printed or committed.
- Independently extracted the installed official APK before removal: SHA-256 `BEE2872917FF5CDB24641738FE09A88DF1E252A712247044C3D13A544DCC12D8`, official signer matched the preflight comparison.
- Confirmed the official and Korean APKs request the same 27 permissions. Trigger-rule export succeeded with zero existing rules.
- Copied the Korean APK to the phone's Downloads and verified its SHA-256 before uninstalling the original. The independent USB installation/recovery path and original APK backup were ready first.
- Uninstalled only `com.mobilerun.portal`, installed the existing Korean APK, and restored the old connection identity, local API token, private bridge credential, reconnect preference, app settings and previous permission state. The backend pairing pin and token were preserved.
- Used app-scoped Korean locale `ko-KR`. Device-wide language was not changed.

| Check | Result |
| --- | --- |
| Actual installed APK version | PASS: `0.8.2` / versionCode `104` |
| Actual phone base.apk SHA-256 | PASS: `DC72944CE9ABC326EF408FF9DEAE288071C3C199511B6E993D011BF4F227751F`, exact candidate match |
| Install/runtime permission grant entries | PASS: all 20 original entries match; all 11 runtime permissions remain denied |
| UID app-op modes | PASS: all 45 original modes match |
| Package app-op modes | PASS: all 7 original explicit modes match |
| Accessibility services, enabled IMEs and default keyboard | PASS: exact saved values restored, other apps preserved |
| Portal notification-listener state and battery exemption | PASS: same as before replacement |
| Actual Portal UI settings | PASS: all 20 original switches/input fields match; includes auto-accept installs, reconnect, disabled local servers and disabled SMS/audio options |
| Korean UI on Fold7 | PASS: Settings label and Korean app-install auto-accept label observed in the actual phone accessibility tree |
| Local API authentication and accessibility state API | PASS: original local token retained and state query succeeds |
| Tailscale/Portal reverse connection | PASS: connected and authenticated, original pairing identity retained |
| Reconnect-after-restart readiness | PASS: enabled with saved credentials; actual phone reboot NOT RUN |

During restoration, a keyboard-subtype list containing semicolons needed explicit remote-shell quoting. This was corrected and the complete original enabled-IME/default-keyboard state was reverified. UID-level app-op overrides and package-level modes were compared separately; a flattened mode list is not a valid comparison. Android `content insert` returning empty stdout was verified through subsequent connection/status queries rather than treated as a failed mutation.

No product source change or rebuild was needed; the existing localization candidate was reused. Product-source history remains at `ea92b984fc792a31847bad6cc5bbd21413685b44`; later commits add testing or deployment records. Existing complete JVM test results are historical and were not rerun for this device replacement.

Cleanup: removed the verified temporary phone Downloads APK (57,011,149 bytes reclaimed). Kept the protected original/setting backup for recovery and the single existing Korean build candidate. No temporary worktree or test profile was created. Installed apps, SAVE PILOT data and protected backups were not cleanup targets.
