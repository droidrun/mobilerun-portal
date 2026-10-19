package com.mobilerun.portal.service

import com.mobilerun.portal.service.ReverseReconnectBackoff.Companion.FAST_RETRY_WINDOW_MS
import com.mobilerun.portal.service.ReverseReconnectBackoff.Companion.LONG_OUTAGE_MS
import com.mobilerun.portal.service.ReverseReconnectBackoff.Companion.STABLE_CONNECTION_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverseReconnectBackoffTest {
    private fun backoff(random: () -> Double = { 0.5 }) = ReverseReconnectBackoff(random = random)

    private fun ReverseReconnectBackoff.delayAt(nowMs: Long): Long = nextRetry(nowMs).delayMs

    /** Drives the backoff into its exponential phase with [attempts] exponential steps taken. */
    private fun ReverseReconnectBackoff.intoExponentialPhase(attempts: Int) {
        delayAt(0L)
        repeat(attempts) { delayAt(FAST_RETRY_WINDOW_MS + it) }
    }

    @Test
    fun retriesAtBaseCadenceInsideFastWindow() {
        val backoff = backoff(random = { 0.9999 })
        var nowMs = 0L
        val attemptTimes = mutableListOf<Long>()

        while (nowMs < FAST_RETRY_WINDOW_MS) {
            nowMs += backoff.delayAt(nowMs)
            attemptTimes += nowMs
        }

        val gaps = attemptTimes.zipWithNext { a, b -> b - a }
        assertTrue(gaps.all { it <= 3_600L })
        assertTrue(attemptTimes.any { it in 26_000L until 30_000L })
    }

    @Test
    fun fastWindowLastsSixtySeconds() {
        val backoff = backoff()
        backoff.delayAt(0L)

        assertEquals(3_000L, backoff.delayAt(59_000L))
        assertEquals(3_000L, backoff.delayAt(FAST_RETRY_WINDOW_MS))
        assertEquals(6_000L, backoff.delayAt(FAST_RETRY_WINDOW_MS + 1L))
    }

    @Test
    fun backsOffExponentiallyAfterFastWindowAndCaps() {
        val backoff = backoff()
        assertEquals(3_000L, backoff.delayAt(0L))

        val delays = (0 until 7).map { backoff.delayAt(FAST_RETRY_WINDOW_MS + it) }

        assertEquals(listOf(3_000L, 6_000L, 12_000L, 24_000L, 30_000L, 30_000L, 30_000L), delays)
    }

    @Test
    fun jitterStaysWithinTwentyPercent() {
        assertTrue(backoff(random = { 0.0 }).delayAt(1_000L) in 2_399L..2_400L)
        assertTrue(backoff(random = { 0.9999 }).delayAt(1_000L) in 3_599L..3_600L)
    }

    @Test
    fun manyAttemptsDoNotOverflow() {
        val backoff = backoff()
        backoff.intoExponentialPhase(attempts = 100)

        assertEquals(30_000L, backoff.delayAt(FAST_RETRY_WINDOW_MS))
    }

    @Test
    fun stableConnectionResetsOutageAndAttempts() {
        val backoff = backoff()
        backoff.intoExponentialPhase(attempts = 2)
        val connectedAtMs = LONG_OUTAGE_MS - STABLE_CONNECTION_MS

        backoff.onConnected(connectedAtMs)

        assertEquals(
            ReverseReconnectStep(delayMs = 3_000L, releaseMedia = false),
            backoff.nextRetry(LONG_OUTAGE_MS),
        )
        assertEquals(3_000L, backoff.delayAt(LONG_OUTAGE_MS + FAST_RETRY_WINDOW_MS))
        assertEquals(6_000L, backoff.delayAt(LONG_OUTAGE_MS + FAST_RETRY_WINDOW_MS + 1L))
    }

    @Test
    fun acceptThenCloseKeepsBackingOff() {
        val backoff = backoff()
        backoff.intoExponentialPhase(attempts = 2)

        backoff.onConnected(100_000L)

        assertEquals(12_000L, backoff.delayAt(102_000L))
    }

    @Test
    fun droppedRealConnectionReArmsFastWindow() {
        val backoff = backoff()
        backoff.intoExponentialPhase(attempts = 2)

        backoff.onConnected(100_000L)
        val droppedAtMs = 100_000L + 15_000L

        assertEquals(3_000L, backoff.delayAt(droppedAtMs))
        assertEquals(3_000L, backoff.delayAt(droppedAtMs + 59_000L))
        assertEquals(12_000L, backoff.delayAt(droppedAtMs + FAST_RETRY_WINDOW_MS))
    }

    @Test
    fun acceptThenCloseLoopReachesCapAndReleasesMediaOnce() {
        val backoff = backoff()
        var nowMs = 0L
        var step = backoff.nextRetry(nowMs)
        var releases = 0

        while (nowMs < LONG_OUTAGE_MS * 2) {
            nowMs += step.delayMs
            backoff.onConnected(nowMs)
            nowMs += 2_000L
            step = backoff.nextRetry(nowMs)
            if (step.releaseMedia) releases++
        }

        assertEquals(30_000L, step.delayMs)
        assertEquals(1, releases)
    }

    @Test
    fun longOutageReleasesMediaOnceAndKeepsRetryingAtCap() {
        val backoff = backoff()
        backoff.intoExponentialPhase(attempts = 10)

        assertEquals(
            ReverseReconnectStep(delayMs = 30_000L, releaseMedia = true),
            backoff.nextRetry(LONG_OUTAGE_MS),
        )
        assertEquals(
            ReverseReconnectStep(delayMs = 30_000L, releaseMedia = false),
            backoff.nextRetry(LONG_OUTAGE_MS * 4),
        )
    }

    @Test
    fun mediaReleaseRearmsAfterStableConnection() {
        val backoff = backoff()
        backoff.delayAt(0L)
        assertTrue(backoff.nextRetry(LONG_OUTAGE_MS).releaseMedia)

        backoff.onConnected(LONG_OUTAGE_MS + 1_000L)
        val nextOutageMs = LONG_OUTAGE_MS + 1_000L + STABLE_CONNECTION_MS
        backoff.delayAt(nextOutageMs)

        assertTrue(backoff.nextRetry(nextOutageMs + LONG_OUTAGE_MS).releaseMedia)
    }

    @Test
    fun resetClearsOutageAndAttempts() {
        val backoff = backoff()
        backoff.intoExponentialPhase(attempts = 2)

        backoff.reset()
        val restartMs = LONG_OUTAGE_MS + 5_000L

        assertEquals(
            ReverseReconnectStep(delayMs = 3_000L, releaseMedia = false),
            backoff.nextRetry(restartMs),
        )
        assertEquals(3_000L, backoff.delayAt(restartMs + FAST_RETRY_WINDOW_MS))
        assertEquals(6_000L, backoff.delayAt(restartMs + FAST_RETRY_WINDOW_MS + 1L))
    }
}
