package com.mobilerun.portal.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsPartRateLimiterTest {
    private var now = 0L
    private val limiter = SmsPartRateLimiter(maxParts = 25, windowMs = 60_000, clock = { now })

    @Test
    fun refusesPastTwentyFivePartsInSixtySeconds() {
        assertTrue(limiter.tryAcquire(25))
        assertFalse(limiter.tryAcquire(1))
        now = 59_999
        assertFalse(limiter.tryAcquire(1))
        now = 60_000
        assertTrue(limiter.tryAcquire(25))
    }

    @Test
    fun singleSendOverTheCapIsAlwaysRefused() {
        assertFalse(limiter.tryAcquire(26))
        assertEquals(0, limiter.used())
    }

    @Test
    fun recordedLocalSendsCountAndRefundReturnsParts() {
        limiter.record(20)
        assertFalse(limiter.tryAcquire(6))
        assertTrue(limiter.tryAcquire(5))
        limiter.refund(5)
        assertEquals(20, limiter.used())
    }
}
