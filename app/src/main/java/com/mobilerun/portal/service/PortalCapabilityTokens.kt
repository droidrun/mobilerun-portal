package com.mobilerun.portal.service

/** Relay handshake tokens. The base set maps to the same devices-api capabilities as no header. */
internal object PortalCapabilityTokens {
    const val CAPABILITIES_HEADER = "X-Device-Capabilities"
    const val PLATFORM_HEADER = "X-Device-Platform"
    const val PLATFORM = "android"

    private val BASE = listOf("apps", "clipboard", "files", "location", "proxy", "timezone")
    private val SMS = listOf("sms", "sms-events", "sms-send")

    fun tokens(hasTelephony: Boolean): List<String> =
        (if (hasTelephony) BASE + SMS else BASE).sorted()

    fun headers(hasTelephony: Boolean): Map<String, String> = mapOf(
        CAPABILITIES_HEADER to tokens(hasTelephony).joinToString(","),
        PLATFORM_HEADER to PLATFORM,
    )
}
