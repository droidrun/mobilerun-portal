# Local API

Mobilerun Portal exposes local control over HTTP, WebSocket, and ContentProvider. Use these when the device is on the same network or connected via ADB.

## Auth token

Local HTTP and WebSocket access require a token (except `GET /ping`). You can copy it from the main screen or query it via ADB:

```bash
adb shell content query --uri content://com.mobilerun.portal/auth_token
```

Response example:

```json
{"status":"success","result":"YOUR_TOKEN"}
```

## Transport matrix

| Capability | ADB ContentProvider | Local HTTP | Local WebSocket | Reverse WebSocket |
| --- | --- | --- | --- | --- |
| Tree/state/version/packages | Yes | Yes | Yes | Yes when Accessibility is ready; headless subset otherwise |
| Keyboard/overlay/config actions | Yes | Yes | Yes | Yes when Accessibility is ready |
| Clipboard get/set | Yes | Yes | Yes | Yes when Mobilerun Keyboard is available |
| Screenshot | No direct endpoint | Yes | Yes | Yes when Accessibility is ready |
| Deep-link discovery / resolution | Query | POST | Yes | Yes, including headless mode |
| SMS (`sms/*`) | Query + `sms/send` insert | POST | Yes | Yes, including headless mode |
| APK install | No | No | Yes | Yes |
| WebRTC streaming + signaling | No | No | No | Yes |
| `files/*` | No | Yes on Android 11+ | Yes on Android 11+ | Yes on Android 11+ |

Android 8-10 support is a compatibility tier. Core control, screenshots, install flows, and reverse streaming are supported where the transport exposes them. `files/*` remains disabled below Android 11 in the current build.

## WebSocket (JSON-RPC-style)

Enable **WebSocket Server** in Settings. Default port is `8081`.

Connect with an auth token:

```bash
wscat -c "ws://localhost:8081/?token=YOUR_TOKEN"
```

Request format:

```json
{
  "id": "uuid-or-number",
  "method": "tap",
  "params": { "x": 200, "y": 400 }
}
```

Response format:

```json
{
  "id": "uuid-or-number",
  "status": "success",
  "result": "..."
}
```

### Supported methods

| Method | Params | Notes |
| --- | --- | --- |
| `tap` | `x`, `y` | Tap screen coordinates |
| `swipe` | `startX`, `startY`, `endX`, `endY`, `duration` | Duration in ms (optional) |
| `global` | `action` | Accessibility global action ID |
| `app` | `package`, `activity`, `stopBeforeLaunch` | `activity` optional; `stopBeforeLaunch` defaults to `false` |
| `app/deep-link` | `deepLink`, `displayId`, `package`, `action` | Opens an implicit URI intent; `displayId` defaults to `0`, `action` defaults to `android.intent.action.VIEW`, and `package` is optional (`packageName` is also accepted) |
| `app/deep-links` | `package` | Read manifest declarations without launching; no Accessibility required |
| `app/deep-link/resolve` | `package`, `deepLink`, `action` | Read Android resolver matches without launching; `action` defaults to `android.intent.action.VIEW` |
| `app/stop` | `package` | Best-effort stop (non-privileged app) |
| `keyboard/input` | `base64_text`, `clear` | `clear` defaults to `true` |
| `keyboard/clear` | - | Clears focused input |
| `keyboard/key` | `key_code` | Uses Android key codes |
| `clipboard/get` | - | Reads clipboard text; requires Mobilerun Keyboard to be selected |
| `clipboard/set` | `text` or `text_base64` | Sets clipboard text |
| `overlay_offset` | `offset` | Vertical offset in pixels |
| `socket_port` | `port` | Updates HTTP server port |
| `screenshot` | `hideOverlay` | Default `true` |
| `packages` | - | List launchable packages |
| `state` | `filter` | Full state; `filter=false` keeps small elements |
| `version` | - | App version |
| `time` | - | Unix ms timestamp |
| `sms/capabilities` | - | Gateway switch (`gateway_enabled`), SMS permissions, SMS messaging availability, active subscriptions |
| `sms/send` | `phone_number`, `message`, optional `message_id`, `subscription_id` or `sim_slot`, `delivery_report` | Sends one text SMS; multipart bodies are split automatically |
| `sms/status` | `message_id` | Returns in-memory send state for recent sends |
| `sms/messages/list` | `box`, `limit`, `since`, `phone_number` | Reads stored SMS when `READ_SMS` is granted |
| `sms/messages/get` | `sms_id` | Reads one stored SMS when `READ_SMS` is granted |
| `files/list` | `path` | Android 11+ only in the current compatibility tier |
| `files/download` | `path` | Android 11+ only in the current compatibility tier |
| `files/upload` | `path`, `data` | Android 11+ only in the current compatibility tier |
| `files/delete` | `path` | Android 11+ only in the current compatibility tier |
| `files/fetch` | `url`, `path` | Android 11+ only in the current compatibility tier |
| `files/push` | `url`, `path` | Android 11+ only in the current compatibility tier |
| `install` | `urls`, `hideOverlay` | Local and reverse WebSocket only; supports split APKs |
| `screen/keepAwake/set` | `enabled` | WebSocket only; local and reverse; toggles the screen-awake watchdog and returns the requested target status |
| `screen/keepAwake/status` | - | WebSocket only; local and reverse; returns live watchdog status |
| `cloud/reconnectAfterRestart/set` | `enabled` | HTTP and local WebSocket; turns reconnect after restart on or off; enabling requires saved cloud credentials |
| `cloud/reconnectAfterRestart/status` | - | Returns `enabled` and `credentialsPresent` |

