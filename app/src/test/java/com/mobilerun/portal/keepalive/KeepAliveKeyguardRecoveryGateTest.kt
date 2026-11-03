package com.mobilerun.portal.keepalive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAliveKeyguardRecoveryGateTest {
    private fun evaluateSwipeLock(gate: KeepAliveKeyguardRecoveryGate): KeepAliveRecoveryDecision =
        KeepAliveRecoveryPolicy.evaluate(
            enabled = true,
            interactive = true,
            deviceLocked = false,
            keyguardLocked = gate.effectiveKeyguardLocked(true),
            lastRecoveryAttemptAtMs = 0L,
            nowMs = 1_000L,
        )

    @Test
    fun finishAttempt_keyguardOnlyFailureBlocksKeyguardOnlyRecovery() {
        val gate = KeepAliveKeyguardRecoveryGate()
        gate.beginAttempt()
        gate.onRecoveryActivityLaunched(deviceLocked = false, keyguardLocked = true)

        gate.finishAttempt(success = false)

        assertTrue(gate.keyguardOnlyRecoveryBlocked)
        assertFalse(gate.effectiveKeyguardLocked(true))
        assertFalse(evaluateSwipeLock(gate).shouldAttemptRecovery)
    }

    @Test
    fun onScreenEvent_unblocksKeyguardOnlyRecovery() {
        val gate = KeepAliveKeyguardRecoveryGate()
        gate.beginAttempt()
        gate.onRecoveryActivityLaunched(deviceLocked = false, keyguardLocked = true)
        gate.finishAttempt(success = false)

        gate.onScreenEvent()

        assertFalse(gate.keyguardOnlyRecoveryBlocked)
        assertTrue(gate.effectiveKeyguardLocked(true))
        val decision = evaluateSwipeLock(gate)
        assertTrue(decision.shouldAttemptRecovery)
        assertTrue(decision.shouldLaunchRecoveryActivity)
    }

    @Test
    fun finishAttempt_secureLockFailureNeverBlocks() {
        val gate = KeepAliveKeyguardRecoveryGate()
        gate.beginAttempt()
        gate.onRecoveryActivityLaunched(deviceLocked = true, keyguardLocked = true)

        gate.finishAttempt(success = false)

        assertFalse(gate.keyguardOnlyRecoveryBlocked)
        assertTrue(gate.effectiveKeyguardLocked(true))
        val decision =
            KeepAliveRecoveryPolicy.evaluate(
                enabled = true,
                interactive = true,
                deviceLocked = true,
                keyguardLocked = gate.effectiveKeyguardLocked(true),
                lastRecoveryAttemptAtMs = 1_000L,
                nowMs = 61_000L,
            )
        assertTrue(decision.shouldAttemptRecovery)
        assertTrue(decision.shouldLaunchRecoveryActivity)
    }

    @Test
    fun finishAttempt_secureLockStillRecoversWhileKeyguardOnlyIsBlocked() {
        val gate = KeepAliveKeyguardRecoveryGate()
        gate.beginAttempt()
        gate.onRecoveryActivityLaunched(deviceLocked = false, keyguardLocked = true)
        gate.finishAttempt(success = false)

        val decision =
            KeepAliveRecoveryPolicy.evaluate(
                enabled = true,
                interactive = true,
                deviceLocked = true,
                keyguardLocked = gate.effectiveKeyguardLocked(true),
                lastRecoveryAttemptAtMs = 0L,
                nowMs = 1_000L,
            )

        assertTrue(decision.shouldAttemptRecovery)
        assertTrue(decision.shouldLaunchRecoveryActivity)
    }

    @Test
    fun finishAttempt_keyguardOnlySuccessDoesNotBlock() {
        val gate = KeepAliveKeyguardRecoveryGate()
        gate.beginAttempt()
        gate.onRecoveryActivityLaunched(deviceLocked = false, keyguardLocked = true)

        gate.finishAttempt(success = true)

        assertFalse(gate.keyguardOnlyRecoveryBlocked)
    }

    @Test
    fun finishAttempt_failureWithoutLaunchedActivityDoesNotBlock() {
        val gate = KeepAliveKeyguardRecoveryGate()
        gate.beginAttempt()
        gate.onRecoveryActivityLaunched(deviceLocked = false, keyguardLocked = true)
        gate.beginAttempt()

        gate.finishAttempt(success = false)

        assertFalse(gate.keyguardOnlyRecoveryBlocked)
    }
}
