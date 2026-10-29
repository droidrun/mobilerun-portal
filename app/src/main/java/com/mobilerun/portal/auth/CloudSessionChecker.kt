package com.mobilerun.portal.auth

import com.mobilerun.portal.config.ConfigManager
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Pings get-session when due, so an idle socket does not let the session lapse. */
class CloudSessionChecker(
    private val client: DeviceCodeAuthClient,
    private val config: ConfigManager,
    private val executor: Executor,
    private val nowMs: () -> Long,
    private val onSessionEnded: (String) -> Unit,
    private val minCallIntervalMs: Long = 60_000L,
) {
    private val inFlight = AtomicBoolean(false)
    private val lastCallMs = AtomicLong(Long.MIN_VALUE / 2)

    /** Runs at most once per [minCallIntervalMs]; returns true when a check was started. */
    fun checkIfDue(): Boolean {
        val now = nowMs()
        val last = lastCallMs.get()
        if (now - last in 0 until minCallIntervalMs || !lastCallMs.compareAndSet(last, now)) return false
        if (!config.cloudSessionActive) return false
        if (!DeviceCodeAuthClient.sessionCheckDue(config.cloudSessionCheckedAtMs, nowMs())) return false
        if (!inFlight.compareAndSet(false, true)) return false
        val token = config.reverseConnectionToken
        return try {
            executor.execute {
                try {
                    when (client.session(token)) {
                        SessionResult.Valid -> config.cloudSessionCheckedAtMs = nowMs()
                        SessionResult.Invalid -> onSessionEnded(token)
                        SessionResult.Unavailable -> Unit
                    }
                } finally {
                    inFlight.set(false)
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            inFlight.set(false)
            false
        }
    }
}