Streaming methods (`stream/start`, `stream/stop`, `webrtc/*`) are only available over reverse connection.
Screen-awake methods (`screen/keepAwake/*`) are available over local and reverse WebSocket. They are not available over HTTP.
Trigger management methods (`triggers/*`) are available over the local WebSocket API and reverse connection. See [Triggers and Events](triggers.md) for the exact method list, params, validation rules, and response shape.
Install is available over local and reverse WebSocket. It is not available over HTTP or the ContentProvider.
SMS methods (`sms/*`) are available over HTTP POST, local WebSocket, and reverse connection. `sms/send` requires `SEND_SMS`; `sms/messages/*` requires `READ_SMS`; SIM slot lookup requires `READ_PHONE_STATE`.
The SMS gateway is off by default. While it is off, every `sms/*` method except `sms/capabilities` returns the error `SMS gateway is disabled in Portal settings`. Turn it on with the **SMS Gateway** switch in Settings > Permissions or the `sms/gateway` ContentProvider insert; it cannot be changed over HTTP or WebSocket.
Local WebSocket sends unsolicited device events as raw
`{ "type": "...", "timestamp": ..., "payload": { ... } }` by default. Add
`?eventFormat=rpc` to request the same
`{ "method": "events/device", "params": { ... } }` envelope used by reverse connection.
`?eventFormat=legacy` is an explicit no-op for the default raw format. Raw local `PING` /
`PONG` compatibility remains unchanged. See [Reverse Connection](reverse-connection.md) for
the reverse notification shape.

Install notes:

- The device must allow **Install unknown apps** for Mobilerun Portal.
- Enable **Install Auto-Accept** in Settings to auto-confirm install prompts.

### Screenshot response format

`screenshot` returns a JSON response with a **base64-encoded PNG string** in `result`
over HTTP, local WebSocket, and reverse WebSocket. HTTP uses
`Content-Type: application/json`; both WebSocket transports send a **text frame**. Screenshots do
not use binary frames or a request-ID prefix.

WebSocket request and successful response (the `id` retains its original string or
number type):

```json
{"id":42,"method":"screenshot","params":{"hideOverlay":false}}
{"id":42,"status":"success","result":"<base64-encoded PNG>"}
```

A capture failure returns an error envelope instead of image data, for example:

```json
{"id":42,"status":"error","error":"Screenshot failed: Secure window cannot be captured"}
```

Save an HTTP screenshot as `screenshot.png`:

```bash
curl -s -H "Authorization: Bearer YOUR_TOKEN" 'http://localhost:8080/screenshot?hideOverlay=false' | jq -er 'select(.status=="success").result' | base64 -d > screenshot.png
```

`hideOverlay` defaults to `true`; set it to `false` to keep Portal's overlay in the
capture.

### Binary WebSocket responses

Local WebSocket binary responses, such as `files/download`, use a binary frame:

- First 36 bytes: request ID string (UUID)
- Remaining bytes: raw binary payload

## HTTP socket server

Enable **HTTP Server** in Settings. Default port is `8080`.

Authentication header:

```
Authorization: Bearer YOUR_TOKEN
```

Example:

```bash
curl -H "Authorization: Bearer YOUR_TOKEN" http://localhost:8080/ping
```

### GET endpoints

