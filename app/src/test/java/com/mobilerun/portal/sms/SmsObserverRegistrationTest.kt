package com.mobilerun.portal.sms

import org.junit.Assert.assertEquals
import org.junit.Test

class SmsObserverRegistrationTest {
    private val time = FakeReporterScheduler()
    private var granted = false
    private var registrable = { granted }
    private var attempts = 0
    private var cancelled = 0
    private var scans = 0

    private val registration = SmsObserverRegistration(
        register = {
            attempts += 1
            if (!registrable()) throw SecurityException("READ_SMS")
            SmsCancellable { cancelled += 1 }
        },
        hasReadSms = { granted },
        scheduler = time.scheduler,
        onReadSms = { scans += 1 },
    )

    private fun recheck(times: Int = 1) = time.advanceBy(SmsReporterCore.BLOCKED_RETRY_MS * times)

    @Test
    fun failedRegistration_isRetriedOnceReadSmsIsGranted() {
        registration.update(enabled = true)
        registration.retry()
        assertEquals(1, attempts)

        granted = true
        registration.retry()
        registration.retry()
        assertEquals(2, attempts)

        registration.update(enabled = false)
        registration.retry()
        assertEquals(1, cancelled)
        assertEquals(2, attempts)
    }

    @Test
    fun grantWithoutAScanTrigger_registersAndScansOnTheNextRecheck() {
        registration.update(enabled = true)
        recheck()
        assertEquals(1, attempts)
        assertEquals(0, scans)

        granted = true
        recheck()
        assertEquals(2, attempts)
        assertEquals(1, scans)

        recheck(times = 3)
        assertEquals(2, attempts)
        assertEquals(1, scans)
    }

    @Test
    fun registeredWithoutReadSms_scansOnceItIsGranted() {
        registrable = { true }
        registration.update(enabled = true)
        recheck()
        assertEquals(0, scans)

        granted = true
        recheck()
        assertEquals(1, attempts)
        assertEquals(1, scans)

        recheck(times = 3)
        assertEquals(1, scans)
    }

    @Test
    fun readSmsLostAfterRegistering_isRecheckedFromTheNextScanTrigger() {
        granted = true
        registration.update(enabled = true)
        granted = false
        registration.retry()

        granted = true
        recheck()
        assertEquals(1, scans)
    }

    @Test
    fun recheck_isASingleTask() {
        registration.update(enabled = true)
        registration.retry()
        registration.retry()

        granted = true
        recheck()
        assertEquals(1, scans)
    }

    @Test
    fun retryAfterTurnedOff_neitherRegistersNorRechecks() {
        granted = true
        registration.update(enabled = true)
        // A scan trigger read the switch on before this turned it off.
        registration.update(enabled = false)
        registration.retry()
        assertEquals(1, attempts)
        assertEquals(1, cancelled)

        granted = false
        registration.retry()
        granted = true
        recheck(times = 3)
        assertEquals(1, attempts)
        assertEquals(0, scans)
    }

    @Test
    fun recheck_stopsWhenTurnedOff() {
        registration.update(enabled = true)
        registration.update(enabled = false)

        granted = true
        recheck(times = 3)
        assertEquals(1, attempts)
        assertEquals(0, scans)
    }
}
