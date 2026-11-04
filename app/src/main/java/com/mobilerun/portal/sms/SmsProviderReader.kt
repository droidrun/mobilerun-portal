package com.mobilerun.portal.sms

import android.content.Context
import android.database.Cursor
import android.provider.Telephony

/** content://sms reads for the bridge and send recovery. SMS only: RCS and MMS are never read. */
class AndroidSmsProviderSource(
    private val context: Context,
) : SmsProviderSource, SmsReportRowSource {
    override fun latest(): SmsProviderRow? =
        query(selection = null, args = null, sortOrder = "_id DESC LIMIT 1").firstOrNull()

    override fun query(query: SmsProviderQuery): List<SmsProviderRow> {
        val (selection, args) = query.selection()
        return query(selection, args, query.sortOrder())
    }

    override fun get(providerId: Long): SmsProviderRow? =
        query("_id = ?", arrayOf(providerId.toString()), sortOrder = null).firstOrNull()

    override fun row(providerId: Long): SmsProviderRow? = get(providerId)

    override fun rows(providerIds: Collection<Long>): List<SmsProviderRow> =
        if (providerIds.isEmpty()) emptyList() else query("_id IN (${providerIds.joinToString(",")})", null, sortOrder = null)

    override fun rowsAfterId(types: List<Int>, afterId: Long, limit: Int): List<SmsProviderRow> =
        query(
            "type IN (${types.joinToString(",")}) AND _id > ?",
            arrayOf(afterId.toString()),
            sortOrder = "_id ASC LIMIT $limit",
        )

    override fun rowsFromDate(types: List<Int>, fromDate: Long, maxId: Long, limit: Int): List<SmsProviderRow> =
        query(
            "type IN (${types.joinToString(",")}) AND date >= ? AND _id <= ?",
            arrayOf(fromDate.toString(), maxId.toString()),
            sortOrder = "date ASC, _id ASC LIMIT $limit",
        )

    override fun rowsAfterDate(types: List<Int>, afterDate: Long, afterId: Long, maxId: Long, limit: Int): List<SmsProviderRow> =
        query(
            "type IN (${types.joinToString(",")}) AND (date > ? OR (date = ? AND _id > ?)) AND _id <= ?",
            arrayOf(afterDate.toString(), afterDate.toString(), afterId.toString(), maxId.toString()),
            sortOrder = "date ASC, _id ASC LIMIT $limit",
        )

    override fun ownSends(packageName: String, since: Long, after: SmsWatermark?, limit: Int): List<SmsProviderRow> {
        val types = listOf(
            SmsProviderRow.TYPE_SENT,
            SmsProviderRow.TYPE_FAILED,
            SmsProviderRow.TYPE_OUTBOX,
            SmsProviderRow.TYPE_QUEUED,
        ).joinToString(",")
        var selection = "creator = ? AND date >= ? AND type IN ($types)"
        val args = mutableListOf(packageName, since.toString())
        if (after != null) {
            selection += " AND (date > ? OR (date = ? AND _id > ?))"
            args += listOf(after.date.toString(), after.date.toString(), after.id.toString())
        }
        return query(selection, args.toTypedArray(), sortOrder = "date ASC, _id ASC LIMIT $limit")
    }

    /** A null cursor means the provider is unavailable, not "no rows": it throws, so callers retry. */
    private fun query(selection: String?, args: Array<String>?, sortOrder: String?): List<SmsProviderRow> {
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            PROJECTION,
            selection,
            args,
            sortOrder,
        ) ?: throw IllegalStateException("content://sms unavailable")
        return cursor.use {
            val rows = ArrayList<SmsProviderRow>(it.count.coerceAtLeast(0))
            while (it.moveToNext()) rows += it.toRow()
            rows
        }
    }

    private fun Cursor.toRow(): SmsProviderRow =
        SmsProviderRow(
            id = getLong(getColumnIndexOrThrow(Telephony.Sms._ID)),
            type = optInt(Telephony.Sms.TYPE) ?: 0,
            address = optString(Telephony.Sms.ADDRESS),
            body = optString(Telephony.Sms.BODY),
            date = optLong(Telephony.Sms.DATE) ?: 0L,
            dateSent = optLong(Telephony.Sms.DATE_SENT),
            read = optInt(Telephony.Sms.READ)?.let { it != 0 },
            threadId = optLong(Telephony.Sms.THREAD_ID),
            subscriptionId = optInt(Telephony.Sms.SUBSCRIPTION_ID),
            creator = optString(Telephony.Sms.CREATOR),
            status = optInt(Telephony.Sms.STATUS),
        )

    private fun Cursor.optString(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun Cursor.optLong(column: String): Long? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getLong(index) else null
    }

    private fun Cursor.optInt(column: String): Int? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getInt(index) else null
    }

    companion object {
        private val PROJECTION = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.TYPE,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.DATE_SENT,
            Telephony.Sms.READ,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.SUBSCRIPTION_ID,
            Telephony.Sms.CREATOR,
            Telephony.Sms.STATUS,
        )
    }
}
