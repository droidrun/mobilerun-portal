package com.mobilerun.portal.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsReportPolicyTest {
    private fun report(attempts: Int = 0, unconfirmed: Int = 0, firstQueuedAt: Long = 0L) =
        SmsPendingReport("1-10", 1L, "in", attempts, unconfirmed, nextAttemptAt = 0L, firstQueuedAt = firstQueuedAt)

    private val forward = SmsReportOutcome.Error("sms: device_side_failure: forward failed")

    @Test
    fun ok_isDone() {
        assertEquals(SmsReportDecision.Done, SmsReportPolicy.apply(report(), SmsReportOutcome.Ok, 1_000L))
    }

    @Test
    fun invalidParamsAndUnsupportedParam_areDropped() {
        listOf("sms: invalid_params: rejected", "sms: invalid_params", "sms: unsupported_param: junk").forEach {
            val decision = SmsReportPolicy.apply(report(), SmsReportOutcome.Error(it), 1_000L)
            assertEquals(SmsReportDecision.Dropped(it), decision)
            assertTrue(decision.finished)
        }
    }

    @Test
    fun otherErrors_backOff_2_5_15_30_then60() {
        var current = report()
        var now = 0L
        val delays = mutableListOf<Long>()
        repeat(7) {
            val decision = SmsReportPolicy.apply(current, forward, now) as SmsReportDecision.Retry
            delays += decision.nextAttemptAt - now
            assertEquals("sms: device_side_failure: forward failed", decision.lastError)
            current = current.copy(attempts = decision.attempts, unconfirmed = decision.unconfirmed)
            now = decision.nextAttemptAt
        }
        assertEquals(listOf(2_000L, 5_000L, 15_000L, 30_000L, 60_000L, 60_000L, 60_000L), delays)
        assertEquals(7, current.attempts)
        assertEquals(0, current.unconfirmed)
    }

    @Test
    fun unknownErrorsLikeAnOldRelay_areRetriedNotDropped() {
        val decision = SmsReportPolicy.apply(report(), SmsReportOutcome.Error("Unknown method"), 0L)
        assertTrue(decision is SmsReportDecision.Retry)
        assertFalse(decision.finished)
    }

    @Test
    fun errors_giveUpAfter24h() {
        val day = SmsReportPolicy.GIVE_UP_MS
        assertTrue(SmsReportPolicy.apply(report(attempts = 3), forward, day - 1) is SmsReportDecision.Retry)
        assertEquals(SmsReportDecision.GaveUp, SmsReportPolicy.apply(report(attempts = 3), forward, day))
    }

    @Test
    fun timeout_retriesTwiceMore_thenDoneUnconfirmed() {
        val first = SmsReportPolicy.apply(report(), SmsReportOutcome.Timeout, 0L) as SmsReportDecision.Retry
        assertEquals(1, first.unconfirmed)
        assertEquals(0, first.attempts)
        assertEquals(2_000L, first.nextAttemptAt)

        val second = SmsReportPolicy.apply(report(unconfirmed = 1), SmsReportOutcome.Timeout, 0L) as SmsReportDecision.Retry
        assertEquals(2, second.unconfirmed)
        assertEquals(5_000L, second.nextAttemptAt)

        assertEquals(
            SmsReportDecision.DoneUnconfirmed,
            SmsReportPolicy.apply(report(unconfirmed = 2), SmsReportOutcome.Timeout, 0L),
        )
    }

    @Test
    fun timeout_isNotSubjectToThe24hGiveUp_butStillCapped() {
        val old = report(unconfirmed = 2, firstQueuedAt = 0L)
        assertEquals(
            SmsReportDecision.DoneUnconfirmed,
            SmsReportPolicy.apply(old, SmsReportOutcome.Timeout, SmsReportPolicy.GIVE_UP_MS * 2),
        )
    }

    @Test
    fun closed_requeuesWithoutCountingAnAttempt() {
        val decision = SmsReportPolicy.apply(report(attempts = 2, unconfirmed = 1), SmsReportOutcome.Closed, 0L)
        assertEquals(SmsReportDecision.Requeue, decision)
        assertFalse(decision.finished)
    }

    @Test
    fun errorAfterTimeout_backoffCountsBothFailures() {
        val decision = SmsReportPolicy.apply(report(unconfirmed = 1), forward, 0L) as SmsReportDecision.Retry
        assertEquals(1, decision.attempts)
        assertEquals(1, decision.unconfirmed)
        assertEquals(5_000L, decision.nextAttemptAt)
    }

    @Test
    fun errorText_isTrimmedForStorage() {
        val long = "sms: device_side_failure: " + "x".repeat(500)
        val decision = SmsReportPolicy.apply(report(), SmsReportOutcome.Error(long), 0L) as SmsReportDecision.Retry
        assertEquals(120, decision.lastError.length)
    }

    @Test
    fun callTimeout_outlastsTheRelayForwardBudget() {
        // devices-api answers "forward failed" only after its 30 s smsForwardTimeout.
        assertTrue(SmsReporterCore.CALL_TIMEOUT_MS > 30_000L)
    }

    @Test
    fun needsSendStateReport() {
        assertFalse(relayRecord("a", SmsSendState.PENDING).needsSendStateReport())
        assertTrue(relayRecord("a", SmsSendState.SENT).needsSendStateReport())
        assertFalse(relayRecord("a", SmsSendState.SENT, lastReportedState = "sent").needsSendStateReport())
        assertTrue(relayRecord("a", SmsSendState.DELIVERED, lastReportedState = "sent").needsSendStateReport())
        assertFalse(relayRecord("a", SmsSendState.SENT, origin = "local").needsSendStateReport())
    }
}
