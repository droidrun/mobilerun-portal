package com.mobilerun.portal.service

import android.content.pm.PackageManager
import com.mobilerun.portal.sms.hasTelephonyFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalCapabilityTokensTest {
    @Test
    fun headers_withTelephonyAddSmsTokens() {
        assertEquals(
            mapOf(
                "X-Device-Capabilities" to
                    "apps,clipboard,files,location-read,sms,sms-events,sms-send,timezone-read",
                "X-Device-Platform" to "android",
            ),
            PortalCapabilityTokens.headers(hasTelephony = true),
        )
    }

    @Test
    fun headers_withoutTelephonyAnnounceOnlyServedCapabilities() {
        assertEquals(
            mapOf(
                "X-Device-Capabilities" to "apps,clipboard,files,location-read,timezone-read",
                "X-Device-Platform" to "android",
            ),
            PortalCapabilityTokens.headers(hasTelephony = false),
        )
    }

    @Test
    fun tokens_neverAnnounceGatedCapabilities() {
        for (telephony in listOf(true, false)) {
            val tokens = PortalCapabilityTokens.tokens(telephony)
            assertEquals(tokens.sorted(), tokens)
            for (forbidden in listOf(
                "reset", "recording", "recording-trajectory", "app-uninstall", "media",
                "location", "proxy", "timezone",
                "screen-control", "sms-services", "sms-kinds", "sms-junk",
            )) {
                assertFalse(forbidden in tokens)
            }
        }
    }

    @Test
    fun telephony_api33UsesTelephonyMessaging() {
        assertTrue(
            hasTelephonyFeature(33) { it == PackageManager.FEATURE_TELEPHONY_MESSAGING },
        )
        assertFalse(hasTelephonyFeature(33) { it == PackageManager.FEATURE_TELEPHONY })
    }

    @Test
    fun telephony_belowApi33UsesTelephony() {
        assertTrue(hasTelephonyFeature(30) { it == PackageManager.FEATURE_TELEPHONY })
        assertFalse(
            hasTelephonyFeature(30) { it == PackageManager.FEATURE_TELEPHONY_MESSAGING },
        )
    }

    @Test
    fun telephony_absentWithoutFeatures() {
        assertFalse(hasTelephonyFeature(34) { false })
        assertFalse(hasTelephonyFeature(26) { false })
    }
}
