package com.mobilerun.portal.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SmsPartRateLimiterTest {
    private var now = 0L
    private val limiter = SmsPartRateLimiter(maxParts = 25, windowMs = 60_000, clock = { now })

    @Test
    fun refusesPastTwentyFivePartsInSixtySeconds() {
        assertNotNull(limiter.tryAcquire(25))
        assertNull(limiter.tryAcquire(1))
        now = 59_999
        assertNull(limiter.tryAcquire(1))
        now = 60_000
        assertNotNull(limiter.tryAcquire(25))
    }

    @Test
    fun singleSendOverTheCapIsAlwaysRefused() {
        assertNull(limiter.tryAcquire(26))
        assertEquals(0, limiter.used())
    }

    @Test
    fun recordedLocalSendsCountAndRefundReturnsParts() {
        limiter.record(20)
        assertNull(limiter.tryAcquire(6))
        limiter.refund(limiter.tryAcquire(5)!!)
        assertEquals(20, limiter.used())
    }

    @Test
    fun refundRemovesItsOwnAcquisition_notALaterLocalSendOfTheSameSize() {
        val relay = limiter.tryAcquire(2)!!
        now = 50_000
        limiter.record(2)
        limiter.refund(relay)

        // The local send at 50 s still counts until 110 s.
        now = 60_000
        assertEquals(2, limiter.used())
        assertNull(limiter.tryAcquire(24))
    }
}
