package com.mobilerun.portal.sms

/** The last provider row a direction's scan has looked at. id 0 means "from the start of the table". */
data class SmsWatermark(val id: Long, val date: Long)

/** An outbox, queued or failed row the owner sent, checked again until it reaches the sent box or is gone. */
data class SmsRecheck(val providerId: Long, val firstSeenAt: Long)

/**
 * A date-ordered re-read after the watermark row went missing: the next page starts strictly after
 * (date, id). [top] is the newest row when the re-read started and the watermark once it ends, so
 * rows added meanwhile are read again by _id.
 */
data class SmsRecovery(val date: Long, val id: Long, val top: SmsWatermark)

data class SmsScanState(
    val inbound: SmsWatermark,
    val out: SmsWatermark,
    val coveredFrom: Long,
    /** The newest provider _id when coverage started: rows above it were all added since. */
    val coveredFromId: Long,
    val recheck: List<SmsRecheck> = emptyList(),
    val inboundRecovery: SmsRecovery? = null,
    val outRecovery: SmsRecovery? = null,
    /** Set once a scan finds the clock before coveredFrom: rows above coveredFromId count from here. */
    val newRowsFrom: Long? = null,
)

data class SmsReportCandidate(
    val msgId: String,
    val providerId: Long,
    val direction: String,
)

data class SmsScanResult(
    val state: SmsScanState,
    val reports: List<SmsReportCandidate>,
    /** A page was full: scan again soon. */
    val more: Boolean,
    /** Oldest-seen unsent rechecks dropped over [SmsReportScanner.MAX_RECHECKS]. */
    val evictedRechecks: List<SmsRecheck> = emptyList(),
    /** A report had no room in the queue: the scan stopped before its row, to go on once the queue drains. */
    val queueFull: Boolean = false,
)

/** Provider reads the reporter needs. */
interface SmsReportRowSource {
    /** The newest row by _id (any type), or null when the table is empty. */
    fun latest(): SmsProviderRow?

    fun row(providerId: Long): SmsProviderRow?

    /** The rows with these _ids that still exist, in one read (none for no ids). */
    fun rows(providerIds: Collection<Long>): List<SmsProviderRow>

    /** Rows of [types] with _id > [afterId], by _id ascending. */
    fun rowsAfterId(types: List<Int>, afterId: Long, limit: Int): List<SmsProviderRow>

    /** Rows of [types] with date >= [fromDate] and _id <= [maxId], by (date, _id) ascending. */
    fun rowsFromDate(types: List<Int>, fromDate: Long, maxId: Long, limit: Int): List<SmsProviderRow>

    /** Rows of [types] strictly after ([afterDate], [afterId]) with _id <= [maxId], by (date, _id) ascending. */
    fun rowsAfterDate(types: List<Int>, afterDate: Long, afterId: Long, maxId: Long, limit: Int): List<SmsProviderRow>
}

/**
 * Finds provider rows to report as sms/received. in = inbox rows; out = sent rows the owner sent
 * from another app (never the Portal's own). New rows are those past the watermark; when the
 * watermark row is gone or its date changed (_id reuse), rows dated from
 * min(watermarkDate, now) - 5 min are read in (date, _id) order instead, page by page, then _id
 * reads resume from the newest row when that re-read started. Already reported ids are skipped.
 * Rows dated before coveredFrom (the moment cloud SMS was turned on) are never reported, except
 * rows added since once the clock was set back before it.
 */
