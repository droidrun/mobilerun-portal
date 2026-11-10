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

## Existing Fold7 installation: deployment check (2026-11-10)

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
