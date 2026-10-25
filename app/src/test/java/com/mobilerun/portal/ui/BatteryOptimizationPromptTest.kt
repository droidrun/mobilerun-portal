package com.mobilerun.portal.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryOptimizationPromptTest {
    @Test
    fun promptsOnlyWhenALocalServerIsEnabledAndPortalIsOptimized() {
        assertTrue(BatteryOptimizationPrompt.shouldPrompt(localServerEnabled = true, exempt = false))
        assertFalse(BatteryOptimizationPrompt.shouldPrompt(localServerEnabled = true, exempt = true))
        assertFalse(BatteryOptimizationPrompt.shouldPrompt(localServerEnabled = false, exempt = false))
    }
}
