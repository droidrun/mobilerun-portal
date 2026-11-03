package com.mobilerun.portal.keepalive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAliveRecoveryPolicyTest {
    @Test
    fun evaluate_returnsNoRecoveryWhenDisabled() {
        val decision =
            KeepAliveRecoveryPolicy.evaluate(
                enabled = false,
                interactive = false,
                deviceLocked = true,
                keyguardLocked = true,
                lastRecoveryAttemptAtMs = 0L,
                nowMs = 1_000L,
            )

        assertFalse(decision.shouldAttemptRecovery)
        assertFalse(decision.shouldWakeDisplay)
        assertFalse(decision.shouldLaunchRecoveryActivity)
        assertEquals(null, decision.degradedReason)
    }

    @Test
    fun evaluate_returnsNoRecoveryWhenDeviceIsInteractiveAndUnlocked() {
        val decision =
            KeepAliveRecoveryPolicy.evaluate(
                enabled = true,
                interactive = true,
                deviceLocked = false,
                keyguardLocked = false,
                lastRecoveryAttemptAtMs = 0L,
                nowMs = 1_000L,
            )

        assertFalse(decision.shouldAttemptRecovery)
        assertFalse(decision.shouldWakeDisplay)
        assertFalse(decision.shouldLaunchRecoveryActivity)
    }

    @Test
    fun evaluate_wakesDisplayWhenScreenIsOff() {
        val decision =
            KeepAliveRecoveryPolicy.evaluate(
                enabled = true,
                interactive = false,
                deviceLocked = false,
                keyguardLocked = false,
                lastRecoveryAttemptAtMs = 0L,
                nowMs = 1_000L,
            )

        assertTrue(decision.shouldAttemptRecovery)
        assertTrue(decision.shouldWakeDisplay)
        assertFalse(decision.shouldLaunchRecoveryActivity)
    }

    @Test
    fun evaluate_launchesRecoveryActivityWhenLocked() {
        val decision =
            KeepAliveRecoveryPolicy.evaluate(
                enabled = true,
                interactive = true,
                deviceLocked = true,
                keyguardLocked = true,
                lastRecoveryAttemptAtMs = 0L,
                nowMs = 1_000L,
            )

        assertTrue(decision.shouldAttemptRecovery)
        assertFalse(decision.shouldWakeDisplay)
        assertTrue(decision.shouldLaunchRecoveryActivity)
    }

    @Test
    fun evaluate_doesNotThrottleWhenNoRecentAttemptTimestampExists() {
        val decision =
            KeepAliveRecoveryPolicy.evaluate(
                enabled = true,
                interactive = false,
                deviceLocked = false,
                keyguardLocked = false,
                lastRecoveryAttemptAtMs = 0L,
                nowMs = 10_000L,
                throttleMs = 10_000L,
            )

        assertTrue(decision.shouldAttemptRecovery)
        assertTrue(decision.shouldWakeDisplay)
        assertFalse(decision.shouldLaunchRecoveryActivity)
        assertEquals(null, decision.degradedReason)
    }

    @Test
    fun evaluate_throttlesRecentFailedAttempts() {
        val decision =
            KeepAliveRecoveryPolicy.evaluate(
                enabled = true,
                interactive = false,
                deviceLocked = false,
                keyguardLocked = false,
                lastRecoveryAttemptAtMs = 5_000L,
                nowMs = 10_000L,
                throttleMs = 10_000L,
            )

        assertFalse(decision.shouldAttemptRecovery)
        assertFalse(decision.shouldWakeDisplay)
        assertFalse(decision.shouldLaunchRecoveryActivity)
        assertEquals("recovery_throttled", decision.degradedReason)
    }

    @Test
    fun evaluate_launchesRecoveryActivityForSwipeLockWithScreenOn() {
        val decision =
            KeepAliveRecoveryPolicy.evaluate(
                enabled = true,
                interactive = true,
                deviceLocked = false,
                keyguardLocked = true,
                lastRecoveryAttemptAtMs = 0L,
                nowMs = 1_000L,
            )

        assertTrue(decision.shouldAttemptRecovery)
        assertFalse(decision.shouldWakeDisplay)
        assertTrue(decision.shouldLaunchRecoveryActivity)
    }

    @Test
    fun evaluate_throttlesRecentFailedSwipeLockAttempts() {
        val decision =
            KeepAliveRecoveryPolicy.evaluate(
                enabled = true,
                interactive = true,
                deviceLocked = false,
                keyguardLocked = true,
                lastRecoveryAttemptAtMs = 5_000L,
                nowMs = 10_000L,
            )

        assertFalse(decision.shouldAttemptRecovery)
        assertFalse(decision.shouldLaunchRecoveryActivity)
        assertEquals("recovery_throttled", decision.degradedReason)
    }
}