class SmsReportScanner(
    private val rows: SmsReportRowSource,
    private val packageName: String,
) {
    fun scan(
        state: SmsScanState,
        ownSends: SmsOwnSends,
        isReported: (String) -> Boolean,
        subscriptionFilter: Int?,
        now: Long,
        room: Int = Int.MAX_VALUE,
    ): SmsScanResult {
        val reports = LinkedHashMap<String, SmsReportCandidate>()
        var queueFull = false
        // A clock set back dates new rows before coveredFrom: rows added since enabling then count
        // from the first scan that finds it. Older _ids keep coveredFrom, so date re-reads never
        // report a row from before enabling.
        val newRowsFrom =
            if (now < state.coveredFrom) minOf(state.newRowsFrom ?: Long.MAX_VALUE, now - SLACK_MS) else state.newRowsFrom
        val newFrom = minOf(state.coveredFrom, newRowsFrom ?: Long.MAX_VALUE)

        fun eligible(row: SmsProviderRow, direction: String): Boolean =
            row.date >= (if (row.id > state.coveredFromId) newFrom else state.coveredFrom) &&
                (subscriptionFilter == null || row.subscriptionId == subscriptionFilter) &&
                (direction == SmsBridgeListParams.DIRECTION_IN || (row.creator != packageName && row !in ownSends))

        /** False when the queue has no room left for it. */
        fun report(row: SmsProviderRow, direction: String): Boolean {
            val msgId = row.messageId
            if (msgId in reports || isReported(msgId)) return true
            if (reports.size >= room) {
                queueFull = true
                return false
            }
            reports[msgId] = SmsReportCandidate(msgId, row.id, direction)
            return true
        }

        val inboundRead = page(state.inbound, state.inboundRecovery, INBOUND_TYPES, MAX_ROWS_PER_SCAN, now)
        val inbound = taken(inboundRead, state.inbound, state.inboundRecovery) { row ->
            row.type != SmsProviderRow.TYPE_INBOX || !eligible(row, SmsBridgeListParams.DIRECTION_IN) ||
                report(row, SmsBridgeListParams.DIRECTION_IN)
        }

        val outRead = page(state.out, state.outRecovery, OUT_TYPES, MAX_ROWS_PER_SCAN - inboundRead.rows.size, now)
        val recheck = LinkedHashMap<Long, SmsRecheck>()
        val awaitingRoom = HashSet<Long>()
        val current = rows.rows(state.recheck.map { it.providerId }).associateBy { it.id }
        for (entry in state.recheck) {
            val row = current[entry.providerId]
            when {
                row == null -> Unit
                // Without room it stays, to be reported by a later scan.
                row.type == SmsProviderRow.TYPE_SENT -> {
                    if (eligible(row, SmsBridgeListParams.DIRECTION_OUT) && !report(row, SmsBridgeListParams.DIRECTION_OUT)) {
                        recheck[entry.providerId] = entry
                        awaitingRoom += entry.providerId
                    }
                }
                row.type in RECHECK_TYPES -> recheck[entry.providerId] = entry
            }
        }
        val out = taken(outRead, state.out, state.outRecovery) { row ->
            if (eligible(row, SmsBridgeListParams.DIRECTION_OUT)) {
                when (row.type) {
                    SmsProviderRow.TYPE_SENT -> return@taken report(row, SmsBridgeListParams.DIRECTION_OUT)
                    in RECHECK_TYPES -> recheck.putIfAbsent(row.id, SmsRecheck(row.id, now))
                }
            }
            true
        }

        // In first-seen order, so the oldest-seen unsent go first over capacity; sent ones awaiting room stay.
        val rechecks = recheck.values.toList()
        val evicted = rechecks.filter { it.providerId !in awaitingRoom }
            .dropLast(maxOf(MAX_RECHECKS - awaitingRoom.size, 0))
            .toSet()
        return SmsScanResult(
            state = state.copy(
                inbound = inbound.watermark,
                out = out.watermark,
                recheck = rechecks - evicted,
                inboundRecovery = inbound.recovery,
                outRecovery = out.recovery,
                newRowsFrom = newRowsFrom,
            ),
            reports = reports.values.toList(),
            more = inbound.full || out.full,
            evictedRechecks = evicted.toList(),
            queueFull = queueFull,
        )
    }

    private class Page(
        val rows: List<SmsProviderRow>,
        val watermark: SmsWatermark,
        val recovery: SmsRecovery?,
        val full: Boolean,
        /** The date re-read's [SmsRecovery.top]; null for an _id read. */
        val top: SmsWatermark? = null,
    )

    /**
     * [page] up to the first row [take] refuses: the next scan reads on after the last row taken, or
     * from [start] and [startRecovery] again when none was. A fresh date re-read keeps a cursor at
     * its first row instead: started again later, it could begin after rows dated meanwhile.
     */
    private inline fun taken(
        page: Page,
        start: SmsWatermark,
        startRecovery: SmsRecovery?,
        take: (SmsProviderRow) -> Boolean,
    ): Page {
        val count = page.rows.indexOfFirst { !take(it) }
        if (count < 0) return page
        val kept = page.rows.take(count)
        val last = kept.lastOrNull() ?: run {
            val first = page.rows.first()
            val cursor = startRecovery ?: page.top?.let { SmsRecovery(first.date, first.id - 1, it) }
            return Page(kept, start, cursor, full = true)
        }
        val top = page.top ?: return Page(kept, SmsWatermark(last.id, last.date), null, full = true)
        return Page(kept, start, SmsRecovery(last.date, last.id, top), full = true, top)
    }

    private fun page(watermark: SmsWatermark, recovery: SmsRecovery?, types: List<Int>, limit: Int, now: Long): Page {
        if (limit <= 0) return Page(emptyList(), watermark, recovery, full = true)
        if (recovery != null) {
            val page = rows.rowsAfterDate(types, recovery.date, recovery.id, recovery.top.id, limit)
            return recovered(watermark, recovery.top, page, limit)
        }
        val valid = watermark.id <= 0L || rows.row(watermark.id)?.date == watermark.date
        if (valid) {
            val page = rows.rowsAfterId(types, watermark.id, limit)
            val next = page.lastOrNull()?.let { SmsWatermark(it.id, it.date) } ?: watermark
            return Page(page, next, null, page.size >= limit)
        }
        // Taken before the read: rows added after it get a higher _id and are left to the _id scan.
        val top = rows.latest()?.let { SmsWatermark(it.id, it.date) } ?: watermark
        // A watermark dated ahead of now (clock moved back) must not hide rows dated now.
        val from = minOf(watermark.date, now) - SLACK_MS
        return recovered(watermark, top, rows.rowsFromDate(types, from, top.id, limit), limit)
    }

    /** A full date-ordered page continues after its last row; the last page ends the re-read at [top]. */
    private fun recovered(watermark: SmsWatermark, top: SmsWatermark, page: List<SmsProviderRow>, limit: Int): Page {
        if (page.size >= limit) {
            val last = page.last()
            return Page(page, watermark, SmsRecovery(last.date, last.id, top), full = true, top)
        }
        return Page(page, top, null, full = false, top)
    }

    companion object {
        const val MAX_ROWS_PER_SCAN = 50
        const val SLACK_MS = 5L * 60L * 1000L
        const val MAX_RECHECKS = 200

        val INBOUND_TYPES = listOf(SmsProviderRow.TYPE_INBOX)
        /** A failed row stays too: SMS apps retry on the same row. */
        val RECHECK_TYPES = setOf(SmsProviderRow.TYPE_OUTBOX, SmsProviderRow.TYPE_QUEUED, SmsProviderRow.TYPE_FAILED)
        val OUT_TYPES = listOf(SmsProviderRow.TYPE_SENT) + RECHECK_TYPES
    }
}

