package com.mobilerun.portal.sms

/** The last provider row a direction's scan has looked at. id 0 means "from the start of the table". */
data class SmsWatermark(val id: Long, val date: Long)

/** An outbox or queued row the owner sent, checked again until it reaches the sent box. */
data class SmsRecheck(val providerId: Long, val firstSeenAt: Long)

data class SmsScanState(
    val inbound: SmsWatermark,
    val out: SmsWatermark,
    val coveredFrom: Long,
    val recheck: List<SmsRecheck> = emptyList(),
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
)

/** Provider reads the reporter needs. */
interface SmsReportRowSource {
    /** The newest row by _id (any type), or null when the table is empty. */
    fun latest(): SmsProviderRow?

    fun row(providerId: Long): SmsProviderRow?

    /** Rows of [types] with _id > [afterId], by _id ascending. */
    fun rowsAfterId(types: List<Int>, afterId: Long, limit: Int): List<SmsProviderRow>

    /** Rows of [types] with date >= [fromDate], by (date, _id) ascending. */
    fun rowsFromDate(types: List<Int>, fromDate: Long, limit: Int): List<SmsProviderRow>
}

/**
 * Finds provider rows to report as sms/received. in = inbox rows; out = sent rows the owner sent
 * from another app (never the Portal's own). New rows are those past the watermark; when the
 * watermark row is gone or its date changed (_id reuse), rows dated from watermarkDate - 5 min
 * are read instead and already reported ids are skipped.
 */
class SmsReportScanner(
    private val rows: SmsReportRowSource,
    private val packageName: String,
) {
    fun scan(
        state: SmsScanState,
        ownSendIds: Set<Long>,
        isReported: (String) -> Boolean,
        subscriptionFilter: Int?,
        now: Long,
    ): SmsScanResult {
        val reports = LinkedHashMap<String, SmsReportCandidate>()
        val floor = state.coveredFrom - SLACK_MS

        fun eligible(row: SmsProviderRow, direction: String): Boolean =
            row.date >= floor &&
                (subscriptionFilter == null || row.subscriptionId == subscriptionFilter) &&
                row.id !in ownSendIds &&
                (direction == SmsBridgeListParams.DIRECTION_IN || row.creator != packageName)

        fun report(row: SmsProviderRow, direction: String) {
            val msgId = row.messageId
            if (msgId in reports || isReported(msgId)) return
            reports[msgId] = SmsReportCandidate(msgId, row.id, direction)
        }

        val inbound = page(state.inbound, INBOUND_TYPES, MAX_ROWS_PER_SCAN)
        inbound.rows
            .filter { it.type == SmsProviderRow.TYPE_INBOX && eligible(it, SmsBridgeListParams.DIRECTION_IN) }
            .forEach { report(it, SmsBridgeListParams.DIRECTION_IN) }

        val out = page(state.out, OUT_TYPES, MAX_ROWS_PER_SCAN - inbound.rows.size)
        val recheck = LinkedHashMap<Long, SmsRecheck>()
        for (entry in state.recheck) {
            val row = rows.row(entry.providerId)
            when {
                row == null -> Unit
                row.type == SmsProviderRow.TYPE_SENT -> {
                    if (eligible(row, SmsBridgeListParams.DIRECTION_OUT)) report(row, SmsBridgeListParams.DIRECTION_OUT)
                }
                row.type in PENDING_OUT_TYPES && now - entry.firstSeenAt < RECHECK_TTL_MS ->
                    recheck[entry.providerId] = entry
            }
        }
        for (row in out.rows) {
            if (!eligible(row, SmsBridgeListParams.DIRECTION_OUT)) continue
            when (row.type) {
                SmsProviderRow.TYPE_SENT -> report(row, SmsBridgeListParams.DIRECTION_OUT)
                in PENDING_OUT_TYPES -> recheck.putIfAbsent(row.id, SmsRecheck(row.id, now))
            }
        }

        val keptRechecks = recheck.values.sortedBy { it.firstSeenAt }.takeLast(MAX_RECHECKS)
        return SmsScanResult(
            state = state.copy(inbound = inbound.watermark, out = out.watermark, recheck = keptRechecks),
            reports = reports.values.toList(),
            more = inbound.full || out.full,
        )
    }

    private class Page(val rows: List<SmsProviderRow>, val watermark: SmsWatermark, val full: Boolean)

    private fun page(watermark: SmsWatermark, types: List<Int>, limit: Int): Page {
        if (limit <= 0) return Page(emptyList(), watermark, full = true)
        val valid = watermark.id <= 0L || rows.row(watermark.id)?.date == watermark.date
        if (valid) {
            val page = rows.rowsAfterId(types, watermark.id, limit)
            val next = page.lastOrNull()?.let { SmsWatermark(it.id, it.date) } ?: watermark
            return Page(page, next, page.size >= limit)
        }
        val page = rows.rowsFromDate(types, watermark.date - SLACK_MS, limit)
        val full = page.size >= limit
        // New rows take max(_id) + 1, so the highest _id is the newest row once the page is complete.
        val anchor = if (full) page.lastOrNull() else page.maxByOrNull { it.id }
        val next = anchor?.let { SmsWatermark(it.id, it.date) } ?: watermark
        return Page(page, next, full)
    }

    companion object {
        const val MAX_ROWS_PER_SCAN = 50
        const val SLACK_MS = 5L * 60L * 1000L
        const val RECHECK_TTL_MS = 24L * 60L * 60L * 1000L
        const val MAX_RECHECKS = 200

        val INBOUND_TYPES = listOf(SmsProviderRow.TYPE_INBOX)
        val PENDING_OUT_TYPES = setOf(SmsProviderRow.TYPE_OUTBOX, SmsProviderRow.TYPE_QUEUED)
        val OUT_TYPES = listOf(SmsProviderRow.TYPE_SENT, SmsProviderRow.TYPE_OUTBOX, SmsProviderRow.TYPE_QUEUED)
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

/** Which SIM the reporter covers, from the active subscriptions and the owner's cloud SIM. */
sealed class SmsReporterSims {
    data class Covered(val subscriptionFilter: Int?, val phoneNumbers: Map<Int, String>) : SmsReporterSims()

    /** Multi-SIM without a cloud SIM, or subscriptions unreadable: report nothing for now. */
    object Blocked : SmsReporterSims()

    companion object {
        fun from(result: SmsSubscriptionsResult, cloudSubscriptionId: Int?): SmsReporterSims {
            val sims = (result as? SmsSubscriptionsResult.Available)?.subscriptions ?: return Blocked
            val visible = if (sims.size <= 1) {
                sims
            } else {
                listOf(sims.firstOrNull { it.subscriptionId == cloudSubscriptionId } ?: return Blocked)
            }
            val filter = if (sims.size > 1) visible.single().subscriptionId else null
            return Covered(filter, visible.mapNotNull { sim -> sim.phoneNumber?.let { sim.subscriptionId to it } }.toMap())
        }
    }
}
