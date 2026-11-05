package com.mobilerun.portal.sms

/** A keyset page query on content://sms, newest first unless [ascending]. */
data class SmsProviderQuery(
    val types: List<Int>,
    val ceilingId: Long,
    val limit: Int,
    val ascending: Boolean = false,
    val after: SmsListCursor? = null,
    val since: Long? = null,
    val until: Long? = null,
    val storedFrom: Long? = null,
    val address: String? = null,
    val threadId: String? = null,
    val subscriptionId: Int? = null,
) {
    /** SQL selection over provider columns; storedAt is the row date on Android. */
    fun selection(): Pair<String, Array<String>> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        clauses += "type IN (${types.joinToString(",")})"
        clauses += "_id <= ?"
        args += ceilingId.toString()
        since?.let { clauses += "date > ?"; args += it.toString() }
        until?.let { clauses += "date <= ?"; args += it.toString() }
        storedFrom?.let { clauses += "date >= ?"; args += it.toString() }
        address?.let { clauses += "address = ?"; args += it }
        threadId?.let { clauses += "thread_id = ?"; args += it }
        subscriptionId?.let { clauses += "sub_id = ?"; args += it.toString() }
        after?.let {
            val op = if (ascending) ">" else "<"
            clauses += "(date $op ? OR (date = ? AND _id $op ?))"
            args += listOf(it.date.toString(), it.date.toString(), it.id.toString())
        }
        return clauses.joinToString(" AND ") to args.toTypedArray()
    }

    fun sortOrder(): String {
        val direction = if (ascending) "ASC" else "DESC"
        return "date $direction, _id $direction LIMIT $limit"
    }
}

interface SmsProviderSource {
    /** The newest row by _id (any type), or null when the table is empty. */
    fun latest(): SmsProviderRow?

    fun query(query: SmsProviderQuery): List<SmsProviderRow>

    fun get(providerId: Long): SmsProviderRow?

    /**
     * Rows this package created at or after [since], in the sent, failed, outbox or queued boxes,
     * strictly after [after] by (date, _id) ascending.
     */
    fun ownSends(packageName: String, since: Long, after: SmsWatermark?, limit: Int): List<SmsProviderRow>
}

/** Outstanding sends alone fill the store: nothing was inserted. */
class SmsStoreFullException : Exception("store_full")

/** Persistence used by relay sends. */
interface SmsSendRecordStore {
    fun find(id: String): SmsSendRecord?

    /** Inserts a new record; false when the id already exists. Throws [SmsStoreFullException]. */
    fun insert(record: SmsSendRecord): Boolean

    /** Removes [id] if it is still a prepared record with [callbackToken]: an insert reported as failed may still land. */
    fun discardPrepared(id: String, callbackToken: String)

    fun markDispatched(id: String, now: Long): SmsSendRecord?

    fun markFailed(id: String, code: String, message: String, now: Long): SmsSendRecord?
}
