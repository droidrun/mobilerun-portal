package com.mobilerun.portal.sms

object SmsStorePolicy {
    const val MAX_RECORDS = 1000
    const val RECORD_TTL_MS = 7L * 24L * 60L * 60L * 1000L
    const val RESET_KEEP_MS = 24L * 60L * 60L * 1000L
    const val RECOVERY_GRACE_MS = 60_000L
    /** How long the cap keeps a record whose current state is unreported; past it the relay is stalling. */
    const val UNREPORTED_KEEP_MS = 10L * 60L * 1000L
    const val MAX_OWN_SEND_IDS = 2000
    const val MAX_REPORTED_IDS = 2000
    /** Queued reports are never dropped over it: scans stop queueing until the queue drains. */
    const val MAX_PENDING_REPORTS = 2000

    /**
     * [awaitingDelivery]: every part sent, only delivery reports outstanding. [unreported]: its
     * current state, reached at [updatedAt], still has to be reported.
     */
    data class EvictionCandidate(
        val id: String,
        val terminal: Boolean,
        val createdAt: Long,
        val awaitingDelivery: Boolean = false,
        val unreported: Boolean = false,
        val updatedAt: Long = createdAt,
    )

    /**
     * Records older than the TTL, then the oldest records over the cap: terminal ones, then sends
     * only awaiting delivery reports, reported ones first. Other sends and records unreported for less
     * than [UNREPORTED_KEEP_MS] are never evicted over the cap: null when they alone exceed it.
     */
    fun evictions(
        records: List<EvictionCandidate>,
        now: Long,
        maxRecords: Int = MAX_RECORDS,
        ttlMs: Long = RECORD_TTL_MS,
    ): List<String>? {
        val (expired, kept) = records.partition { now - it.createdAt > ttlMs }
        val excess = kept.size - maxRecords
        if (excess <= 0) return expired.map { it.id }
        val overCap = kept
            .filter { (it.terminal || it.awaitingDelivery) && !(it.unreported && now - it.updatedAt < UNREPORTED_KEEP_MS) }
            .sortedWith(compareBy<EvictionCandidate>({ it.unreported }, { !it.terminal }, { it.createdAt }))
            .take(excess)
        if (overCap.size < excess) return null
        return expired.map { it.id } + overCap.map { it.id }
    }
}

/**
 * Store work tried again after a failure: [MAX_ATTEMPTS] tries in all, [RETRY_DELAY_MS] apart. Work
 * run [untilDone] keeps trying after that, [SLOW_RETRY_DELAY_MS] apart.
 */
class SmsStoreRetry(
    private val schedule: (delayMs: Long, task: () -> Unit) -> Unit,
    private val log: (String) -> Unit = {},
) {
    fun run(name: String, untilDone: Boolean = false, block: () -> Unit) = attempt(name, 1, untilDone, block)

    private fun attempt(name: String, attempt: Int, untilDone: Boolean, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            if (attempt >= MAX_ATTEMPTS && !untilDone) {
                log("$name failed (${e.javaClass.simpleName}), giving up")
                return
            }
            log("$name failed (${e.javaClass.simpleName}), retrying")
            val delay = if (attempt >= MAX_ATTEMPTS) SLOW_RETRY_DELAY_MS else RETRY_DELAY_MS
            try {
                schedule(delay) { attempt(name, minOf(attempt + 1, MAX_ATTEMPTS), untilDone, block) }
            } catch (_: Exception) {
                log("$name retry not scheduled")
            }
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 5
        const val RETRY_DELAY_MS = 30_000L
        const val SLOW_RETRY_DELAY_MS = 5L * 60L * 1000L
    }
}

/**
 * Discards of prepared records whose insert was reported as failed but may still land. A failed one
 * runs again, and a lookup of its id runs it first, so the record never answers a retried sms/send
 * as pending and then vanishes. Store thread only.
 */
