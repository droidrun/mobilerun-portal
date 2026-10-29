package com.mobilerun.portal.service

import android.content.Intent
import com.mobilerun.portal.config.ConfigManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverseConnectionRestartTest {
    @Test
    fun resumesOnlyWhenOptedInWasConnectedHasCredentialsAndIsNotRunning() {
        assertTrue(resume())
        assertFalse(resume(optedIn = false))
        assertFalse(resume(wasEnabled = false))
        assertFalse(resume(credentials = false))
        assertFalse(resume(running = true))
    }

    @Test
    fun bootAndUpdateMarkTheConnectionOnBeforeStartingTheService() {
        listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED).forEach { action ->
            val configManager = optedInConfig()
            val starts = mutableListOf<Unit>()

            assertTrue(
                resumeReverseConnectionAfterRestart(action, configManager, serviceRunning = false) {
                    starts += Unit
                    verify { configManager.reverseConnectionEnabled = true }
                },
            )
            assertEquals(1, starts.size)
        }
    }

    @Test
    fun otherBroadcastsAndDeclinedResumeDoNotStartTheService() {
        val configManager = optedInConfig()
        var started = false

        assertFalse(
            resumeReverseConnectionAfterRestart("android.intent.action.TIME_SET", configManager, false) {
                started = true
            },
        )
        assertFalse(resumeReverseConnectionAfterRestart(null, configManager, false) { started = true })
        every { configManager.reconnectAfterRestartEnabled } returns false
        assertFalse(
            resumeReverseConnectionAfterRestart(Intent.ACTION_BOOT_COMPLETED, configManager, false) {
                started = true
            },
        )
        assertFalse(started)
        verify(exactly = 0) { configManager.reverseConnectionEnabled = any() }
    }

    @Test
    fun stickyRestartStopsOnlyWhenTheConnectionWasTurnedOff() {
        assertTrue(shouldStopStickyRestart(hasIntent = false, wasEnabled = false))
        assertFalse(shouldStopStickyRestart(hasIntent = false, wasEnabled = true))
        assertFalse(shouldStopStickyRestart(hasIntent = true, wasEnabled = false))
        assertFalse(shouldStopStickyRestart(hasIntent = true, wasEnabled = true))
    }

    private fun optedInConfig(): ConfigManager = mockk<ConfigManager>(relaxed = true).also {
        every { it.reconnectAfterRestartEnabled } returns true
        every { it.reverseConnectionWasEnabled } returns true
        every { it.hasCloudCredentials } returns true
    }

    private fun resume(
        optedIn: Boolean = true,
        wasEnabled: Boolean = true,
        credentials: Boolean = true,
        running: Boolean = false,
    ): Boolean = shouldResumeReverseConnectionAfterRestart(
        reconnectAfterRestartEnabled = optedIn,
        wasEnabled = wasEnabled,
        hasCloudCredentials = credentials,
        serviceRunning = running,
    )
}
