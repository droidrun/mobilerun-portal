package com.mobilerun.portal.keepalive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeepAliveRecoveryActivityStatePolicyTest {
    @Test
    fun resultForResume_doesNotCompleteWhenDeviceIsUnlockedButStillDark() {
        val result =
            KeepAliveRecoveryActivityStatePolicy.resultForResume(
                KeepAliveRecoveryScreenState(
                    interactive = false,
                    deviceLocked = false,
                    keyguardLocked = false,
                ),
            )

        assertNull(result)
    }

    @Test
    fun resultForResume_completesWhenDeviceIsInteractiveAndUnlocked() {
        val result =
            KeepAliveRecoveryActivityStatePolicy.resultForResume(
                KeepAliveRecoveryScreenState(
                    interactive = true,
                    deviceLocked = false,
                    keyguardLocked = false,
                ),
            )

        requireNotNull(result)
        assertEquals(true, result.success)
        assertEquals(null, result.reason)
    }

    @Test
    fun resultForDismissCallback_waitsWhenDismissIsCancelledAndDeviceIsStillLocked() {
        val result =
            KeepAliveRecoveryActivityStatePolicy.resultForDismissCallback(
                KeepAliveRecoveryScreenState(
                    interactive = true,
                    deviceLocked = true,
                    keyguardLocked = true,
                ),
                KeepAliveDismissCallbackState.Failed("dismiss_cancelled"),
            )

        assertNull(result)
    }

    @Test
    fun resultForDismissCallback_waitsWhenDismissSucceedsButScreenIsStillDark() {
        val result =
            KeepAliveRecoveryActivityStatePolicy.resultForDismissCallback(
                KeepAliveRecoveryScreenState(
                    interactive = false,
                    deviceLocked = false,
                    keyguardLocked = false,
                ),
                KeepAliveDismissCallbackState.Succeeded,
            )

        assertNull(result)
    }

    @Test
    fun resultForDismissCallback_completesWhenDeviceRecoversAfterDismissFailure() {
        val result =
            KeepAliveRecoveryActivityStatePolicy.resultForDismissCallback(
                KeepAliveRecoveryScreenState(
                    interactive = true,
                    deviceLocked = false,
                    keyguardLocked = false,
                ),
                KeepAliveDismissCallbackState.Failed("dismiss_cancelled"),
            )

        requireNotNull(result)
        assertEquals(true, result.success)
        assertEquals(null, result.reason)
    }

    @Test
    fun resultForTimeout_preservesDismissFailureReasonWhenDeviceStaysLocked() {
        val result =
            KeepAliveRecoveryActivityStatePolicy.resultForTimeout(
                KeepAliveRecoveryScreenState(
                    interactive = true,
                    deviceLocked = true,
                    keyguardLocked = true,
                ),
                KeepAliveDismissCallbackState.Failed("dismiss_cancelled"),
            )

        assertEquals(false, result.success)
        assertEquals("dismiss_cancelled", result.reason)
    }

    @Test
    fun resultForTimeout_usesLiveStateReasonWhenScreenIsStillDarkWithoutDismissFailure() {
        val result =
            KeepAliveRecoveryActivityStatePolicy.resultForTimeout(
                KeepAliveRecoveryScreenState(
                    interactive = false,
                    deviceLocked = false,
                    keyguardLocked = false,
                ),
            )

        assertEquals(false, result.success)
        assertEquals("screen_not_interactive", result.reason)
    }

    @Test
    fun resultForTimeout_usesLiveStateReasonWhenDeviceIsStillLockedWithoutDismissFailure() {
        val result =
            KeepAliveRecoveryActivityStatePolicy.resultForTimeout(
                KeepAliveRecoveryScreenState(
                    interactive = true,
                    deviceLocked = true,
                    keyguardLocked = true,
                ),
                KeepAliveDismissCallbackState.None,
            )

        assertEquals(false, result.success)
        assertEquals("device_still_locked", result.reason)
    }

    @Test
    fun resultForResume_doesNotCompleteWhileSwipeKeyguardIsShowing() {
        val result =
            KeepAliveRecoveryActivityStatePolicy.resultForResume(
                KeepAliveRecoveryScreenState(
                    interactive = true,
                    deviceLocked = false,
                    keyguardLocked = true,
                ),
            )

        assertNull(result)
    }

    @Test
    fun resultForDismissCallback_completesWhenSwipeKeyguardDismissSucceeds() {
        val result =
            KeepAliveRecoveryActivityStatePolicy.resultForDismissCallback(
                KeepAliveRecoveryScreenState(
                    interactive = true,
                    deviceLocked = false,
                    keyguardLocked = true,
                ),
                KeepAliveDismissCallbackState.Succeeded,
            )

        requireNotNull(result)
        assertEquals(true, result.success)
        assertEquals(null, result.reason)
    }

    @Test
    fun resultForDismissCallback_waitsWhenDismissSucceedsButSecureLockIsStillLocked() {
        val result =
            KeepAliveRecoveryActivityStatePolicy.resultForDismissCallback(
                KeepAliveRecoveryScreenState(
                    interactive = true,
                    deviceLocked = true,
                    keyguardLocked = true,
                ),
                KeepAliveDismissCallbackState.Succeeded,
            )

        assertNull(result)
    }

    @Test
    fun resultForDismissCallback_finishesOnDismissErrorWithoutSecureLock() {
        val result =
            KeepAliveRecoveryActivityStatePolicy.resultForDismissCallback(
                KeepAliveRecoveryScreenState(
                    interactive = true,
                    deviceLocked = false,
                    keyguardLocked = true,
                ),
                KeepAliveDismissCallbackState.Failed("dismiss_error"),
            )

        requireNotNull(result)
        assertEquals(false, result.success)
        assertEquals("dismiss_error", result.reason)
    }

    @Test
    fun resultForDismissCallback_waitsOnDismissErrorWithSecureLock() {
        val result =
            KeepAliveRecoveryActivityStatePolicy.resultForDismissCallback(
                KeepAliveRecoveryScreenState(
                    interactive = true,
                    deviceLocked = true,
                    keyguardLocked = true,
                ),
                KeepAliveDismissCallbackState.Failed("dismiss_error"),
            )

        assertNull(result)
    }

    @Test
    fun resultForDismissCallback_waitsOnDismissCancelledWithoutSecureLock() {
        val result =
            KeepAliveRecoveryActivityStatePolicy.resultForDismissCallback(
                KeepAliveRecoveryScreenState(
                    interactive = true,
                    deviceLocked = false,
                    keyguardLocked = true,
                ),
                KeepAliveDismissCallbackState.Failed("dismiss_cancelled"),
            )

        assertNull(result)
    }

    @Test
    fun resultForTimeout_reportsDeviceStillLockedWhenSwipeKeyguardStaysShowing() {
        val result =
            KeepAliveRecoveryActivityStatePolicy.resultForTimeout(
                KeepAliveRecoveryScreenState(
                    interactive = true,
                    deviceLocked = false,
                    keyguardLocked = true,
                ),
                KeepAliveDismissCallbackState.None,
            )

        assertEquals(false, result.success)
        assertEquals("device_still_locked", result.reason)
    }
}
