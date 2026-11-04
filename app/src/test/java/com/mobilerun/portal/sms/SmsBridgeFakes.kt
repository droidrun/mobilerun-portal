package com.mobilerun.portal.sms

/**
 * In-memory content://sms with the same filter and keyset semantics as the SQL selection.
 * New rows take max(_id) + 1, like the provider's non-AUTOINCREMENT table.
 */
class FakeSmsProvider : SmsProviderSource, SmsReportRowSource {
    val rows = mutableListOf<SmsProviderRow>()
    var queries = 0
    /** The next N rowsAfterId reads throw, like a transient provider failure. */
    var failReads = 0
    /** Single-row reads by _id. */
    var rowReads = 0

    fun add(
        type: Int,
        date: Long,
        address: String = "+15550001111",
        body: String? = "body-$date",
        subscriptionId: Int? = 1,
        threadId: Long? = 7,
        creator: String? = "com.google.android.apps.messaging",
        dateSent: Long? = null,
    ): SmsProviderRow {
        val row = SmsProviderRow(
            id = (rows.maxOfOrNull { it.id } ?: 0L) + 1L,
            type = type,
            address = address,
            body = body,
            date = date,
            dateSent = dateSent,
            read = true,
            threadId = threadId,
            subscriptionId = subscriptionId,
            creator = creator,
        )
        rows += row
        return row
    }

    override fun latest(): SmsProviderRow? = rows.maxByOrNull { it.id }

    override fun query(query: SmsProviderQuery): List<SmsProviderRow> {
        queries += 1
        val comparator = compareBy<SmsProviderRow>({ it.date }, { it.id })
        return rows
            .filter { it.type in query.types && it.id <= query.ceilingId }
            .filter { row -> query.since?.let { row.date > it } ?: true }
            .filter { row -> query.until?.let { row.date <= it } ?: true }
            .filter { row -> query.storedFrom?.let { row.date >= it } ?: true }
            .filter { query.address == null || it.address == query.address }
            .filter { query.threadId == null || it.threadId?.toString() == query.threadId }
            .filter { query.subscriptionId == null || it.subscriptionId == query.subscriptionId }
            .filter { row ->
                val after = query.after ?: return@filter true
                if (query.ascending) {
                    row.date > after.date || (row.date == after.date && row.id > after.id)
                } else {
                    row.date < after.date || (row.date == after.date && row.id < after.id)
                }
            }
            .sortedWith(if (query.ascending) comparator else comparator.reversed())
            .take(query.limit)
    }

    override fun get(providerId: Long): SmsProviderRow? = rows.firstOrNull { it.id == providerId }

    override fun row(providerId: Long): SmsProviderRow? {
        rowReads += 1
        return get(providerId)
    }

    override fun rows(providerIds: Collection<Long>): List<SmsProviderRow> {
        if (providerIds.isEmpty()) return emptyList()
        queries += 1
        val wanted = providerIds.toSet()
        return rows.filter { it.id in wanted }
    }

    override fun rowsAfterId(types: List<Int>, afterId: Long, limit: Int): List<SmsProviderRow> {
        queries += 1
        if (failReads > 0) {
            failReads -= 1
            throw IllegalStateException("provider unavailable")
        }
        return rows.filter { it.type in types && it.id > afterId }.sortedBy { it.id }.take(limit)
    }

    override fun rowsFromDate(types: List<Int>, fromDate: Long, maxId: Long, limit: Int): List<SmsProviderRow> {
        queries += 1
        return rows
            .filter { it.type in types && it.date >= fromDate && it.id <= maxId }
            .sortedWith(compareBy<SmsProviderRow>({ it.date }, { it.id }))
            .take(limit)
    }

    override fun rowsAfterDate(types: List<Int>, afterDate: Long, afterId: Long, maxId: Long, limit: Int): List<SmsProviderRow> {
        queries += 1
        return rows
            .filter { it.type in types && (it.date > afterDate || (it.date == afterDate && it.id > afterId)) && it.id <= maxId }
            .sortedWith(compareBy<SmsProviderRow>({ it.date }, { it.id }))
            .take(limit)
    }

    fun remove(providerId: Long) {
        rows.removeAll { it.id == providerId }
    }

    fun update(providerId: Long, transform: (SmsProviderRow) -> SmsProviderRow) {
        val index = rows.indexOfFirst { it.id == providerId }
        rows[index] = transform(rows[index])
    }

    override fun ownSends(packageName: String, since: Long, after: SmsWatermark?, limit: Int): List<SmsProviderRow> {
        queries += 1
        val types = setOf(SmsProviderRow.TYPE_SENT, SmsProviderRow.TYPE_FAILED, SmsProviderRow.TYPE_OUTBOX, SmsProviderRow.TYPE_QUEUED)
        return rows
            .filter { it.creator == packageName && it.date >= since && it.type in types }
            .filter { row -> after == null || row.date > after.date || (row.date == after.date && row.id > after.id) }
            .sortedWith(compareBy<SmsProviderRow>({ it.date }, { it.id }))
            .take(limit)
    }
}

class InMemorySendRecordStore : SmsSendRecordStore {
    val records = linkedMapOf<String, SmsSendRecord>()
    val events = mutableListOf<String>()
    var failInsert = false

    /** Outstanding sends alone fill the store. */
    var full = false

    /** The insert lands but the caller sees a failure, like a store-thread timeout or interrupt. */
    var insertLandsThenFails = false

    override fun find(id: String): SmsSendRecord? = records[id]

    override fun insert(record: SmsSendRecord): Boolean {
        if (failInsert) throw IllegalStateException("disk full")
        if (full) throw SmsStoreFullException()
        if (records.containsKey(record.id)) return false
        records[record.id] = record
        events += "insert:${record.id}:${record.phase.wire}"
        if (insertLandsThenFails) throw java.util.concurrent.TimeoutException()
        return true
    }

    override fun discardPrepared(id: String, callbackToken: String) {
        val record = records[id] ?: return
        if (record.phase != SmsSendPhase.PREPARED || record.callbackToken != callbackToken) return
        records.remove(id)
        events += "discard:$id"
    }

    override fun markDispatched(id: String, now: Long): SmsSendRecord? =
        records[id]?.withDispatched(now)?.also {
            records[id] = it
            events += "dispatched:$id"
        }

    override fun markFailed(id: String, code: String, message: String, now: Long): SmsSendRecord? =
        records[id]?.withFailure(code, message, now)?.also {
            records[id] = it
            events += "failed:$id:$code"
        }
}

class FakeBridgeSender(private val events: MutableList<String>) : SmsBridgeSender {
    var parts: (String) -> List<String> = { listOf(it) }
    var sendException: Exception? = null
    val sends = mutableListOf<Triple<String, String, Int?>>()

    override fun divideMessage(message: String, subscriptionId: Int?): List<String> = parts(message)

    override fun sendBridgeMultipart(
        recordId: String,
        to: String,
        parts: List<String>,
        subscriptionId: Int?,
        deliveryReport: Boolean,
        callbackToken: String,
    ) {
        events += "send:$recordId"
        sendException?.let { throw it }
        sends += Triple(recordId, to, subscriptionId)
    }
}
