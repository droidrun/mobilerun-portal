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
