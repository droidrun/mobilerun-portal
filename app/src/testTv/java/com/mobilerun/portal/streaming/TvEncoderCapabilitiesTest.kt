package com.mobilerun.portal.streaming

import org.junit.Assert.*
import org.junit.Test

class TvEncoderCapabilitiesTest {
    private fun encoder(
        name: String = "fake.avc.encoder",
        hardware: Boolean = true,
        supports: (Int, Int, Int) -> Boolean = { _, _, _ -> true },
        performanceCovers: ((Int, Int, Int) -> Boolean)? = null,
    ) = TvEncoderCapabilities.Encoder(name, hardware, supports, performanceCovers)

    @Test
    fun hardwareAvcWith720p30SupportUses720pAndH264Only() {
        val selected = TvEncoderCapabilities.select(listOf(encoder(supports = { w, h, fps -> w == 1280 && h == 720 && fps == 30 })))
        assertEquals(1280 to 720, selected.box)
        assertEquals("capability", selected.reason)
        assertNotNull(selected.encoder)
    }

    @Test
    fun softwareAvcOrNoEncoderKeepsVp8AndLegacyAutoBox() {
        for (codecs in listOf(emptyList(), listOf(encoder(hardware = false)))) {
            val selected = TvEncoderCapabilities.select(codecs)
            assertNull(selected.encoder)
            assertNull(selected.encoder)
            assertEquals(720 to 1280, selected.box)
            assertEquals("fallback", selected.reason)
        }
    }

    @Test
    fun hardwareSelectionSkipsSoftwareButDoesNotSkipWebRtcFirstHardwareChoice() {
        val selected = TvEncoderCapabilities.select(listOf(
            encoder(name = "software", hardware = false),
            encoder(name = "first.hardware", supports = { w, h, fps -> w == 960 && h == 540 && fps == 30 }),
            encoder(name = "second.faster.hardware"),
        ))
        assertEquals("first.hardware", selected.encoder?.name)
        assertEquals(960 to 540, selected.box)
    }

    @Test
    fun advertisedPerformancePointsMustCoverTheSelectedThirtyFpsSize() {
        val selected = TvEncoderCapabilities.select(listOf(encoder(performanceCovers = { w, h, fps -> w <= 960 && h <= 540 && fps == 30 })))
        assertEquals(960 to 540, selected.box)
        val tooSlow = TvEncoderCapabilities.select(listOf(encoder(performanceCovers = { _, _, _ -> false })))
        assertEquals(720 to 1280, tooSlow.box)
        assertNotNull(tooSlow.encoder) // AVC exists, but higher-resolution real-time capability is unproven.
    }

    @Test
    fun absentPerformancePointsAllowTheSizeAndRateCapabilityResult() {
        assertEquals(1280 to 720, TvEncoderCapabilities.select(listOf(encoder(performanceCovers = null))).box)
    }

    @Test
    fun unsupportedOrThrowingCapabilityQueryFallsBackWithoutDisablingHardwareAvc() {
        for (codec in listOf(encoder(supports = { _, _, _ -> false }), encoder(supports = { _, _, _ -> error("unavailable") }))) {
            val selected = TvEncoderCapabilities.select(listOf(codec))
            assertEquals(720 to 1280, selected.box)
            assertEquals("fallback", selected.reason)
            assertNotNull(selected.encoder)
        }
    }

    @Test
    fun optionalBuildBoxCanOnlyReduceCapabilitySelection() {
        assertEquals(960 to 540, TvEncoderCapabilities.select(listOf(encoder()), 960 to 540).box)
        assertEquals(1280 to 720, TvEncoderCapabilities.select(listOf(encoder()), 1920 to 1080).box)
        assertEquals(720 to 720, TvEncoderCapabilities.select(emptyList(), 1280 to 720).box)
        assertEquals(720 to 1280, TvEncoderCapabilities.select(emptyList()).box)
    }
}
