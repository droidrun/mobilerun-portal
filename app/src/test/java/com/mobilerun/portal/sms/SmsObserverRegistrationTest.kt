package com.mobilerun.portal.sms

import org.junit.Assert.assertEquals
import org.junit.Test

class SmsObserverRegistrationTest {
    @Test
    fun failedRegistration_isRetriedOnceReadSmsIsGranted() {
        var granted = false
        var attempts = 0
        var cancelled = 0
        val registration = SmsObserverRegistration(
            register = {
                attempts += 1
                if (!granted) throw SecurityException("READ_SMS")
                SmsCancellable { cancelled += 1 }
            },
        )

        registration.update(enabled = true)
        registration.retry(enabled = true, readSmsGranted = false)
        assertEquals(1, attempts)

        granted = true
        registration.retry(enabled = true, readSmsGranted = true)
        registration.retry(enabled = true, readSmsGranted = true)
        assertEquals(2, attempts)

        registration.update(enabled = false)
        registration.retry(enabled = false, readSmsGranted = true)
        assertEquals(1, cancelled)
        assertEquals(2, attempts)
    }
}
