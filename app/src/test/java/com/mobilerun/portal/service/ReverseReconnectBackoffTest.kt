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
    ) = ReverseReconnectBackoff(retryBudgetMs = retryBudgetMs, random = random)

    private fun ReverseReconnectBackoff.retryAt(nowMs: Long, network: Boolean = true) =
        nextRetry(nowMs, network)

    @Test
    fun retriesThreeTimesWithinTheBudgetThenGivesUp() {
        val backoff = backoff()

        assertEquals(1_000L, backoff.retryAt(0L))
        assertEquals(2_000L, backoff.retryAt(1_000L))
        assertEquals(4_000L, backoff.retryAt(3_000L))
        assertNull(backoff.retryAt(7_000L))
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

        assertEquals(1_000L, backoff.retryAt(droppedAtMs))
        assertEquals(2_000L, backoff.retryAt(droppedAtMs + 1_000L))
        assertEquals(4_000L, backoff.retryAt(droppedAtMs + 3_000L))
        assertNull(backoff.retryAt(droppedAtMs + 7_000L))
    }

    @Test
    fun shortConnectionKeepsSpendingTheSameBudget() {
        val backoff = backoff()
        assertEquals(1_000L, backoff.retryAt(0L))

        backoff.onConnected(1_000L)

        assertEquals(2_000L, backoff.retryAt(3_000L))
        assertEquals(4_000L, backoff.retryAt(5_000L))
        assertNull(backoff.retryAt(9_000L))
    }

    @Test
    fun afterRestartWaitsForNetworkBeforeSpendingTheBudget() {
        val backoff = backoff()
        backoff.waitForNetwork()

        assertEquals(5_000L, backoff.retryAt(0L, network = false))
        assertEquals(5_000L, backoff.retryAt(120_000L, network = false))

        assertEquals(1_000L, backoff.retryAt(125_000L))
        assertEquals(2_000L, backoff.retryAt(126_000L))
        assertEquals(4_000L, backoff.retryAt(128_000L))
        assertNull(backoff.retryAt(132_000L))
    }

    @Test
    fun networkDropDuringTheRestartWaitStartsTheBudgetAgain() {
        val backoff = backoff()
        backoff.waitForNetwork()
        assertEquals(1_000L, backoff.retryAt(0L))

        assertEquals(5_000L, backoff.retryAt(1_000L, network = false))
        assertEquals(5_000L, backoff.retryAt(20_000L, network = false))

        assertEquals(1_000L, backoff.retryAt(25_000L))
        assertEquals(2_000L, backoff.retryAt(26_000L))
    }

    @Test
    fun connectingEndsTheNetworkWait() {
        val backoff = backoff()
        backoff.waitForNetwork()

        backoff.onConnected(0L)

        assertEquals(1_000L, backoff.retryAt(1_000L, network = false))
        assertNull(backoff.retryAt(11_000L, network = false))
    }

    @Test
    fun resetClearsTheBudgetAndTheNetworkWait() {
        val backoff = backoff()
        backoff.waitForNetwork()
        backoff.retryAt(0L)
        backoff.retryAt(1_000L)
        backoff.retryAt(3_000L)
        assertNull(backoff.retryAt(7_000L))

        backoff.reset()

        assertEquals(1_000L, backoff.retryAt(60_000L, network = false))
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