- `/ping` (no auth required)
- `/a11y_tree`
- `/a11y_tree_full?filter=true|false`
- `/state`
- `/state_full?filter=true|false`
- `/phone_state`
- `/version`
- `/packages`
- `/clipboard/get` (requires Mobilerun Keyboard to be selected)
- `/screenshot?hideOverlay=false` (JSON envelope containing base64 PNG; see [Screenshot response format](#screenshot-response-format))

### POST endpoints

POST requests map to the same method names as WebSocket (e.g., `/tap`, `/action/tap`, `/keyboard/input`).
Bodies are limited to 1 MiB (24 MiB for `/files/upload`); larger bodies return HTTP `413`. A malformed
body, an invalid `Content-Length`, or a JSON `null` value returns HTTP `400`.

Example:

```bash
curl -X POST \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "x=200&y=400" \
  http://localhost:8080/tap
```

Opening deep links is authenticated and requires Accessibility, like `app` launches. Android resolves
the URI and any optional package pin; custom schemes are supported.

```bash
curl -X POST \
  -H "Authorization: Bearer YOUR_TOKEN" \
  --data-urlencode "deepLink=myapp://path" \
  --data-urlencode "displayId=0" \
  http://localhost:8080/app/deep-link
```

See [Deep-link inspection](deep-links.md) for `/app/deep-links` and `/app/deep-link/resolve`.

SMS examples:

```bash
curl -X POST \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"phone_number":"+15550001111","message":"hello","delivery_report":true}' \
  http://localhost:8080/sms/send

curl -X POST \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"box":"inbox","limit":10}' \
  http://localhost:8080/sms/messages/list
```

## ContentProvider (ADB)

All commands use `content://com.mobilerun.portal/`.

The ContentProvider covers core tree/state/keyboard/clipboard/overlay/config and SMS flows. It does not expose direct screenshot, install, or WebRTC streaming endpoints.

Trigger queries and mutations are also available through the same `ContentProvider`, including `triggers/catalog`, `triggers/status`, rule CRUD, enable/disable, test runs, and run history management. See [Triggers and Events](triggers.md) for the full URI list and `rule_json_base64` examples.

### Query

```bash
adb shell content query --uri content://com.mobilerun.portal/ping
adb shell content query --uri content://com.mobilerun.portal/version
adb shell content query --uri content://com.mobilerun.portal/a11y_tree
adb shell content query --uri content://com.mobilerun.portal/a11y_tree_full?filter=false
adb shell content query --uri content://com.mobilerun.portal/phone_state
adb shell content query --uri content://com.mobilerun.portal/state
adb shell content query --uri content://com.mobilerun.portal/state_full?filter=false
adb shell content query --uri content://com.mobilerun.portal/packages
adb shell content query --uri content://com.mobilerun.portal/auth_token
adb shell content query --uri content://com.mobilerun.portal/clipboard/get
adb shell content query --uri content://com.mobilerun.portal/screen_keep_awake_status
adb shell content query --uri content://com.mobilerun.portal/stream_session_limit
adb shell content query --uri content://com.mobilerun.portal/cloud/reconnect_after_restart
adb shell content query --uri content://com.mobilerun.portal/sms/capabilities
adb shell content query --uri 'content://com.mobilerun.portal/sms/status?message_id=MSG_ID'
adb shell "content query --uri 'content://com.mobilerun.portal/sms/messages?box=inbox&limit=10'"
adb shell content query --uri content://com.mobilerun.portal/sms/messages/SMS_ID
adb shell "content query --uri 'content://com.mobilerun.portal/app/deep-links?package=com.example.app'"
```

### Insert

```bash
adb shell content insert --uri content://com.mobilerun.portal/keyboard/input --bind base64_text:s:"SGVsbG8="
adb shell content insert --uri content://com.mobilerun.portal/keyboard/clear
adb shell content insert --uri content://com.mobilerun.portal/keyboard/key --bind key_code:i:66

adb shell content insert --uri content://com.mobilerun.portal/clipboard/set --bind text:s:"Hello World"
adb shell content insert --uri content://com.mobilerun.portal/clipboard/set --bind text_base64:s:"SGVsbG8gV29ybGQ="

adb shell content insert --uri content://com.mobilerun.portal/overlay_offset --bind offset:i:100
adb shell content insert --uri content://com.mobilerun.portal/overlay_visible --bind visible:b:false
adb shell content insert --uri content://com.mobilerun.portal/socket_port --bind port:i:8090

adb shell content insert --uri content://com.mobilerun.portal/toggle_websocket_server --bind enabled:b:true --bind port:i:8081
adb shell content insert --uri content://com.mobilerun.portal/toggle_screen_keep_awake --bind enabled:b:true
adb shell content insert --uri content://com.mobilerun.portal/toggle_screen_keep_awake --bind enabled:b:false

# Max concurrent stream viewers, the main stream included (1-5, default 3); applies to new viewers
adb shell content insert --uri content://com.mobilerun.portal/stream_session_limit --bind limit:i:4

adb shell content insert --uri content://com.mobilerun.portal/configure_reverse_connection --bind url_base64:s:"d3NzOi8vYXBpLm1vYmlsZXJ1bi5haS92MS9wcm92aWRlcnMvcGVyc29uYWwvam9pbg==" --bind token_base64:s:"WU9VUl9UT0tFTg==" --bind enabled:b:true
adb shell content insert --uri content://com.mobilerun.portal/configure_reverse_connection --bind service_key_base64:s:"WU9VUl9LRVk="

adb shell content insert --uri content://com.mobilerun.portal/toggle_production_mode --bind enabled:b:true

# Reconnect to the cloud after a reboot or app update (default off; needs saved cloud credentials)
adb shell content insert --uri content://com.mobilerun.portal/cloud/reconnect_after_restart --bind enabled:b:true
adb shell content insert --uri content://com.mobilerun.portal/cloud/reconnect_after_restart --bind enabled:b:false

adb shell content insert --uri content://com.mobilerun.portal/sms/gateway --bind enabled:b:true
adb shell content insert --uri content://com.mobilerun.portal/sms/gateway --bind enabled:b:false

adb shell content insert --uri content://com.mobilerun.portal/sms/send \
  --bind phone_number:s:+15550001111 \
  --bind message_base64:s:$(printf 'hello from Portal' | base64)
```
