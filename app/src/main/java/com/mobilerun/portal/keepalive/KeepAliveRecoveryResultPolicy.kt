package com.mobilerun.portal.keepalive

data class KeepAliveRecoveryResultDecision(
    val shouldIgnore: Boolean,
    val shouldMarkSuccess: Boolean,
    val failureReason: String? = null,
)

object KeepAliveRecoveryResultPolicy {
    private fun stateFailureReason(
        interactive: Boolean,
        deviceLocked: Boolean,
        keyguardLocked: Boolean,
    ): String {
        return if (deviceLocked || keyguardLocked) {
            "device_still_locked"
        } else if (!interactive) {
            "screen_not_interactive"
        } else {
            "dismiss_failed"
        }
    }

    fun evaluate(
        enabled: Boolean,
        activeRecoveryToken: Long?,
        reportedRecoveryToken: Long,
        callbackSuccess: Boolean,
        interactive: Boolean,
        deviceLocked: Boolean,
        keyguardLocked: Boolean,
        failureReason: String?,
    ): KeepAliveRecoveryResultDecision {
        if (!enabled) {
            return KeepAliveRecoveryResultDecision(
                shouldIgnore = true,
                shouldMarkSuccess = false,
            )
        }

        if (activeRecoveryToken == null || activeRecoveryToken != reportedRecoveryToken) {
            return KeepAliveRecoveryResultDecision(
                shouldIgnore = true,
                shouldMarkSuccess = false,
            )
        }

        // A reported dismissal counts while the keyguard still reads locked.
        if (interactive && !deviceLocked && (!keyguardLocked || callbackSuccess)) {
            return KeepAliveRecoveryResultDecision(
                shouldIgnore = false,
                shouldMarkSuccess = true,
            )
        }

        val resolvedFailureReason =
            if (callbackSuccess) {
                stateFailureReason(interactive, deviceLocked, keyguardLocked)
            } else {
                failureReason ?: stateFailureReason(interactive, deviceLocked, keyguardLocked)
            }

        return KeepAliveRecoveryResultDecision(
            shouldIgnore = false,
            shouldMarkSuccess = false,
            failureReason = resolvedFailureReason,
        )
    }

    fun evaluatePersisted(
        enabled: Boolean,
        activeRecoveryToken: Long?,
        reportedRecoveryToken: Long,
        callbackSuccess: Boolean,
        failureReason: String?,
    ): KeepAliveRecoveryResultDecision {
        if (!enabled) {
            return KeepAliveRecoveryResultDecision(
                shouldIgnore = true,
                shouldMarkSuccess = false,
            )
        }

        if (activeRecoveryToken == null || activeRecoveryToken != reportedRecoveryToken) {
            return KeepAliveRecoveryResultDecision(
                shouldIgnore = true,
                shouldMarkSuccess = false,
            )
        }

        return if (callbackSuccess) {
            KeepAliveRecoveryResultDecision(
                shouldIgnore = false,
                shouldMarkSuccess = true,
            )
        } else {
            KeepAliveRecoveryResultDecision(
                shouldIgnore = false,
                shouldMarkSuccess = false,
                failureReason = failureReason ?: "dismiss_failed",
            )
        }
    }
}
