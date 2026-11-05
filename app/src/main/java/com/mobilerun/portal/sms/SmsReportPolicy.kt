package com.mobilerun.portal.sms

/** A queued sms/received report. The body is never stored: it is read from the provider when sent. */
data class SmsPendingReport(
    val msgId: String,
    val providerId: Long,
    val direction: String,
    val attempts: Int = 0,
    val unconfirmed: Int = 0,
    val nextAttemptAt: Long,
    val firstQueuedAt: Long,
    val lastError: String? = null,
)

/** Retry bookkeeping of a relay send's unreported [state], saved with the record so a restart keeps it. */
data class SmsSendStateRetry(
    val state: SmsSendState,
    val attempts: Int,
    val unconfirmed: Int,
    val nextAttemptAt: Long,
    val firstQueuedAt: Long,
)

/** A relay send whose current state is unreported, with the retry bookkeeping saved for it. */
data class SmsUnreportedSend(val record: SmsSendRecord, val retry: SmsSendStateRetry?)

/** How a device -> relay call ended. */
sealed class SmsReportOutcome {
    object Ok : SmsReportOutcome()
    data class Error(val message: String) : SmsReportOutcome()
    object Timeout : SmsReportOutcome()
    object Closed : SmsReportOutcome()
}

sealed class SmsReportDecision {
    /** Answered {}. */
    object Done : SmsReportDecision()

    /** The relay rejected the message itself; sending it again cannot help. */
    data class Dropped(val error: String) : SmsReportDecision()

    /** Errors for 24 h. */
    object GaveUp : SmsReportDecision()

    /** No reply after the extra attempts; the relay probably forwarded without answering. */
    object DoneUnconfirmed : SmsReportDecision()

    data class Retry(
        val attempts: Int,
        val unconfirmed: Int,
        val nextAttemptAt: Long,
        val lastError: String,
    ) : SmsReportDecision()

    /** The socket closed: send again on the next open, no attempt counted. */
    object Requeue : SmsReportDecision()

    val finished: Boolean get() = this !is Retry && this !is Requeue
}

/**
 * Delivery rules for sms/received and sms/sendState. Today's relays may forward without answering,
 * so a missing reply is retried only twice and then treated as done.
 */
object SmsReportPolicy {
    val BACKOFF_MS = longArrayOf(2_000L, 5_000L, 15_000L, 30_000L, 60_000L)
    const val GIVE_UP_MS = 24L * 60L * 60L * 1000L
    const val MAX_UNCONFIRMED_RETRIES = 2
    private const val MAX_ERROR_CHARS = 120
    private val PERMANENT = Regex("^sms: (invalid_params|unsupported_param)(:|$)")

    fun decide(
        attempts: Int,
        unconfirmed: Int,
        firstQueuedAt: Long,
        outcome: SmsReportOutcome,
        now: Long,
    ): SmsReportDecision = when (outcome) {
        SmsReportOutcome.Ok -> SmsReportDecision.Done
        SmsReportOutcome.Closed -> SmsReportDecision.Requeue
        is SmsReportOutcome.Error -> {
            val error = outcome.message.trim().take(MAX_ERROR_CHARS)
            when {
                PERMANENT.containsMatchIn(error) -> SmsReportDecision.Dropped(error)
                now - firstQueuedAt >= GIVE_UP_MS -> SmsReportDecision.GaveUp
                else -> retry(attempts + 1, unconfirmed, now, error)
            }
        }
        SmsReportOutcome.Timeout ->
            if (unconfirmed >= MAX_UNCONFIRMED_RETRIES) {
                SmsReportDecision.DoneUnconfirmed
            } else {
                retry(attempts, unconfirmed + 1, now, "timeout")
            }
    }

    fun backoffMs(failures: Int): Long = BACKOFF_MS[(failures - 1).coerceIn(0, BACKOFF_MS.lastIndex)]

    private fun retry(attempts: Int, unconfirmed: Int, now: Long, error: String) =
        SmsReportDecision.Retry(attempts, unconfirmed, now + backoffMs(attempts + unconfirmed), error)

    fun apply(report: SmsPendingReport, outcome: SmsReportOutcome, now: Long): SmsReportDecision =
        decide(report.attempts, report.unconfirmed, report.firstQueuedAt, outcome, now)
}

/** Whether a relay send record has a state the cloud has not been told about. */
fun SmsSendRecord.needsSendStateReport(): Boolean {
    if (origin != SmsSendRecord.ORIGIN_RELAY) return false
    if (lastReportedState == state.wire) return false
    // The sms/send reply already carried "pending".
    return !(state == SmsSendState.PENDING && lastReportedState == null)
}
