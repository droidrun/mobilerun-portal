package com.mobilerun.portal.deeplinks

import org.junit.Assert.*
import org.junit.Test

class DeepLinkManifestTest {
    @Test
    fun unrelatedStyleReferencesDoNotMakeLinkDeclarationsIncomplete() {
        val filter = ManifestElement("intent-filter", emptyList(), listOf(
            ManifestElement("action", listOf(ManifestAttribute(ANDROID_NAMESPACE, "name", "android.intent.action.VIEW")), emptyList()),
            ManifestElement("data", listOf(ManifestAttribute(ANDROID_NAMESPACE, "scheme", "example")), emptyList()),
        ))
        val component = ManifestElement("activity", listOf(
            ManifestAttribute(ANDROID_NAMESPACE, "name", ".Link"),
            ManifestAttribute(ANDROID_NAMESPACE, "theme", "@0x7f020000", 0x7f020000),
        ), listOf(filter))
        val root = ManifestElement("manifest", emptyList(), listOf(
            ManifestElement("application", emptyList(), listOf(component)),
        ))
        val result = DeepLinkManifest.extract(root, "example.app") { error("Unrelated resources must not be read") }
        assertTrue(result.warnings.isEmpty())
        assertEquals("example", result.filters.single().data.single()["scheme"])
    }

    @Test
    fun compiledManifestPreservesFiltersAndResolvesTargetResources() {
        val root = BinaryAndroidManifestReader.read(javaClass.getResourceAsStream("/deeplinks/manifest-aapt2.axml")!!.use { it.readBytes() })
        val result = DeepLinkManifest.extract(root, "com.mobilerun.fixture.links") { id ->
            when (id) { 0x7f010001 -> "resource-scheme"; 0x7f010000 -> "resource.example"; else -> null }
                ?.let(::ManifestResourceValue)
        }
        assertTrue(result.warnings.isEmpty())
        val activity = result.filters.single { it.activity.endsWith(".LinkActivity") }
        assertTrue(activity.autoVerify)
        assertEquals("fixture", activity.data[0]["scheme"])
        assertEquals("/orders/", activity.data[0]["pathPrefix"])
        assertEquals("例え.example", activity.data[1]["host"])
        assertEquals("resource-scheme", activity.data[2]["scheme"])
        assertEquals("resource.example", activity.data[2]["host"])
        assertTrue(result.filters.any { it.activity.endsWith("AliasActivity") || it.activity.contains("Alias") })
    }

    @Test
    fun unresolvedResourcesAreFlaggedAndNeverInvented() {
        val root = BinaryAndroidManifestReader.read(javaClass.getResourceAsStream("/deeplinks/manifest-aapt2.axml")!!.use { it.readBytes() })
        val result = DeepLinkManifest.extract(root, "com.mobilerun.fixture.links") { null }
        assertEquals(listOf("unresolved_manifest_resource"), result.warnings)
        assertTrue(result.filters.flatMap { it.data }.any { it["scheme"] == "@0x7f010001" })
    }

    @Test
    fun configurationDependentDataAttributesAreDroppedLikeAndroid() {
        val root = BinaryAndroidManifestReader.read(javaClass.getResourceAsStream("/deeplinks/manifest-aapt2.axml")!!.use { it.readBytes() })
        val result = DeepLinkManifest.extract(root, "com.mobilerun.fixture.links") { id ->
            when (id) {
                0x7f010001 -> ManifestResourceValue("resource-scheme")
                0x7f010000 -> ManifestResourceValue("localized.example", configurationDependent = true)
                else -> null
            }
        }
        assertEquals(listOf("configuration_dependent_data_ignored"), result.warnings)
        val data = result.filters.single { it.activity.endsWith(".LinkActivity") }.data[2]
        assertEquals(mapOf("scheme" to "resource-scheme"), data)
    }

    @Test
    fun outputSizeCountsEveryEmittedString() {
        val rule = ManifestElement("uri-relative-filter-group", listOf(ManifestAttribute(ANDROID_NAMESPACE, "allow", "true")), emptyList())
        val filter = DeclaredDeepLinkFilter("a.B", listOf("view"), listOf("cat"), false, listOf(mapOf("host" to "h.example")), listOf(rule))
        assertEquals(3L + 4 + 3 + 4 + 9 + 25 + ANDROID_NAMESPACE.length + 5 + 4, filter.outputSize())
    }

    @Test
    fun unrelatedActionsAreExcludedAndDataElementsStayWithinTheirFilter() {
        fun attr(name: String, value: String) = ManifestAttribute(ANDROID_NAMESPACE, name, value)
        fun element(name: String, attrs: List<ManifestAttribute> = emptyList(), children: List<ManifestElement> = emptyList()) =
            ManifestElement(name, attrs, children)
        fun action(value: String) = element("action", listOf(attr("name", value)))
        val firstFilter = element("intent-filter", children = listOf(
            action("android.intent.action.VIEW"),
            element("data", listOf(attr("scheme", "one"))),
            element("data", listOf(attr("host", "host"))),
        ))
        val secondFilter = element("intent-filter", children = listOf(
            action("android.intent.action.VIEW"),
            element("data", listOf(attr("scheme", "two"))),
        ))
        val unrelatedFilter = element("intent-filter", children = listOf(
            action("android.intent.action.SEND"),
            element("data", listOf(attr("mimeType", "text/plain"))),
        ))
        val activity = element("activity", listOf(attr("name", ".Link")),
            listOf(firstFilter, secondFilter, unrelatedFilter))
        val application = element("application", children = listOf(activity))
        val root = element("manifest", children = listOf(application))
        val result = DeepLinkManifest.extract(root, "example.app") { null }
        assertEquals(2, result.filters.size)
        assertEquals("example.app.Link", result.filters[0].activity)
        assertEquals(listOf(mapOf("scheme" to "one"), mapOf("host" to "host")), result.filters[0].data)
        assertEquals(listOf(mapOf("scheme" to "two")), result.filters[1].data)
    }
}
