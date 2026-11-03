package com.mobilerun.portal.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAudioPermissionPromptTest {
    private fun shouldPrompt(
        sdk: Int = 29,
        flagEnabled: Boolean = true,
        permissionGranted: Boolean = false,
        alreadyPrompted: Boolean = false,
    ) = DeviceAudioPermissionPrompt.shouldPrompt(sdk, flagEnabled, permissionGranted, alreadyPrompted)

    @Test
    fun shouldPrompt_whenDeviceAudioIsOnAndRecordAudioIsMissing() {
        assertTrue(shouldPrompt())
        assertTrue(shouldPrompt(sdk = 36))
    }

    @Test
    fun shouldPrompt_onlyOnce() {
        assertFalse(shouldPrompt(alreadyPrompted = true))
    }

    @Test
    fun shouldPrompt_skipsGrantedDisabledAndOldSdk() {
        assertFalse(shouldPrompt(permissionGranted = true))
        assertFalse(shouldPrompt(flagEnabled = false))
        assertFalse(shouldPrompt(sdk = 28))
    }
}
