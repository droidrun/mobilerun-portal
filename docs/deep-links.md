# Deep-link inspection

Portal can inspect an installed app's manifest declarations and ask Android whether
a particular URI resolves. Both operations are read-only: they never open an app
and do not require Accessibility. Opening a URI still uses `app/deep-link`.

## Transports and authentication

Use `app/deep-links` and `app/deep-link/resolve` over local WebSocket, reverse
WebSocket, HTTP **POST** to the matching path, or an ADB ContentProvider query with
URL-encoded query parameters. They use the existing local API token, reverse
connection session, or ADB access; no additional credential is introduced.
They are available in local no-accessibility mode and the reverse headless subset.
They are not HTTP GET endpoints.

See [Local API](local-api.md) for token setup and [Reverse Connection](reverse-connection.md)
for connection configuration. Responses use the normal JSON `status` and
`result`/`error` envelope, with `id` on WebSocket responses.

## Discover manifest declarations

```json
{"id":1,"method":"app/deep-links","params":{"package":"com.example.app"}}
```

HTTP equivalent:

```bash
curl -X POST -H "Authorization: Bearer YOUR_TOKEN" \
  --data-urlencode "package=com.example.app" \
  http://localhost:8080/app/deep-links
```

ContentProvider equivalent:

```bash
adb shell "content query --uri 'content://com.mobilerun.portal/app/deep-links?package=com.example.app'"
```

`package` is required; `packageName` is accepted as an alias. The result includes:

```json
{
  "package":"com.example.app",
  "versionCode":42,
  "lastUpdateTime":1700000000000,
  "scope":"manifest",
  "declarationsComplete":true,
  "filters":[{
    "activity":"com.example.app.LinkActivity",
    "targetActivity":null,
    "enabled":true,
    "exported":true,
    "permission":null,
    "callable":true,
    "actions":["android.intent.action.VIEW"],
    "categories":["android.intent.category.DEFAULT","android.intent.category.BROWSABLE"],
    "autoVerify":true,
    "data":[{"scheme":"https","host":"example.com","pathPrefix":"/orders/"}],
    "source":"base"
  }],
  "warnings":[],
  "limitations":["runtime_routes_not_enumerated","dynamic_app_link_rules_not_evaluated"]
}
```

Portal reads `ACTION_VIEW` intent filters from the installed base and split APK
manifests, including activity aliases. Each filter retains its own actions,
categories and separate `<data>` elements. Android combines data elements within
a filter: an individual element is not necessarily a complete URL. Patterns such
as `pathPrefix`, `pathPattern`, and `pathAdvancedPattern` are matching rules, not a
list of concrete routes. Resource-valued attributes are resolved from the target
app when possible. Like Android, discovery omits a `<data>` attribute whose
resource value varies by configuration (for example a string with locale
variants) and reports `configuration_dependent_data_ignored`; a filter that loses
its `host` this way matches any host for its scheme. `autoVerify` records a
manifest request, not verified domain ownership or the user's preferred handler.

`enabled`, `exported`, `permission`, and `callable` describe current component
availability to Portal. Discovery's `callable` also requires `CATEGORY_DEFAULT`;
it does not mean every URI matches. Same-UID components can be callable without
being exported. `targetActivity` identifies the destination of an alias.

`source` is `base` or `split:N`. Unreadable manifests, unresolved resources,
missing component metadata, unsupported extra rules, or safety limits produce
warnings and `declarationsComplete:false`. Unresolved references remain visible as
`@0x...`; extra rules remain under `additionalRules` as raw trees. Do not interpret
a partial or unreadable result as proof that the app has no links. A successful
empty `filters` list means no `ACTION_VIEW` declaration was found in the manifests
that were read.

Inspection is bounded to 128 APKs, 4 MiB per manifest, 16 MiB of total manifest
reads, 1,000 filters, and 1 MiB of declaration text in `filters`. Complete
declarations are cached for up to 16 packages; package version/update/path or
resource configuration changes invalidate them.
Enabled state and permission checks are refreshed on each request.

## Resolve a candidate URI without launching

```json
{"id":2,"method":"app/deep-link/resolve","params":{"package":"com.example.app","deepLink":"https://example.com/orders/123"}}
```

HTTP equivalent:

```bash
curl -X POST -H "Authorization: Bearer YOUR_TOKEN" \
  --data-urlencode "package=com.example.app" \
  --data-urlencode "deepLink=https://example.com/orders/123" \
  http://localhost:8080/app/deep-link/resolve
```

ContentProvider equivalent:

```bash
adb shell "content query --uri 'content://com.mobilerun.portal/app/deep-link/resolve?package=com.example.app&deepLink=https%3A%2F%2Fexample.com%2Forders%2F123'"
```

`package` and an absolute `deepLink` URI are required. Optional `action` defaults
to `android.intent.action.VIEW`, matching `app/deep-link`. Portal asks Android's
resolver for default activities in that package and returns:

```json
{
  "package":"com.example.app",
  "scope":"android_intent_resolution",
  "canResolve":true,
  "navigationVerified":false,
  "matches":[{
    "activity":"com.example.app.LinkActivity",
    "enabled":true,
    "exported":true,
    "permission":null,
    "callable":true,
    "filter":{
      "actions":["android.intent.action.VIEW"],
      "categories":["android.intent.category.DEFAULT","android.intent.category.BROWSABLE"],
      "schemes":["https"],
      "mimeTypes":[],
      "authorities":[{"host":"example.com","port":null}],
      "paths":[{"type":"prefix","value":"/orders/"}],
      "schemeSpecificParts":[]
    }
  }],
  "warnings":[]
}
```

`canResolve` is true if Android returns at least one currently callable activity.
Android does not let a `file://` URI outside `/system/` leave Portal
(`FileUriExposedException`), so such a URI reports `callable:false` on every
match, `canResolve:false`, and the `file_uri_exposed` warning; `app/deep-link`
rejects it. Use a `content://` URI instead. Resolution is a resolver check, not
proof that the app navigates to the intended page or accepts the URI after login.
An unmatched URI returns `canResolve:false` and `matches:[]`. A missing or package-visibility-restricted app returns an error:
`Package is not installed or not visible to Portal`. The API does not broaden
Android package visibility permissions. URI inputs and resolver responses are
redacted in reverse connection logs; the response does not echo the input URI.

## Limits of discovery

Apps can implement additional routes in their own code or fetch them from a
server. Those routes cannot be enumerated from Android's manifest. Server-side
dynamic App Link rules are not evaluated by the discovery endpoint. Use the
target app's official documentation and test known candidate URIs with the
resolver endpoint. This API does not brute-force routes, bypass app permissions,
or guarantee navigation. It needs neither root nor an Accessibility service.
