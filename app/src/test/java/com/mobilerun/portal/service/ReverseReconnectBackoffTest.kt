package com.mobilerun.portal.service

import com.mobilerun.portal.service.ReverseReconnectBackoff.Companion.HEALTHY_CONNECTION_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverseReconnectBackoffTest {
    private fun backoff(
        random: () -> Double = { 0.5 },
        retryBudgetMs: Long = ReverseReconnectBackoff.RETRY_BUDGET_MS,
        onNetworkRecheck: () -> Unit = {},
    ) = ReverseReconnectBackoff(
        retryBudgetMs = retryBudgetMs,
        random = random,
        onNetworkRecheck = onNetworkRecheck,
    )

    private fun ReverseReconnectBackoff.retryAt(nowMs: Long, network: Boolean = true) =
        nextRetry(nowMs, network)

    /** Spends the budget from [startMs]; returns when the recheck window started. */
    private fun ReverseReconnectBackoff.spendBudget(startMs: Long): Long {
        assertEquals(1_000L, retryAt(startMs))
        assertEquals(2_000L, retryAt(startMs + 1_000L))
        assertEquals(4_000L, retryAt(startMs + 3_000L))
        assertEquals(5_000L, retryAt(startMs + 7_000L))
        return startMs + 7_000L
    }

    @Test
    fun retriesThreeTimesWithinTheBudgetThenRechecksTheNetworkBeforeGivingUp() {
        var rechecks = 0
        val backoff = backoff(onNetworkRecheck = { rechecks++ })

        val recheckAtMs = backoff.spendBudget(0L)
        assertEquals(1, rechecks)

        assertEquals(5_000L, backoff.retryAt(recheckAtMs + 5_000L))
        assertEquals(5_000L, backoff.retryAt(recheckAtMs + 10_000L))
        assertEquals(5_000L, backoff.retryAt(recheckAtMs + 14_999L))
        assertNull(backoff.retryAt(recheckAtMs + 15_000L))
        assertNull(backoff.retryAt(recheckAtMs + 20_000L))
        assertEquals(1, rechecks)
    }

    @Test
    fun offlineDuringTheRecheckWaitsAndThenStartsAFreshBudget() {
        var rechecks = 0
        val backoff = backoff(onNetworkRecheck = { rechecks++ })
        val recheckAtMs = backoff.spendBudget(0L)

        assertEquals(5_000L, backoff.retryAt(recheckAtMs + 5_000L, network = false))
        assertEquals(5_000L, backoff.retryAt(recheckAtMs + 60_000L, network = false))

        val recheckAgainAtMs = backoff.spendBudget(recheckAtMs + 65_000L)
        assertEquals(2, rechecks)
        assertNull(backoff.retryAt(recheckAgainAtMs + 15_000L))
    }

    @Test
    fun healthyConnectionEndsTheRecheck() {
        val backoff = backoff()
        val recheckAtMs = backoff.spendBudget(0L)

        backoff.onConnected(recheckAtMs + 5_000L)

        backoff.spendBudget(recheckAtMs + 5_000L + HEALTHY_CONNECTION_MS)
    }

    @Test
    fun shortConnectionKeepsTheRecheckWindow() {
        val backoff = backoff()
        val recheckAtMs = backoff.spendBudget(0L)

        backoff.onConnected(recheckAtMs + 5_000L)

        assertEquals(5_000L, backoff.retryAt(recheckAtMs + 6_000L))
        assertNull(backoff.retryAt(recheckAtMs + 15_000L))
    }

    @Test
    fun jitterStaysWithinTwentyPercent() {
        assertEquals(800L, backoff(random = { 0.0 }).retryAt(0L))
        assertTrue(backoff(random = { 0.9999 }).retryAt(0L)!! in 1_199L..1_200L)
    }

    @Test
    fun healthyConnectionStartsAFreshBudget() {
        val backoff = backoff()
        backoff.retryAt(0L)
        backoff.retryAt(1_000L)

        backoff.onConnected(3_000L)
        val droppedAtMs = 3_000L + HEALTHY_CONNECTION_MS

        backoff.spendBudget(droppedAtMs)
    }

    @Test
    fun shortConnectionKeepsSpendingTheSameBudget() {
        val backoff = backoff()
        assertEquals(1_000L, backoff.retryAt(0L))

        backoff.onConnected(1_000L)

        assertEquals(2_000L, backoff.retryAt(3_000L))
        assertEquals(4_000L, backoff.retryAt(5_000L))
        assertEquals(5_000L, backoff.retryAt(9_000L))
    }

    @Test
    fun offlineNeverSpendsTheBudget() {
        val backoff = backoff()

        var nowMs = 0L
        repeat(50) {
            assertEquals(5_000L, backoff.retryAt(nowMs, network = false))
            nowMs += 5_000L
        }

        backoff.spendBudget(nowMs)
    }

    @Test
    fun networkLossDuringAnOutageStartsTheBudgetAgain() {
        val backoff = backoff()
        assertEquals(1_000L, backoff.retryAt(0L))
        assertEquals(2_000L, backoff.retryAt(1_000L))

        assertEquals(5_000L, backoff.retryAt(3_000L, network = false))
        assertEquals(5_000L, backoff.retryAt(60_000L, network = false))

        backoff.spendBudget(65_000L)
    }

    @Test
    fun networkWaitIsJittered() {
        assertEquals(4_000L, backoff(random = { 0.0 }).retryAt(0L, network = false))
        assertTrue(backoff(random = { 0.9999 }).retryAt(0L, network = false)!! in 5_999L..6_000L)
    }

    @Test
    fun resetClearsTheBudgetAndTheRecheck() {
        val backoff = backoff()
        val recheckAtMs = backoff.spendBudget(0L)
        assertNull(backoff.retryAt(recheckAtMs + 15_000L))

        backoff.reset()

        backoff.spendBudget(60_000L)
    }

    @Test
    fun manyFailuresCapAtThirtySeconds() {
        val backoff = backoff(retryBudgetMs = Long.MAX_VALUE / 2)
        var nowMs = 0L
        var delayMs = 0L

        repeat(100) {
            delayMs = backoff.retryAt(nowMs)!!
            nowMs += delayMs
        }

        assertEquals(30_000L, delayMs)
    }
}