/** reporter_state encoding of the recheck list: "id:firstSeenAt,..." */
object SmsRecheckCodec {
    fun encode(rechecks: List<SmsRecheck>): String = rechecks.joinToString(",") { "${it.providerId}:${it.firstSeenAt}" }

    fun decode(raw: String?): List<SmsRecheck> =
        raw.orEmpty().split(',').mapNotNull { entry ->
            val parts = entry.trim().split(':')
            if (parts.size != 2) return@mapNotNull null
            val id = parts[0].toLongOrNull() ?: return@mapNotNull null
            val seen = parts[1].toLongOrNull() ?: return@mapNotNull null
            SmsRecheck(id, seen)
        }
}

/** reporter_state encoding of a recovery cursor: "date:id:topId:topDate", empty for none. */
object SmsRecoveryCodec {
    fun encode(recovery: SmsRecovery?): String =
        recovery?.let { "${it.date}:${it.id}:${it.top.id}:${it.top.date}" }.orEmpty()

    fun decode(raw: String?): SmsRecovery? {
        val parts = raw.orEmpty().split(':').map { it.trim().toLongOrNull() ?: return null }
        if (parts.size != 4) return null
        return SmsRecovery(parts[0], parts[1], SmsWatermark(parts[2], parts[3]))
    }
}

object SmsCloudSims {
    /** The SIM cloud SMS covers: the owner's cloud SIM if one was chosen, else the only active SIM. */
    fun pick(active: List<SmsSimInfo>, cloudSubscriptionId: Int?): SmsSimInfo? =
        if (cloudSubscriptionId != null) {
            active.firstOrNull { it.subscriptionId == cloudSubscriptionId }
        } else {
            active.singleOrNull()
        }
}

/** Which SIM the reporter covers, from the active subscriptions and the owner's cloud SIM. */
sealed class SmsReporterSims {
    /** The covered SIM always filters rows, whether it was chosen or is the only active SIM. */
    data class Covered(val subscriptionFilter: Int?, val phoneNumbers: Map<Int, String>) : SmsReporterSims()

    /** No active SIM (right after boot) or subscriptions unreadable: the scan waits and catches up. */
    object Blocked : SmsReporterSims()

    /** SIMs are active but none is covered (cloud SIM inactive or not chosen): their rows are passed over. */
    object Uncovered : SmsReporterSims()

    companion object {
        fun from(result: SmsSubscriptionsResult, cloudSubscriptionId: Int?): SmsReporterSims {
            val sims = (result as? SmsSubscriptionsResult.Available)?.subscriptions ?: return Blocked
            val sim = SmsCloudSims.pick(sims, cloudSubscriptionId) ?: return if (sims.isEmpty()) Blocked else Uncovered
            return Covered(sim.subscriptionId, sim.phoneNumber?.let { mapOf(sim.subscriptionId to it) }.orEmpty())
        }
    }
}
