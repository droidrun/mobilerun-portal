package com.mobilerun.portal.service

import org.junit.Assert.assertEquals
import org.junit.Test

class TvAccessibilityHealthPolicyTest {
    @Test fun boundAloneIsNeverReadyAndUnboundIsNeededEvenWithOldUsableEvidence() {
        val policy = TvAccessibilityHealthPolicy()
        assertEquals(TvAccessibilityHealthState.NEEDED, policy.update(false, true, 0))
        assertEquals(TvAccessibilityHealthState.RECONNECTING, policy.update(true, false, 0))
        assertEquals(TvAccessibilityHealthState.READY, policy.update(true, true, 1))
    }

    @Test fun repeatedBlindProbesDoNotRestartTheTenSecondDeadline() {
        val policy = TvAccessibilityHealthPolicy()
        assertEquals(TvAccessibilityHealthState.RECONNECTING, policy.update(true, false, 1_000))
        assertEquals(TvAccessibilityHealthState.RECONNECTING, policy.update(true, false, 8_000))
        assertEquals(3_000L, policy.remainingMs(8_000))
        assertEquals(TvAccessibilityHealthState.RECONNECTING, policy.update(true, false, 10_999))
        assertEquals(TvAccessibilityHealthState.NOT_RESPONDING, policy.update(true, false, 11_000))
        assertEquals(0L, policy.remainingMs(11_000))
    }

    @Test fun healthyProbeClearsFailureAndStartsANewDeadlineForANewOutage() {
        val policy = TvAccessibilityHealthPolicy()
        policy.update(true, false, 0)
        assertEquals(TvAccessibilityHealthState.NOT_RESPONDING, policy.update(true, false, 10_000))
        assertEquals(TvAccessibilityHealthState.READY, policy.update(true, true, 10_001))
        assertEquals(TvAccessibilityHealthState.RECONNECTING, policy.update(true, false, 20_000))
        assertEquals(10_000L, policy.remainingMs(20_000))
    }

    @Test fun unbindResetsTheOutageBeforeARebind() {
        val policy = TvAccessibilityHealthPolicy()
        policy.update(true, false, 0)
        assertEquals(TvAccessibilityHealthState.NEEDED, policy.update(false, false, 20_000))
        assertEquals(TvAccessibilityHealthState.RECONNECTING, policy.update(true, false, 20_001))
    }
}
