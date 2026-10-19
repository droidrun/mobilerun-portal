package com.mobilerun.portal.service

import kotlin.random.Random

internal data class ReverseReconnectStep(
    val delayMs: Long,
    val releaseMedia: Boolean,
)

/**
 * Paces reconnects within one outage: a fixed fast cadence first, then exponential backoff.
 * A connection only ends the outage once it has stayed open for [stableConnectionMs]; shorter
 * ones that lasted [minRealConnectionMs] re-arm the fast cadence, while near-instant
 * accept-then-close loops keep backing off.
 */
internal class ReverseReconnectBackoff(
    private val baseDelayMs: Long = BASE_DELAY_MS,
    private val maxDelayMs: Long = MAX_DELAY_MS,
    private val jitterRatio: Double = JITTER_RATIO,
    private val fastRetryWindowMs: Long = FAST_RETRY_WINDOW_MS,
    private val minRealConnectionMs: Long = MIN_REAL_CONNECTION_MS,
    private val stableConnectionMs: Long = STABLE_CONNECTION_MS,
    private val longOutageMs: Long = LONG_OUTAGE_MS,
    private val random: () -> Double = { Random.nextDouble() },
) {
    companion object {
        const val BASE_DELAY_MS = 3_000L
        const val MAX_DELAY_MS = 30_000L
        const val JITTER_RATIO = 0.2

        // Covers the cloud's BYOD reconnect grace (30 s) plus relay presence TTL (30 s).
        const val FAST_RETRY_WINDOW_MS = 60_000L
        const val MIN_REAL_CONNECTION_MS = 5_000L
        const val STABLE_CONNECTION_MS = 60_000L
        const val LONG_OUTAGE_MS = 30 * 60 * 1000L
        private const val MAX_SHIFT = 20
    }

    private var outageStartedAtMs: Long? = null
    private var fastUntilMs = 0L
    private var connectedAtMs: Long? = null
    private var attempt = 0
    private var mediaReleased = false

    @Synchronized
    fun onConnected(nowMs: Long) {
        connectedAtMs = nowMs
    }

    /** [ReverseReconnectStep.releaseMedia] is set once per outage, when it turns long. */
    @Synchronized
    fun nextRetry(nowMs: Long): ReverseReconnectStep {
        val connectedForMs = connectedAtMs?.let { nowMs - it }
        connectedAtMs = null
        if (connectedForMs != null && connectedForMs >= stableConnectionMs) {
            resetLocked()
        }
        val startedAtMs = outageStartedAtMs
        if (startedAtMs == null) {
            outageStartedAtMs = nowMs
            fastUntilMs = nowMs + fastRetryWindowMs
        } else if (connectedForMs != null && connectedForMs >= minRealConnectionMs) {
            fastUntilMs = nowMs + fastRetryWindowMs
        }
        val delayMs = if (nowMs < fastUntilMs) {
            baseDelayMs
        } else {
            (baseDelayMs shl attempt.coerceAtMost(MAX_SHIFT)).coerceAtMost(maxDelayMs)
                .also { attempt++ }
        }
        val releaseMedia = !mediaReleased && nowMs - (startedAtMs ?: nowMs) >= longOutageMs
        if (releaseMedia) mediaReleased = true
        val jitter = 1.0 + jitterRatio * (2 * random() - 1)
        return ReverseReconnectStep(
            delayMs = (delayMs * jitter).toLong().coerceAtLeast(1L),
            releaseMedia = releaseMedia,
        )
    }

    @Synchronized
    fun reset() {
        resetLocked()
    }

    private fun resetLocked() {
        outageStartedAtMs = null
        fastUntilMs = 0L
        connectedAtMs = null
        attempt = 0
        mediaReleased = false
    }
}
