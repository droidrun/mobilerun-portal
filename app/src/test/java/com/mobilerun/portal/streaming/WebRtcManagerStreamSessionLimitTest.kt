package com.mobilerun.portal.streaming

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRtcManagerStreamSessionLimitTest {
    @Test
    fun isStreamSessionLimitReached_countsPrimaryAndSecondaries() {
        assertFalse(limitReached(hasPrimary = true, secondaries = 1, limit = 3))
        assertTrue(limitReached(hasPrimary = true, secondaries = 2, limit = 3))
        assertFalse(limitReached(hasPrimary = false, secondaries = 2, limit = 3))
    }

    @Test
    fun isStreamSessionLimitReached_limitOfOneRejectsAnySecondaryNextToThePrimary() {
        assertTrue(limitReached(hasPrimary = true, secondaries = 0, limit = 1))
    }

    @Test
    fun isStreamSessionLimitReached_raisedLimitAdmitsMoreViewers() {
        assertFalse(limitReached(hasPrimary = true, secondaries = 3, limit = 5))
        assertTrue(limitReached(hasPrimary = true, secondaries = 4, limit = 5))
    }

    @Test
    fun isStreamSessionLimitReached_newPrimaryCountsTheRemainingSecondaries() {
        assertTrue(limitReached(hasPrimary = false, secondaries = 2, limit = 1))
        assertTrue(limitReached(hasPrimary = false, secondaries = 3, limit = 3))
        assertFalse(limitReached(hasPrimary = false, secondaries = 2, limit = 3))
    }

    @Test
    fun isStreamSessionLimitReached_neverRejectsAReplacedSession() {
        assertFalse(
            WebRtcManager.isStreamSessionLimitReached(
                hasPrimary = true,
                secondarySessionCount = 4,
                replacesExistingSession = true,
                sessionLimit = 1,
            ),
        )
    }

    private fun limitReached(hasPrimary: Boolean, secondaries: Int, limit: Int): Boolean =
        WebRtcManager.isStreamSessionLimitReached(
            hasPrimary = hasPrimary,
            secondarySessionCount = secondaries,
            replacesExistingSession = false,
            sessionLimit = limit,
        )
}
