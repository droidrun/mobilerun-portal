package com.mobilerun.portal.streaming

import org.junit.Assert.*
import org.junit.Test

class TvViewerLeaseTest {
    @Test
    fun admissionArmsNegotiationAllowanceWithoutAnyViewerSignal() {
        val lease = WebRtcManager.TvViewerLease(1_000L)
        assertEquals(31_000L, lease.deadlineMs)
        assertEquals(0L, lease.renewals)
        assertFalse(WebRtcManager.TvViewerLease.shouldExpire(lease, lease, 30_999L))
        assertTrue(WebRtcManager.TvViewerLease.shouldExpire(lease, lease, 31_000L))
    }

    @Test
    fun renewalMovesDeadlineAndRecordsViewerCadence() {
        val lease = WebRtcManager.TvViewerLease(0L)
        assertNull(lease.renew(10_000L))
        assertEquals(5_000L, lease.renew(15_000L))
        assertEquals(7_000L, lease.renew(22_000L))
        assertEquals(37_000L, lease.deadlineMs)
        assertEquals(3L, lease.renewals)
        assertEquals(6_000L, lease.medianHeartbeatIntervalMs())
    }

    @Test
    fun diagnosticsKeepHeartbeatCadenceSeparateFromRequestFrameBursts() {
        val lease = WebRtcManager.TvViewerLease(0L)
        lease.renew(1_000L)
        lease.renew(5_000L, requestFrame = true)
        assertNull(lease.sameSignalIntervalMs)
        assertEquals(100L, lease.renew(5_100L, requestFrame = true))
        assertEquals(100L, lease.sameSignalIntervalMs)
        assertEquals(900L, lease.renew(6_000L))
        assertEquals(5_000L, lease.sameSignalIntervalMs)
    }

    @Test
    fun queuedExpiryCannotStopRenewedLease() {
        val lease = WebRtcManager.TvViewerLease(0L)
        val oldDeadline = lease.deadlineMs
        lease.renew(oldDeadline - 1L)
        assertFalse(WebRtcManager.TvViewerLease.shouldExpire(lease, lease, oldDeadline))
        assertTrue(WebRtcManager.TvViewerLease.shouldExpire(lease, lease, lease.deadlineMs))
    }

    @Test
    fun expiredWorkCannotStopReplacementWithSameSessionIdOrRemovedSession() {
        val oldLease = WebRtcManager.TvViewerLease(0L)
        val replacement = WebRtcManager.TvViewerLease(1_000L)
        assertFalse(WebRtcManager.TvViewerLease.shouldExpire(replacement, oldLease, 100_000L))
        assertFalse(WebRtcManager.TvViewerLease.shouldExpire(null, oldLease, 100_000L))
        assertTrue(WebRtcManager.TvViewerLease.shouldExpire(replacement, replacement, 100_000L))
    }

    @Test
    fun cadenceWindowRemainsBoundedForLongSessions() {
        val lease = WebRtcManager.TvViewerLease(0L)
        lease.renew(0L)
        repeat(300) { lease.renew((it + 1) * 100L) }
        assertEquals(100L, lease.medianHeartbeatIntervalMs())
        repeat(256) { lease.renew(30_000L + (it + 1) * 200L) }
        assertEquals(200L, lease.medianHeartbeatIntervalMs())
        assertEquals(557L, lease.renewals)
    }
    @Test
    fun increasingInboundBytesRenewsEvenWithoutAppSignals() {
        val lease = WebRtcManager.TvViewerLease(0L)
        fun sample(bytes: Long) = WebRtcManager.TvTransportSnapshot(bytesReceived = mapOf("transport" to bytes))
        assertFalse(lease.observeTransport(sample(100L), 5_000L))
        assertTrue(lease.observeTransport(sample(200L), 10_000L))
        assertEquals(30_000L, lease.deadlineMs) // Retain initial negotiation allowance.
        for (time in 15_000L..60_000L step 5_000L) {
            assertTrue(lease.observeTransport(sample(time), time))
        }
        assertEquals(75_000L, lease.deadlineMs)
        assertFalse(WebRtcManager.TvViewerLease.shouldExpire(lease, lease, 60_000L))
    }

