package com.mobilerun.portal.streaming

import org.junit.Assert.*
import org.junit.Test
import org.webrtc.RtpCapabilities

class TvEncoderNegotiationTest {
    @Test
    fun fallbackSenderFitsCaptureInto360pWithoutUpscaling() {
        for ((width, height, expectedScale) in listOf(
            Triple(1280, 720, 2.0), Triple(1920, 1080, 3.0),
            Triple(640, 360, 1.0), Triple(320, 180, 1.0),
            Triple(720, 1280, 1280.0 / 360), Triple(1024, 768, 768.0 / 360),
        )) {
            val limits = WebRtcManager.tvFallbackSenderLimits(width, height, 30)
            assertEquals(expectedScale, limits.scaleDownBy, 0.000001)
            assertTrue(width / limits.scaleDownBy <= 640.000001)
            assertTrue(height / limits.scaleDownBy <= 360.000001)
            assertEquals(15, limits.maxFps)
            assertEquals(800_000, limits.maxBitrateBps)
        }
    }

    @Test
    fun fallbackSenderBoundsInvalidSizesAndFrameRates() {
        for (size in listOf(0, -1, Int.MIN_VALUE)) {
            assertEquals(1.0, WebRtcManager.tvFallbackSenderLimits(size, size, 0).scaleDownBy, 0.0)
        }
        for ((fps, expected) in listOf(-1 to 1, 0 to 1, 1 to 1, 10 to 10, 15 to 15, Int.MAX_VALUE to 15)) {
            assertEquals(expected, WebRtcManager.tvFallbackSenderLimits(1280, 720, fps).maxFps)
        }
        val largest = WebRtcManager.tvFallbackSenderLimits(Int.MAX_VALUE, Int.MAX_VALUE, 30)
        assertTrue(largest.scaleDownBy.isFinite())
        assertTrue(Int.MAX_VALUE / largest.scaleDownBy <= 360.000001)
    }

    @Test
    fun onlySuccessfulProbeWithoutCooldownSelectsH264() {
        for (result in TvEncoderProbe.Result.values()) {
            assertEquals(if (result == TvEncoderProbe.Result.OK) setOf("H264") else setOf("VP8"),
                WebRtcManager.tvNegotiationCodecs(result))
            assertEquals(setOf("VP8"), WebRtcManager.tvNegotiationCodecs(result, previousProbeStuck = true))
        }
    }

    @Test
    fun h264StallCooldownForcesVp8ForFiveMinutesThenAllowsHardwareAgain() {
        assertFalse(WebRtcManager.tvEncoderCooldownActive(null, 1000L))
        val stopped = 1000L
        for (now in listOf(stopped, stopped + 299_999L)) {
            assertTrue(WebRtcManager.tvEncoderCooldownActive(stopped, now))
            assertEquals(setOf("VP8"), WebRtcManager.tvNegotiationCodecs(
                TvEncoderProbe.Result.OK, WebRtcManager.tvEncoderCooldownActive(stopped, now),
            ))
        }
        assertFalse(WebRtcManager.tvEncoderCooldownActive(stopped, stopped + 300_000L))
        assertEquals(setOf("H264"), WebRtcManager.tvNegotiationCodecs(
            TvEncoderProbe.Result.OK, WebRtcManager.tvEncoderCooldownActive(stopped, stopped + 300_000L),
        ))
    }

    @Test
    fun concurrentProbeJoinsUntilDeadlineThenIsStuck() {
        assertEquals(WebRtcManager.Companion.TvProbeAction.START, WebRtcManager.tvProbeAction(null, 5000L))
        assertEquals(WebRtcManager.Companion.TvProbeAction.JOIN, WebRtcManager.tvProbeAction(5000L, 5000L))
        assertEquals(WebRtcManager.Companion.TvProbeAction.JOIN, WebRtcManager.tvProbeAction(5000L, 6499L))
        assertEquals(WebRtcManager.Companion.TvProbeAction.STUCK, WebRtcManager.tvProbeAction(5000L, 6500L))
        assertEquals(WebRtcManager.Companion.TvProbeAction.STUCK, WebRtcManager.tvProbeAction(5000L, 9000L))
    }

    private fun sample(captured: Long, sent: Long?, encoded: Long? = sent) =
        WebRtcManager.Companion.EncoderStallSample(captured, sent, encoded)

    @Test
    fun staticCaptureNeverCountsAsEncoderStallEvenBeforeFirstSentFrame() {
        val still = sample(10, 0)
        assertEquals(0, WebRtcManager.nextTvEncoderStallCount(0, true, null, still))
        assertEquals(0, WebRtcManager.nextTvEncoderStallCount(0, true, still, still))
        assertEquals(0, WebRtcManager.nextTvEncoderStallCount(1, true, still, still))
    }

    @Test
    fun captureAdvancingWhileEncoderIsFlatStopsAfterTwoIntervals() {
        val first = WebRtcManager.nextTvEncoderStallCount(0, true, sample(10, 0), sample(20, 0))
        assertEquals(1, first)
        assertFalse(WebRtcManager.shouldWarnForZeroSentStats(first))
        val second = WebRtcManager.nextTvEncoderStallCount(first, true, sample(20, 0), sample(30, 0))
        assertTrue(WebRtcManager.shouldWarnForZeroSentStats(second))
    }

    @Test
    fun midSessionEncoderLossIsDetectedAfterPreviouslySuccessfulFrames() {
        val first = WebRtcManager.nextTvEncoderStallCount(0, true, sample(100, 80), sample(110, 80))
        val second = WebRtcManager.nextTvEncoderStallCount(first, true, sample(110, 80), sample(120, 80))
        assertEquals(2, second)
        assertTrue(WebRtcManager.shouldWarnForZeroSentStats(second))
    }

    @Test
    fun encodingOrSendingProgressAndUnavailableStatsResetTheStallCount() {
        assertEquals(0, WebRtcManager.nextTvEncoderStallCount(1, true, sample(10, 5, 7), sample(20, 5, 8)))
        assertEquals(0, WebRtcManager.nextTvEncoderStallCount(1, true, sample(10, 5, 7), sample(20, 6, 7)))
        assertEquals(0, WebRtcManager.nextTvEncoderStallCount(1, true, sample(10, null), sample(20, null)))
        assertEquals(0, WebRtcManager.nextTvEncoderStallCount(1, false, sample(10, 5), sample(20, 5)))
        assertEquals(0, WebRtcManager.nextTvEncoderStallCount(1, true, sample(10, 5), sample(20, 0)))
        assertEquals(1, WebRtcManager.nextTvEncoderStallCount(0, true, sample(10, null, 7), sample(20, null, 7)))
    }

    @Test
    fun receiverPreferencesKeepAuxiliaryCodecsForEitherVideoMode() {
        val names = listOf("H264", "VP8", "VP9", "rtx", "red", "ulpfec", "flexfec-03", "AV1")
        val available = names.map { name -> RtpCapabilities.CodecCapability().apply { this.name = name } }
        for (mode in listOf(setOf("H264"), setOf("VP8"))) {
            val selected = WebRtcManager.tvCodecPreferences(available, mode)
            assertEquals(listOf(mode.single(), "rtx", "red", "ulpfec", "flexfec-03"), selected.map { it.name })
            assertTrue(selected.all { it in available })
        }
    }
}
