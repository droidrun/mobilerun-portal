package com.mobilerun.portal.keepalive

// Blocks keyguard-only recovery after it fails, until the next screen event or service restart.
class KeepAliveKeyguardRecoveryGate {
    private var attemptKeyguardOnly = false

    var keyguardOnlyRecoveryBlocked = false
        private set

    fun effectiveKeyguardLocked(keyguardLocked: Boolean): Boolean =
        keyguardLocked && !keyguardOnlyRecoveryBlocked

    fun beginAttempt() {
        attemptKeyguardOnly = false
    }

    fun onRecoveryActivityLaunched(
        deviceLocked: Boolean,
        keyguardLocked: Boolean,
    ) {
        attemptKeyguardOnly = !deviceLocked && keyguardLocked
    }

    fun finishAttempt(success: Boolean) {
        if (attemptKeyguardOnly && !success) {
            keyguardOnlyRecoveryBlocked = true
        }
        attemptKeyguardOnly = false
    }

    fun onScreenEvent() {
        keyguardOnlyRecoveryBlocked = false
    }
}
