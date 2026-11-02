package com.mobilerun.portal.service

import kotlin.random.Random

/**
 * Paces reconnects like the iOS portal: 1 s doubling up to 30 s with jitter, within a 10 s budget
 * per outage. Only a connection that stayed open for [healthyConnectionMs] starts a fresh budget,
 * so a relay that accepts and drops at once spends the one it started with.
 */
internal class ReverseReconnectBackoff(
    private val baseDelayMs: Long = BASE_DELAY_MS,
    private val maxDelayMs: Long = MAX_DELAY_MS,
    private val jitterRatio: Double = JITTER_RATIO,
    private val retryBudgetMs: Long = RETRY_BUDGET_MS,
    private val healthyConnectionMs: Long = HEALTHY_CONNECTION_MS,
    private val networkWaitDelayMs: Long = NETWORK_WAIT_DELAY_MS,
    private val random: () -> Double = { Random.nextDouble() },
) {
    companion object {
        const val BASE_DELAY_MS = 1_000L
        const val MAX_DELAY_MS = 30_000L
        const val JITTER_RATIO = 0.2
        const val RETRY_BUDGET_MS = 10_000L
        const val HEALTHY_CONNECTION_MS = 15_000L
        const val NETWORK_WAIT_DELAY_MS = 5_000L
        private const val MAX_SHIFT = 20
    }

    private var deadlineMs: Long? = null
    private var connectedAtMs: Long? = null
    private var failures = 0
    private var waitForNetwork = false

    /** After a restart the network may come up late; the budget only runs while it is there. */
    @Synchronized
    fun waitForNetwork() {
        waitForNetwork = true
    }

    @Synchronized
    fun onConnected(nowMs: Long) {
        connectedAtMs = nowMs
        waitForNetwork = false
    }

    /** The delay before the next attempt, or null once the outage has used up its budget. */
    @Synchronized
    fun nextRetry(nowMs: Long, networkAvailable: Boolean): Long? {
        val connectedForMs = connectedAtMs?.let { nowMs - it }
        connectedAtMs = null
        if (connectedForMs != null && connectedForMs >= healthyConnectionMs) {
            deadlineMs = null
            failures = 0
        }
        if (waitForNetwork && !networkAvailable) {
            deadlineMs = null
            failures = 0
            return jittered(networkWaitDelayMs)
        }
        val deadline = deadlineMs ?: (nowMs + retryBudgetMs).also { deadlineMs = it }
        val delayMs = jittered(
            (baseDelayMs shl failures.coerceAtMost(MAX_SHIFT)).coerceAtMost(maxDelayMs),
        )
        if (deadline - nowMs <= delayMs) return null
        failures++
        return delayMs
    }

    @Synchronized
    fun reset() {
        deadlineMs = null
        connectedAtMs = null
        failures = 0
        waitForNetwork = false
    }

    private fun jittered(delayMs: Long): Long {
        val jitter = 1.0 + jitterRatio * (2 * random() - 1)
        return (delayMs * jitter).toLong().coerceAtLeast(1L)
    }
}
