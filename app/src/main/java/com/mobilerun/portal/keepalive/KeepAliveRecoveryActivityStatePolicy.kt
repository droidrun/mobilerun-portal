package com.mobilerun.portal.keepalive

data class KeepAliveRecoveryScreenState(
    val interactive: Boolean,
    val deviceLocked: Boolean,
    val keyguardLocked: Boolean,
)

data class KeepAliveRecoveryActivityResult(
    val success: Boolean,
    val reason: String? = null,
)

sealed class KeepAliveDismissCallbackState {
    object None : KeepAliveDismissCallbackState()

    object Succeeded : KeepAliveDismissCallbackState()

    data class Failed(val reason: String) : KeepAliveDismissCallbackState()
}

object KeepAliveRecoveryActivityStatePolicy {
    const val DISMISS_ERROR_REASON = "dismiss_error"

    private fun stateFailureReason(screenState: KeepAliveRecoveryScreenState): String {
        return if (screenState.deviceLocked || screenState.keyguardLocked) {
            "device_still_locked"
        } else {
            "screen_not_interactive"
        }
    }

    private fun isUnlocked(screenState: KeepAliveRecoveryScreenState): Boolean =
        screenState.interactive && !screenState.deviceLocked && !screenState.keyguardLocked

    fun resultForResume(screenState: KeepAliveRecoveryScreenState): KeepAliveRecoveryActivityResult? {
        if (isUnlocked(screenState)) {
            return KeepAliveRecoveryActivityResult(success = true)
        }
        return null
    }

    fun resultForDismissCallback(
        screenState: KeepAliveRecoveryScreenState,
        dismissCallbackState: KeepAliveDismissCallbackState,
    ): KeepAliveRecoveryActivityResult? {
        return when {
            isUnlocked(screenState) ->
                KeepAliveRecoveryActivityResult(success = true)
            // No dismissible keyguard, e.g. a disabled or SIM PIN keyguard: stop blocking input now.
            dismissCallbackState is KeepAliveDismissCallbackState.Failed &&
                dismissCallbackState.reason == DISMISS_ERROR_REASON &&
                !screenState.deviceLocked ->
                KeepAliveRecoveryActivityResult(success = false, reason = dismissCallbackState.reason)
            dismissCallbackState is KeepAliveDismissCallbackState.Failed ->
                null
            // The keyguard can still read locked while its dismissal settles.
            dismissCallbackState is KeepAliveDismissCallbackState.Succeeded &&
                screenState.interactive && !screenState.deviceLocked ->
                KeepAliveRecoveryActivityResult(success = true)
            dismissCallbackState is KeepAliveDismissCallbackState.Succeeded ->
                null
            else ->
                null
        }
    }

    fun resultForTimeout(
        screenState: KeepAliveRecoveryScreenState,
        dismissCallbackState: KeepAliveDismissCallbackState = KeepAliveDismissCallbackState.None,
    ): KeepAliveRecoveryActivityResult {
        return when {
            isUnlocked(screenState) ->
                KeepAliveRecoveryActivityResult(success = true)
            dismissCallbackState is KeepAliveDismissCallbackState.Failed ->
                KeepAliveRecoveryActivityResult(success = false, reason = dismissCallbackState.reason)
            else ->
                KeepAliveRecoveryActivityResult(success = false, reason = stateFailureReason(screenState))
        }
    }
}
