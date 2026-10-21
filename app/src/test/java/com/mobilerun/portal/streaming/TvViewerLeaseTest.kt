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
        assertEquals(42_000L, lease.deadlineMs)
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
}
