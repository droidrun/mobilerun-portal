package com.mobilerun.portal.streaming

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRtcManagerDeviceAudioTest {
    private fun allowed(
        sdk: Int = 29,
        flagEnabled: Boolean = true,
        streamMode: Boolean = true,
    ) = WebRtcManager.deviceAudioAllowed(sdk, flagEnabled, streamMode)

    @Test
    fun deviceAudioAllowed_needsEveryCondition() {
        assertTrue(allowed())
        assertTrue(allowed(sdk = 34))
    }

    @Test
    fun deviceAudioAllowed_needsTheSettingsSwitch() {
        assertFalse(allowed(flagEnabled = false))
    }

    @Test
    fun deviceAudioAllowed_rejectsOldSdkAndNonStreamCapture() {
        assertFalse(allowed(sdk = 28))
        assertFalse(allowed(streamMode = false))
    }
}
