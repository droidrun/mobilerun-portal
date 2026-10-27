package com.mobilerun.portal.deeplinks

internal const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

internal data class DeclaredDeepLinkFilter(
    val activity: String,
    val actions: List<String>,
    val categories: List<String>,
    val autoVerify: Boolean,
    // Keep each filter and its data elements together. Android combines the
    // elements within a filter; an individual data element is not a whole URL.
    val data: List<Map<String, String>>,
    val additionalRules: List<ManifestElement>,
)

internal data class DeepLinkManifestResult(
    val filters: List<DeclaredDeepLinkFilter>,
    val warnings: List<String>,
)

internal data class ManifestResourceValue(val value: String, val configurationDependent: Boolean = false)

/** Characters of declaration text a filter contributes to discovery output. */
internal fun DeclaredDeepLinkFilter.outputSize(): Long =
    activity.length.toLong() + actions.sumOf { it.length.toLong() } + categories.sumOf { it.length.toLong() } +
        data.sumOf { element -> element.entries.sumOf { (name, value) -> name.length.toLong() + value.length } } +
        additionalRules.sumOf { it.outputSize() }

private fun ManifestElement.outputSize(): Long = name.length.toLong() +
    attributes.sumOf { (it.namespaceUri?.length ?: 0).toLong() + it.name.length + it.value.length } +
    children.sumOf { it.outputSize() }

internal object DeepLinkManifest {
    fun extract(
        manifest: ManifestElement,
        packageName: String,
        resolveResource: (Int) -> ManifestResourceValue?,
    ): DeepLinkManifestResult {
        require(manifest.name == "manifest") { "Not an Android manifest" }
        val warnings = linkedSetOf<String>()
        fun attributes(
            element: ManifestElement,
            names: Set<String>? = null,
            nonConfiguration: Boolean = false,
        ): Map<String, String> = element.attributes.filter {
            it.namespaceUri == ANDROID_NAMESPACE && (names == null || it.name in names)
        }.mapNotNull { attribute ->
            val resolved = attribute.resourceId?.let(resolveResource)
            if (attribute.resourceId != null && resolved == null) {
                warnings += "unresolved_manifest_resource"
            }
            // Android ignores <data> attributes whose resource value varies by configuration.
            if (nonConfiguration && resolved?.configurationDependent == true) {
                warnings += "configuration_dependent_data_ignored"
                return@mapNotNull null
            }
            attribute.name to (resolved?.value ?: attribute.value)
        }.toMap()

        val filters = mutableListOf<DeclaredDeepLinkFilter>()
        for (application in manifest.children.filter { it.name == "application" }) {
            for (component in application.children.filter {
                it.name == "activity" || it.name == "activity-alias"
            }) {
                val name = attributes(component, setOf("name"))["name"] ?: continue
                val activity = when {
                    name.startsWith('.') -> packageName + name
                    '.' !in name -> "$packageName.$name"
                    else -> name
                }
                for (filter in component.children.filter { it.name == "intent-filter" }) {
                    val actions = filter.children.filter { it.name == "action" }
                        .mapNotNull { attributes(it, setOf("name"))["name"] }.distinct()
                    if ("android.intent.action.VIEW" !in actions) continue
                    val categories = filter.children.filter { it.name == "category" }
                        .mapNotNull { attributes(it, setOf("name"))["name"] }.distinct()
                    val extra = filter.children.filter {
                        it.name !in setOf("action", "category", "data")
                    }
                    if (extra.isNotEmpty()) warnings += "additional_filter_rules_preserved"
                    filters += DeclaredDeepLinkFilter(
                        activity,
                        actions,
                        categories,
                        attributes(filter, setOf("autoVerify"))["autoVerify"] in setOf("true", "1"),
                        filter.children.filter { it.name == "data" }.map { attributes(it, nonConfiguration = true) },
                        extra,
                    )
                }
            }
        }
        return DeepLinkManifestResult(filters, warnings.toList())
    }
}
