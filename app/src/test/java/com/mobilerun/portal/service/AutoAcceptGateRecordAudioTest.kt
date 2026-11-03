package com.mobilerun.portal.service

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoAcceptGateRecordAudioTest {
    @After
    fun tearDown() {
        AutoAcceptGate.disarmRecordAudio()
    }

    @Test
    fun recordAudio_isArmedOnlyWithinItsTtl() {
        assertFalse(AutoAcceptGate.isRecordAudioArmed())

        AutoAcceptGate.armRecordAudio(ttlMs = 30_000L)
        val now = System.currentTimeMillis()
        assertTrue(AutoAcceptGate.isRecordAudioArmed(now))
        assertFalse(AutoAcceptGate.isRecordAudioArmed(now + 31_000L))
        // Expiry clears the gate.
        assertFalse(AutoAcceptGate.isRecordAudioArmed(now))
    }

    @Test
    fun recordAudio_disarmClosesTheGate() {
        AutoAcceptGate.armRecordAudio()
        AutoAcceptGate.disarmRecordAudio()
        assertFalse(AutoAcceptGate.isRecordAudioArmed())
    }

    @Test
    fun recordAudio_isIndependentOfMediaProjection() {
        try {
            AutoAcceptGate.armMediaProjection()
            assertFalse(AutoAcceptGate.isRecordAudioArmed())

            AutoAcceptGate.armRecordAudio()
            AutoAcceptGate.disarmMediaProjection()
            assertTrue(AutoAcceptGate.isRecordAudioArmed())
            assertFalse(AutoAcceptGate.isMediaProjectionArmed())

            AutoAcceptGate.armMediaProjection()
            AutoAcceptGate.disarmRecordAudio()
            assertTrue(AutoAcceptGate.isMediaProjectionArmed())
        } finally {
            AutoAcceptGate.disarmMediaProjection()
        }
    }
}
