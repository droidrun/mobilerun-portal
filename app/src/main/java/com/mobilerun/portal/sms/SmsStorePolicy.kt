package com.mobilerun.portal.sms

object SmsStorePolicy {
    const val MAX_RECORDS = 1000
    const val RECORD_TTL_MS = 7L * 24L * 60L * 60L * 1000L
    const val RESET_KEEP_MS = 24L * 60L * 60L * 1000L
    const val RECOVERY_GRACE_MS = 60_000L
    const val MAX_OWN_SEND_IDS = 2000
    const val MAX_REPORTED_IDS = 2000
    const val MAX_PENDING_REPORTS = 2000

    data class EvictionCandidate(val id: String, val terminal: Boolean, val createdAt: Long)

    /** Records older than the TTL, then the oldest records over the cap, terminal ones first. */
    fun evictions(
        records: List<EvictionCandidate>,
        now: Long,
        maxRecords: Int = MAX_RECORDS,
        ttlMs: Long = RECORD_TTL_MS,
    ): List<String> {
        val (expired, kept) = records.partition { now - it.createdAt > ttlMs }
        val excess = kept.size - maxRecords
        if (excess <= 0) return expired.map { it.id }
        val overCap = kept
            .sortedWith(compareBy<EvictionCandidate>({ !it.terminal }, { it.createdAt }))
            .take(excess)
        return expired.map { it.id } + overCap.map { it.id }
    }
}

/** Store work tried again after a failure: [MAX_ATTEMPTS] tries in all, [RETRY_DELAY_MS] apart. */
class SmsStoreRetry(
    private val schedule: (delayMs: Long, task: () -> Unit) -> Unit,
    private val log: (String) -> Unit = {},
) {
    fun run(name: String, block: () -> Unit) = attempt(name, 1, block)

    private fun attempt(name: String, attempt: Int, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            if (attempt >= MAX_ATTEMPTS) {
                log("$name failed (${e.javaClass.simpleName}), giving up")
                return
            }
            log("$name failed (${e.javaClass.simpleName}), retrying")
            try {
                schedule(RETRY_DELAY_MS) { attempt(name, attempt + 1, block) }
            } catch (_: Exception) {
                log("$name retry not scheduled")
            }
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 5
        const val RETRY_DELAY_MS = 30_000L
    }
}

/**
 * The Portal's own sends: provider _id -> when it was recorded. The sms table reuses _id after
 * deletes, so a row matches only when it is not newer than the record.
 */
class SmsOwnSends(private val recordedAt: Map<Long, Long> = emptyMap()) {
    operator fun contains(row: SmsProviderRow): Boolean {
        val at = recordedAt[row.id] ?: return false
        return row.date <= at + CLOCK_SLACK_MS
    }

    fun plus(providerId: Long, at: Long): SmsOwnSends = SmsOwnSends(recordedAt + (providerId to at))

    companion object {
        /** Clock adjustments between the send and its callback. */
        const val CLOCK_SLACK_MS = 60_000L
    }
}

sealed class SmsRecoveryDecision {
    object None : SmsRecoveryDecision()
    data class Wait(val atMs: Long) : SmsRecoveryDecision()
    data class Resolve(val record: SmsSendRecord, val providerId: Long?) : SmsRecoveryDecision()
}

/** Restart rules for relay sends that were persisted as prepared but never marked dispatched. */
object SmsSendRecovery {
    private const val GENERIC_FAILURE = "RESULT_ERROR_GENERIC_FAILURE"
    const val PAGE_SIZE = 200

    fun isCandidate(record: SmsSendRecord, processStartedAt: Long): Boolean =
        record.phase == SmsSendPhase.PREPARED && record.createdAt < processStartedAt

    /**
     * Portal-created rows with the address and body hash of one of [candidates], read page by page
     * from the oldest candidate on until the rows run out.
     */
    fun evidence(
        candidates: List<SmsSendRecord>,
        pageSize: Int = PAGE_SIZE,
        page: (after: SmsWatermark?, limit: Int) -> List<SmsProviderRow>,
    ): List<SmsProviderRow> {
        val wanted = candidates.mapTo(HashSet()) { SmsPhoneNumbers.compactAddress(it.to) to it.bodyHash }
        val addresses = wanted.mapTo(HashSet()) { it.first }
        val found = ArrayList<SmsProviderRow>()
        var after: SmsWatermark? = null
        while (true) {
            val rows = page(after, pageSize)
            rows.filterTo(found) { row ->
                val body = row.body ?: return@filterTo false
                val address = SmsPhoneNumbers.compactAddress(row.address)
                address in addresses && (address to SmsHashes.bodyHash(body)) in wanted
            }
            val last = rows.lastOrNull()
            if (last == null || rows.size < pageSize) return found
            after = SmsWatermark(last.id, last.date)
        }
    }

    /**
     * A prepared record from an earlier process is checked against the provider once it is a minute
     * old: a Portal-created row to the same address, dated at or after the record, with the same body
     * hash, resolves it; no such row fails it with RESULT_INTERRUPTED. Dispatched records never time out.
     */
    fun decide(
        record: SmsSendRecord,
        now: Long,
        processStartedAt: Long,
        rows: List<SmsProviderRow>,
        ownSends: SmsOwnSends,
        packageName: String,
    ): SmsRecoveryDecision {
        if (!isCandidate(record, processStartedAt)) return SmsRecoveryDecision.None
        if (now - record.createdAt < SmsStorePolicy.RECOVERY_GRACE_MS) {
            return SmsRecoveryDecision.Wait(record.createdAt + SmsStorePolicy.RECOVERY_GRACE_MS)
        }
        val target = SmsPhoneNumbers.compactAddress(record.to)
        val match = rows
            .filter {
                it.creator == packageName &&
                    it !in ownSends &&
                    it.date >= record.createdAt &&
                    it.body != null &&
                    SmsPhoneNumbers.compactAddress(it.address) == target &&
                    SmsHashes.bodyHash(it.body) == record.bodyHash
            }
            .minByOrNull { it.date }
        val resolved = when (match?.type) {
            SmsProviderRow.TYPE_SENT -> record.withAllSent(now)
            SmsProviderRow.TYPE_FAILED -> record.withFailure(GENERIC_FAILURE, "Generic SMS send failure", now)
            SmsProviderRow.TYPE_OUTBOX, SmsProviderRow.TYPE_QUEUED -> record.withDispatched(now)
            else -> null
        }
        if (resolved != null) return SmsRecoveryDecision.Resolve(resolved, match?.id)
        val failed = record.withFailure(SmsSendCallbacks.RESULT_INTERRUPTED, "Interrupted before dispatch", now)
            ?: return SmsRecoveryDecision.None
        return SmsRecoveryDecision.Resolve(failed, null)
    }
}