    @Test
    fun flatInboundTrafficExpiresFifteenSecondsAfterLastProgress() {
        val lease = WebRtcManager.TvViewerLease(0L)
        val first = WebRtcManager.TvTransportSnapshot(bytesReceived = mapOf("transport" to 100L))
        val next = WebRtcManager.TvTransportSnapshot(bytesReceived = mapOf("transport" to 200L))
        lease.observeTransport(first, 25_000L)
        assertTrue(lease.observeTransport(next, 30_000L))
        assertFalse(lease.observeTransport(next, 35_000L))
        assertFalse(lease.observeTransport(next, 40_000L))
        assertFalse(WebRtcManager.TvViewerLease.shouldExpire(lease, lease, 44_999L))
        assertTrue(WebRtcManager.TvViewerLease.shouldExpire(lease, lease, 45_000L))
    }

    @Test
    fun staleStatsCannotRenewReplacementOrOverrideNewerSamples() {
        val old = WebRtcManager.TvViewerLease(0L)
        val replacement = WebRtcManager.TvViewerLease(1_000L)
        val sample = WebRtcManager.TvTransportSnapshot(bytesReceived = mapOf("transport" to 100L))
        assertFalse(WebRtcManager.TvViewerLease.observeTransportIfCurrent(replacement, old, sample, 40_000L))
        assertEquals(0L, replacement.renewals)
        assertEquals(0L, old.renewals)
        replacement.observeTransport(sample, 10_000L)
        assertFalse(replacement.observeTransport(sample.copy(bytesReceived = mapOf("transport" to 200L)), 5_000L))
        assertEquals(0L, replacement.renewals)
    }

    @Test
    fun rtcpReportsAndConsentResponsesAreIndependentInboundEvidence() {
        val lease = WebRtcManager.TvViewerLease(0L)
        lease.observeTransport(WebRtcManager.TvTransportSnapshot(rtcpReports = mapOf("rtcp" to 1L), consentResponses = mapOf("pair" to 10L)), 5_000L)
        assertTrue(lease.observeTransport(WebRtcManager.TvTransportSnapshot(rtcpReports = mapOf("rtcp" to 2L), consentResponses = mapOf("pair" to 10L)), 10_000L))
        assertEquals(10_000L, lease.lastRtcpAtMs)
        assertTrue(lease.observeTransport(WebRtcManager.TvTransportSnapshot(rtcpReports = mapOf("rtcp" to 2L), consentResponses = mapOf("pair" to 11L)), 15_000L))
        assertEquals(2L, lease.renewals)
    }

    @Test
    fun resetOrMissingCountersDoNotRenewUntilTheyAdvanceAgain() {
        val lease = WebRtcManager.TvViewerLease(0L)
        fun sample(id: String, bytes: Long) = WebRtcManager.TvTransportSnapshot(bytesReceived = mapOf(id to bytes))
        lease.observeTransport(sample("transport", 100L), 5_000L)
        assertFalse(lease.observeTransport(sample("transport", 50L), 10_000L))
        assertTrue(lease.observeTransport(sample("transport", 60L), 15_000L))
        assertFalse(lease.observeTransport(sample("replacement", 1_000L), 20_000L))
        assertFalse(lease.observeTransport(WebRtcManager.TvTransportSnapshot(), 25_000L))
    }

    @Test
    fun snapshotUsesInboundTransportNumbersAndIgnoresSampleTimestampsAndOutboundTraffic() {
        fun stat(type: String, id: String, members: Map<String, Any>) = org.webrtc.RTCStats(123L, type, id, members)
        val stats = listOf(
            stat("transport", "transport", mapOf("bytesReceived" to java.math.BigInteger.valueOf(200L), "bytesSent" to 999L)),
            stat("candidate-pair", "pair", mapOf("state" to "succeeded", "nominated" to true, "bytesReceived" to 200L, "responsesReceived" to 3L)),
            stat("candidate-pair", "unused", mapOf("state" to "succeeded", "nominated" to false, "bytesReceived" to 999L)),
            stat("remote-inbound-rtp", "rtcp", mapOf("roundTripTimeMeasurements" to 2L)),
            stat("outbound-rtp", "outbound", mapOf("bytesSent" to 999L)),
        ).associateBy { it.id }
        val snapshot = WebRtcManager.TvTransportSnapshot.fromReport(org.webrtc.RTCStatsReport(500L, stats))
        assertEquals(mapOf("transport" to 200L), snapshot.bytesReceived)
        assertEquals(mapOf("pair" to 3L), snapshot.consentResponses)
        assertEquals(mapOf("rtcp" to 2L), snapshot.rtcpReports)
        val fallback = WebRtcManager.TvTransportSnapshot.fromReport(org.webrtc.RTCStatsReport(999L, stats - "transport"))
        assertEquals(mapOf("pair" to 200L), fallback.bytesReceived)
    }

}