class SmsPreparedDiscards(
    private val retry: SmsStoreRetry,
    private val delete: (id: String, callbackToken: String) -> Unit,
) {
    private val pending = HashMap<String, String>()

    fun discard(id: String, callbackToken: String) {
        pending[id] = callbackToken
        retry.run("discard") { settle(id) }
    }

    /** [lookup] once the pending discard of [id], if any, ran; throws while that keeps failing. */
    fun <T> find(id: String, lookup: () -> T): T {
        settle(id)
        return lookup()
    }

    private fun settle(id: String) {
        val callbackToken = pending[id] ?: return
        delete(id, callbackToken)
        pending.remove(id)
    }
}

/**
 * The Portal's own sends: provider _id -> when it was recorded, and the date its row had then. The
 * sms table reuses _id after deletes, so a row matches only with that date; a marker without one
 * (unread or from before row dates) matches when the row is not newer than the record.
 */
class SmsOwnSends(
    private val recordedAt: Map<Long, Long> = emptyMap(),
    private val rowDates: Map<Long, Long> = emptyMap(),
) {
    operator fun contains(row: SmsProviderRow): Boolean {
        rowDates[row.id]?.let { return row.date == it }
        val at = recordedAt[row.id] ?: return false
        return row.date <= at + CLOCK_SLACK_MS
    }

    fun plus(providerId: Long, at: Long, rowDate: Long? = null): SmsOwnSends = SmsOwnSends(
        recordedAt + (providerId to at),
        if (rowDate != null) rowDates + (providerId to rowDate) else rowDates - providerId,
    )

    companion object {
        /** Clock adjustments between the send and its callback. */
        const val CLOCK_SLACK_MS = 60_000L
    }
}

sealed class SmsRecoveryDecision {
    object None : SmsRecoveryDecision()
    data class Wait(val atMs: Long) : SmsRecoveryDecision()
    /** [recheckAt]: the record is still unresolved and is checked again then. */
    data class Resolve(val record: SmsSendRecord, val providerId: Long?, val recheckAt: Long? = null) : SmsRecoveryDecision()
}

/** Restart rules for relay sends an earlier process left prepared or dispatched. */
object SmsSendRecovery {
    private const val GENERIC_FAILURE = "RESULT_ERROR_GENERIC_FAILURE"
    const val PAGE_SIZE = 200

    /**
     * Left by an earlier process: prepared (no wall-clock comparison, which a clock set back would
     * defeat), or dispatched, whose callback write may have been lost with it, up to the record TTL.
     */
    fun isCandidate(record: SmsSendRecord, instanceId: String, now: Long): Boolean =
        record.instanceId != instanceId && when (record.phase) {
            SmsSendPhase.PREPARED -> true
            SmsSendPhase.DISPATCHED -> now - record.createdAt <= SmsStorePolicy.RECORD_TTL_MS
            SmsSendPhase.TERMINAL -> false
        }

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

    /** Uptime since the insert, or null when that was in an earlier boot or predates createdElapsed. */
    private fun sinceInsertMs(record: SmsSendRecord, elapsedNow: Long): Long? =
        if (record.createdElapsed in 1..elapsedNow) elapsedNow - record.createdElapsed else null

    /** The earliest date the record's row can carry: the insert by either clock, if it was set back since. */
    fun evidenceFrom(record: SmsSendRecord, now: Long, elapsedNow: Long): Long =
        sinceInsertMs(record, elapsedNow)?.let { minOf(record.createdAt, now - it) } ?: record.createdAt

