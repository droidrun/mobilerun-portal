# AGENTS.md

Guidance for AI coding agents (Claude Code, Codex, etc.) working in this repository. It encodes the invariants a human reviewer checks on every PR.

## What this repo is

Mobilerun Portal — the Android app (Kotlin, single Gradle module `app/`) that lets AI agents control and observe a phone: accessibility-tree capture, screenshots, input injection, notification/SMS/battery/app events, local HTTP/WebSocket APIs, and the trigger runtime that fires agent tasks from device events.

- Build: `./gradlew assembleDebug` (Kotlin DSL, `build.gradle.kts`)
- Tests: `./gradlew testDebugUnitTest`
- **CI only builds the APK; it does not run unit tests.** The APK build workflows run `assembleDebug` (and `build-and-release.yml` also runs `assembleRelease`); neither runs the unit tests. The exhaustiveness tests below fail nowhere unless you run them locally — so run `./gradlew testDebugUnitTest` before pushing any change to `triggers/` or `events/`.

## Module layout

`app/src/main/java/com/mobilerun/portal/`:

| Package | Responsibility |
| --- | --- |
| `api/` | Local HTTP/WebSocket API surface |
| `auth/` | Token auth for local endpoints |
| `config/` | Config persistence (`ConfigManager`) |
| `core/` | Shared runtime utilities |
| `deeplinks/` | Deep-link handling |
| `events/` | Device event capture; `events/model/EventType.kt` defines the event enum |
| `input/` | Input injection (tap, swipe, text, including the Mobilerun IME) |
| `keepalive/` | Foreground service longevity |
| `location/` | Location events |
| `model/` | Shared data models |
| `service/` | Accessibility service, notification listener, portal service |
| `sms/` | SMS events |
| `state/` | Device/UI state snapshots |
| `streaming/` | Screen streaming |
| `taskprompt/` | Task prompt settings |
| `triggers/` | Trigger rules: `TriggerModels.kt` (enums/rules), `TriggerRuntime.kt` (evaluation loop), `TriggerEditorSupport.kt` (editor UI support) |
| `ui/` | Activities, fragments, ViewBinding |
| `update/` | Self-update flow |

Additional docs live in `docs/` (`triggers.md`, `deep-links.md`, `local-api.md`, `websocket-events.md`, `reverse-connection.md`). When a change alters documented behavior, update the matching doc in the same PR.

## Invariant 1 — every `EventType` gets an explicit trigger decision

`TriggerRuntime.triggerSourceFor` (`app/src/main/java/com/mobilerun/portal/triggers/TriggerRuntime.kt`) maps `EventType` → `TriggerSource?` with an **exhaustive `when` and no `else`**, so the compiler forces a decision for each new enum member. Protocol/legacy/status members (`NOTIFICATION`, `SMS_SEND_STATE_CHANGED`, `PING`, `PONG`, `UNKNOWN`) map to `null` on purpose: they never fire triggers.

When adding an `EventType`:

1. Add an explicit arm to `triggerSourceFor` — a paired `TriggerSource`, or `null` under the protocol/legacy comment if it must never fire. Rationale (from the KDoc): an unmapped event would otherwise advertise a trigger that never fires in the API catalogue and UI picker.
2. Make sure the event is emitted with the expected JSON payload and matching trigger fields. No per-event code is needed in `TriggerRuntime.portalEventToSignal`: it already flattens any `JSONObject` payload into a `TriggerSignal` generically.
3. Update the exhaustiveness test `TriggerCoreTest.`portal event mapping decides every event type`` (`app/src/test/java/com/mobilerun/portal/triggers/TriggerCoreTest.kt`): add the member to `expected` (mapped) or `intentionallyUnmapped` (null). The test asserts `expected.keys == EventType.entries - intentionallyUnmapped`, so a missing entry fails the test — but only locally, since CI does not run tests.

Copy the existing pattern in that test rather than inventing a new one.

## Invariant 2 — a new `TriggerSource` touches several places

`TriggerSource` (`app/src/main/java/com/mobilerun/portal/triggers/TriggerModels.kt`) is consumed by editor support, UI support and the runtime. When adding a member:

1. `TriggerEditorSupport.visibilityFor` (`TriggerEditorSupport.kt`) — exhaustive `when`, no `else`; the compiler forces you to decide what the editor shows for the new source. `capabilitiesFor` and `defaultCooldownSecondsFor` derive from `visibilityFor`, so cooldown/run-limit support follows automatically — they are not separate edit sites. The decision is pinned by `TriggerCoreTest.`every trigger source has an intended visibility and cooldown decision``.
2. `TriggerUiSupport.sourceDescription` (`TriggerUiSupport.kt`) — the user-facing description of the source. `TriggerCoreTest.`source descriptions match remaining trigger sources`` fails if a member's description is missing or stale.
3. `TriggerCoreTest.`every trigger source has a notification access and debounce decision`` — add the `Pair(requiresNotificationAccess, shouldDebounce)` entry. The test asserts `expected.keys == TriggerSource.entries.toSet()`, so a missing entry fails (locally only — see CI note above).
4. `TriggerRuntime.buildTestSignal` (`TriggerRuntime.kt`) — exhaustive `when` over the source, so the compiler forces a sample-payload arm for the new member; test runs build their signal here.
5. `TriggerRuntime.missingPermissions` (`TriggerRuntime.kt`) — if the new source needs a runtime permission, add an explicit arm. Its `else -> Unit` fallback (see Code style) means an unlisted source is silently treated as needing no permission.
6. `docs/triggers.md` — document the new source, and check the editor UI picks it up (`ui/`).

Where the signal originates depends on the kind of source: a device-event source also needs its event producer — the component that emits the `PortalEvent` (see Invariant 1); scheduled (time-based) sources follow a different path and have no portal-event producer.

## Invariant 3 — the two notification predicates are different on purpose

Do not "unify" these:

- `TriggerEditorSupport.requiresNotificationAccess` returns `true` for `NOTIFICATION_POSTED` **and** `NOTIFICATION_REMOVED` — both need the notification-listener permission.
- `TriggerRuntime.shouldDebounce` returns `true` for `NOTIFICATION_POSTED` only — the debounce buffer batches message title/text per sender, which removed notifications don't carry, so `NOTIFICATION_REMOVED` evaluates immediately.

Both are covered by the paired-decision test in Invariant 2; if you change one predicate, update the corresponding `Pair` there and keep the KDoc rationale intact.

## Code style

See `code-style.md` (short, enforced in review):

- Prefer direct `binding.someView` access over one-line private getters that only return a binding view.
- Use resources or constants for repeated strings, colors, dimensions, integer values, timeouts, and similar magic values.

Beyond that: match the surrounding Kotlin idiom. Where a `when` over an enum must force an explicit per-member decision, keep it exhaustive without `else` (that is the mechanism behind Invariants 1 and 2). Not every mapping is of that kind: `TriggerUiSupport.sourceLabel` and `TriggerRuntime.missingPermissions` deliberately keep fallback branches (`else -> source.name`, `else -> Unit`) — leave those in place.

## CI and the PR agent

- `claude.yml` runs Claude on `@claude` mentions in issue/PR comments and reviews.
- `pr-test-build.yml` builds a debug APK per PR and posts the artifact link; it does not compile tests or run them.
- A PR that changes trigger/event behavior should state in its description that `./gradlew testDebugUnitTest` passes locally, since CI cannot verify it.
