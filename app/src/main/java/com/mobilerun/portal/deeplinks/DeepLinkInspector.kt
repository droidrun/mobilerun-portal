package com.mobilerun.portal.deeplinks

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PatternMatcher
import android.os.Process
import android.util.TypedValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipFile

/** Read-only package metadata. Never starts an activity or requires Accessibility. */
class DeepLinkInspector(
    private val context: Context,
    private val packageManager: PackageManager = context.packageManager,
) {
    private data class SourceFilter(val filter: DeclaredDeepLinkFilter, val source: String)
    private data class Declarations(val filters: List<SourceFilter>, val warnings: List<String>)
    private data class Cached(val fingerprint: String, val declarations: Declarations)
    private val cache = object : LinkedHashMap<String, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 16
    }

    fun discover(packageName: String): JSONObject {
        val info = packageInfo(packageName)
        val app = info.applicationInfo ?: throw IllegalArgumentException("Package metadata unavailable")
        val paths = listOfNotNull(app.publicSourceDir ?: app.sourceDir) +
            (app.splitPublicSourceDirs ?: app.splitSourceDirs ?: emptyArray()).toList()
        val fingerprint = "${versionCode(info)}:${info.lastUpdateTime}:" +
            "${context.resources.configuration.hashCode()}:${paths.joinToString("|")}"
        val declarations = synchronized(cache) { cache[packageName]?.takeIf { it.fingerprint == fingerprint } }
            ?.declarations ?: readDeclarations(paths, packageName).also {
                // Retry partial/unreadable results rather than caching a transient failure.
                if (it.warnings.isEmpty()) synchronized(cache) { cache[packageName] = Cached(fingerprint, it) }
            }
        val activities = info.activities.orEmpty().associateBy { it.name }
        val filters = JSONArray()
        val warnings = declarations.warnings.toMutableSet()
        for ((filter, source) in declarations.filters) {
            val activity = activities[filter.activity]
            if (activity == null) warnings += "component_metadata_unavailable"
            val entry = componentJson(activity, filter.activity)
            entry.put("targetActivity", activity?.targetActivity ?: JSONObject.NULL)
            entry.put("callable", entry.getBoolean("callable") && Intent.CATEGORY_DEFAULT in filter.categories)
            entry.put("actions", JSONArray(filter.actions))
            entry.put("categories", JSONArray(filter.categories))
            entry.put("autoVerify", filter.autoVerify)
            entry.put("data", JSONArray(filter.data.map { JSONObject(it) }))
            entry.put("source", source)
            if (filter.additionalRules.isNotEmpty()) {
                entry.put("additionalRules", JSONArray(filter.additionalRules.map(::elementJson)))
            }
            filters.put(entry)
        }
        return JSONObject().apply {
            put("package", packageName)
            put("versionCode", versionCode(info))
            put("lastUpdateTime", info.lastUpdateTime)
            put("scope", "manifest")
            put("declarationsComplete", warnings.isEmpty())
            put("filters", filters)
            put("warnings", JSONArray(warnings.toList()))
            put("limitations", JSONArray(listOf("runtime_routes_not_enumerated", "dynamic_app_link_rules_not_evaluated")))
        }
    }

    fun resolve(packageName: String, deepLink: String, action: String? = null): JSONObject {
        packageInfo(packageName) // Distinguish an inaccessible package from an unmatched URI.
        require(deepLink.length <= MAX_URI_LENGTH && deepLink.none { it.isISOControl() }) { "Invalid deepLink" }
        val uri = Uri.parse(deepLink)
        require(uri.scheme?.matches(Regex("[A-Za-z][A-Za-z0-9+.-]*")) == true) {
            "deepLink must be an absolute URI with a scheme"
        }
        val intent = Intent(action?.takeIf { it.isNotBlank() } ?: Intent.ACTION_VIEW).apply {
            data = uri
            setPackage(packageName)
        }
        val flags = PackageManager.MATCH_DEFAULT_ONLY or PackageManager.GET_RESOLVED_FILTER
        val resolved = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, flags)
        }
        val matches = JSONArray()
        var callable = false
        for (result in resolved) {
            val activity = result.activityInfo ?: continue
            if (activity.packageName != packageName) continue
            val entry = componentJson(activity, activity.name)
            callable = callable || entry.getBoolean("callable")
            entry.put("filter", result.filter?.let(::filterJson) ?: JSONObject.NULL)
            matches.put(entry)
        }
        return JSONObject().apply {
            put("package", packageName)
            put("scope", "android_intent_resolution")
            put("canResolve", callable)
            put("navigationVerified", false)
            put("matches", matches)
        }
    }

    private fun packageInfo(packageName: String): PackageInfo {
        require(packageName.length in 1..255 && packageName.matches(PACKAGE_NAME)) { "Invalid package name" }
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.MATCH_DISABLED_COMPONENTS
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, flags)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            throw IllegalArgumentException("Package is not installed or not visible to Portal")
        }
    }

    private fun readDeclarations(paths: List<String>, packageName: String): Declarations {
        val filters = mutableListOf<SourceFilter>()
        val warnings = linkedSetOf<String>()
        val resources = runCatching { packageManager.getResourcesForApplication(packageName) }.getOrNull()
        var totalBytes = 0
        if (paths.isEmpty()) warnings += "manifest_paths_unavailable"
        if (paths.size > MAX_APKS) warnings += "manifest_limit_exceeded"
        for ((index, path) in paths.take(MAX_APKS).withIndex()) {
            if (totalBytes >= MAX_TOTAL_MANIFEST_BYTES) {
                warnings += "manifest_limit_exceeded"
                break
            }
            val source = if (index == 0) "base" else "split:$index"
            try {
                val bytes = ZipFile(path).use { zip ->
                    val entry = zip.getEntry("AndroidManifest.xml") ?: error("Missing manifest")
                    zip.getInputStream(entry).use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            // Failed/oversized entries still consume the aggregate read budget.
                            totalBytes += count
                            require(output.size() + count <= MAX_MANIFEST_BYTES &&
                                totalBytes <= MAX_TOTAL_MANIFEST_BYTES) { "Manifest too large" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                }
                val result = DeepLinkManifest.extract(BinaryAndroidManifestReader.read(bytes), packageName) { id ->
                    runCatching {
                        val value = TypedValue()
                        resources?.getValue(id, value, true)
                        if (resources == null) null else value.coerceToString()?.toString()
                    }.getOrNull()
                }
                warnings += result.warnings
                if (filters.size + result.filters.size > MAX_FILTERS) {
                    warnings += "filter_limit_exceeded"
                    filters += result.filters.take((MAX_FILTERS - filters.size).coerceAtLeast(0)).map { SourceFilter(it, source) }
                    break
                }
                filters += result.filters.map { SourceFilter(it, source) }
            } catch (_: Exception) {
                warnings += "manifest_unreadable:$source"
            }
        }
        return Declarations(filters.toList(), warnings.toList())
    }

    private fun componentJson(activity: ActivityInfo?, fallbackName: String): JSONObject {
        val enabled = activity?.let {
            val setting = runCatching {
                packageManager.getComponentEnabledSetting(ComponentName(it.packageName, it.name))
            }.getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
            val componentEnabled = when (setting) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> it.enabled
                else -> false
            }
            componentEnabled && it.applicationInfo?.enabled == true
        } ?: false
        val exported = activity?.exported == true
        val permission = activity?.permission
        val sameUid = activity?.applicationInfo?.uid == Process.myUid()
        val hasPermission = permission.isNullOrEmpty() || context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        return JSONObject().apply {
            put("activity", activity?.name ?: fallbackName)
            put("enabled", enabled)
            put("exported", exported)
            put("permission", permission ?: JSONObject.NULL)
            put("callable", enabled && (sameUid || (exported && hasPermission)))
        }
    }

    private fun filterJson(filter: IntentFilter): JSONObject = JSONObject().apply {
        put("actions", JSONArray((0 until filter.countActions()).map(filter::getAction)))
        put("categories", JSONArray((0 until filter.countCategories()).map(filter::getCategory)))
        put("schemes", JSONArray((0 until filter.countDataSchemes()).map(filter::getDataScheme)))
        put("mimeTypes", JSONArray((0 until filter.countDataTypes()).map(filter::getDataType)))
        put("authorities", JSONArray((0 until filter.countDataAuthorities()).map {
            val authority = filter.getDataAuthority(it)
            JSONObject().put("host", authority.host).put("port", if (authority.port < 0) JSONObject.NULL else authority.port)
        }))
        put("paths", JSONArray((0 until filter.countDataPaths()).map { patternJson(filter.getDataPath(it)) }))
        put("schemeSpecificParts", JSONArray((0 until filter.countDataSchemeSpecificParts()).map { patternJson(filter.getDataSchemeSpecificPart(it)) }))
    }

    private fun patternJson(pattern: PatternMatcher) = JSONObject().apply {
        put("type", when (pattern.type) {
            PatternMatcher.PATTERN_LITERAL -> "literal"
            PatternMatcher.PATTERN_PREFIX -> "prefix"
            PatternMatcher.PATTERN_SIMPLE_GLOB -> "simpleGlob"
            PatternMatcher.PATTERN_ADVANCED_GLOB -> "advancedGlob"
            PatternMatcher.PATTERN_SUFFIX -> "suffix"
            else -> "unknown"
        })
        put("value", pattern.path)
    }

    private fun elementJson(element: ManifestElement): JSONObject = JSONObject().apply {
        put("tag", element.name)
        put("attributes", JSONObject(element.attributes.associate { attribute ->
            (attribute.namespaceUri?.let { "{$it}${attribute.name}" } ?: attribute.name) to attribute.value
        }))
        put("children", JSONArray(element.children.map(::elementJson)))
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private companion object {
        val PACKAGE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
        const val MAX_URI_LENGTH = 8192
        const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
        const val MAX_TOTAL_MANIFEST_BYTES = 16 * 1024 * 1024
        const val MAX_APKS = 128
        const val MAX_FILTERS = 1000
    }
}