    /**
     * A prepared record from an earlier process is checked against the provider once it is a minute
     * old, by uptime when inserted this boot, else by the clock, and at least [sinceOpenMs]: a
     * Portal-created row to the same address, dated from [evidenceFrom] on, with the same body hash,
     * resolves it; no such row fails it with RESULT_INTERRUPTED. A dispatched one is checked once this
     * process is a minute old, after callbacks queued at its start, against its own row once a callback
     * named it, and never fails for want of a row: SmsManager took it. Null [rows] (READ_SMS missing)
     * prove nothing, and an unresolved record is checked again later.
     */
    fun decide(
        record: SmsSendRecord,
        now: Long,
        elapsedNow: Long,
        instanceId: String,
        sinceOpenMs: Long,
        rows: List<SmsProviderRow>?,
        ownSends: SmsOwnSends,
        packageName: String,
    ): SmsRecoveryDecision {
        if (!isCandidate(record, instanceId, now)) return SmsRecoveryDecision.None
        val dispatched = record.phase == SmsSendPhase.DISPATCHED
        // Written before this process started, so at least sinceOpenMs old.
        val age = maxOf(sinceInsertMs(record, elapsedNow) ?: (now - record.createdAt), sinceOpenMs)
        // A dispatched record's callback may be what started this process: its exact result lands first.
        val wait = SmsStorePolicy.RECOVERY_GRACE_MS - if (dispatched) sinceOpenMs else age
        if (wait > 0) return SmsRecoveryDecision.Wait(now + wait)
        val recheckAt = now + SmsStoreRetry.SLOW_RETRY_DELAY_MS
        if (rows == null) return SmsRecoveryDecision.Wait(recheckAt)
        val target = SmsPhoneNumbers.compactAddress(record.to)
        val from = evidenceFrom(record, now, elapsedNow)
        // A callback lists the record's own row among ownSends.
        val ownRow = SmsSendCallbacks.providerIdFromUri(record.messageUri)
        val match = rows
            .filter {
                it.creator == packageName &&
                    (if (ownRow != null) it.id == ownRow else it !in ownSends) &&
                    it.date >= from &&
                    it.body != null &&
                    SmsPhoneNumbers.compactAddress(it.address) == target &&
                    SmsHashes.bodyHash(it.body) == record.bodyHash
            }
            .minByOrNull { it.date }
        val resolved = when (match?.type) {
            SmsProviderRow.TYPE_SENT -> fromSentRow(record, match?.status, now)
            SmsProviderRow.TYPE_FAILED -> record.withFailure(GENERIC_FAILURE, "Generic SMS send failure", now)
            SmsProviderRow.TYPE_OUTBOX, SmsProviderRow.TYPE_QUEUED -> record.withDispatched(now)
            else -> null
        }
        if (match != null && resolved != null) {
            val claimed = if (resolved.messageUri != null) resolved else resolved.copy(messageUri = "content://sms/${match.id}")
            val again = recheckAt.takeIf { isCandidate(claimed, instanceId, now) }
            return SmsRecoveryDecision.Resolve(claimed, match.id, again)
        }
        if (dispatched) return SmsRecoveryDecision.Wait(recheckAt)
        val failed = record.withFailure(SmsSendCallbacks.RESULT_INTERRUPTED, "Interrupted before dispatch", now)
            ?: return SmsRecoveryDecision.None
        return SmsRecoveryDecision.Resolve(failed, null)
    }

    /** Every part sent; a requested delivery report settles from the row's status, which holds the latest part's. */
    private fun fromSentRow(record: SmsSendRecord, rowStatus: Int?, now: Long): SmsSendRecord? {
        val sent = record.withAllSent(now) ?: record
        val status = rowStatus?.takeIf { sent.deliveryReport && it >= 0 }
        val settled = when (status?.let(::smsDeliveryOutcome)) {
            SmsDeliveryOutcome.PERMANENT_FAILURE ->
                sent.withFailure(SmsSendCallbacks.DELIVERY_FAILED, "Delivery failed (status=$status)", now)
            // Another part's report may still be outstanding.
            SmsDeliveryOutcome.DELIVERED -> if (sent.parts == 1) sent.withPartDelivered(0, now) else null
            else -> null
        }
        return (settled ?: sent).takeIf { it !== record }
    }
}
