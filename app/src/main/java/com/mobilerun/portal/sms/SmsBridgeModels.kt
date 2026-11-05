package com.mobilerun.portal.sms

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

/** One row of the SMS provider (content://sms). Android-free so policy code stays JVM-testable. */
data class SmsProviderRow(
    val id: Long,
    val type: Int,
    val address: String?,
    val body: String?,
    val date: Long,
    val dateSent: Long? = null,
    val read: Boolean? = null,
    val threadId: Long? = null,
    val subscriptionId: Int? = null,
    val creator: String? = null,
    /** Delivery status of a sent row: -1 none, 0 complete, 32 pending, 64 failed. */
    val status: Int? = null,
) {
    val messageId: String get() = SmsMessageIds.format(id, date)

    companion object {
        // Telephony.TextBasedSmsColumns MESSAGE_TYPE_* values.
        const val TYPE_INBOX = 1
        const val TYPE_SENT = 2
        const val TYPE_DRAFT = 3
        const val TYPE_OUTBOX = 4
        const val TYPE_FAILED = 5
        const val TYPE_QUEUED = 6
    }
}

/** Android message ids are "<_id>-<date>": the sms table is not AUTOINCREMENT, so _id alone can be reused. */
object SmsMessageIds {
    private val PATTERN = Regex("^(\\d{1,18})-(\\d{1,18})$")

    fun format(providerId: Long, date: Long): String = "$providerId-$date"

    /** Returns (providerId, date), or null for anything that is not an Android message id. */
    fun parse(id: String): Pair<Long, Long>? {
        val match = PATTERN.matchEntire(id) ?: return null
        return match.groupValues[1].toLong() to match.groupValues[2].toLong()
    }
}

/** A subscription (SIM) as the bridge reports it. */
data class SmsSimInfo(
    val subscriptionId: Int,
    val simSlot: Int,
    val phoneNumber: String? = null,
    val carrier: String? = null,
    val displayName: String? = null,
) {
    fun toBridgeJson(): JSONObject =
        JSONObject()
            .put("subscriptionId", subscriptionId)
            .put("simSlot", simSlot)
            .apply {
                phoneNumber?.let { put("phoneNumber", it) }
                carrier?.takeIf { it.isNotBlank() }?.let { put("carrier", it) }
                displayName?.takeIf { it.isNotBlank() }?.let { put("displayName", it) }
            }
}

sealed class SmsSubscriptionsResult {
    data class Available(val subscriptions: List<SmsSimInfo>) : SmsSubscriptionsResult()
    object PermissionDenied : SmsSubscriptionsResult()
    object Unavailable : SmsSubscriptionsResult()
}

/** sms/list keyset cursor: position after the last row of a page, with the _id ceiling fixed on page one. */
data class SmsListCursor(
    val order: String,
    val date: Long,
    val id: Long,
    val ceiling: Long,
) {
    fun encode(): String {
        val json = JSONObject()
            .put("o", order)
            .put("d", date)
            .put("i", id)
            .put("c", ceiling)
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    }

    companion object {
        fun decode(raw: String): SmsListCursor? {
            return try {
                val json = JSONObject(String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8))
                val order = json.optString("o")
                if (order.isEmpty() || !json.has("d") || !json.has("i") || !json.has("c")) return null
                SmsListCursor(order, json.getLong("d"), json.getLong("i"), json.getLong("c"))
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** Maps a provider row to the bridge SMSMessage, or null when the row is neither inbox nor sent. */
fun SmsProviderRow.toBridgeMessage(phoneNumbers: Map<Int, String>): JSONObject? {
    val direction = when (type) {
        SmsProviderRow.TYPE_INBOX -> SmsBridgeListParams.DIRECTION_IN
        SmsProviderRow.TYPE_SENT -> SmsBridgeListParams.DIRECTION_OUT
        else -> return null
    }
    val subId = subscriptionId?.takeIf { it >= 0 }
    return JSONObject()
        .put("id", messageId)
        .put("direction", direction)
        .put("address", truncateUtf8(address.orEmpty(), SmsBridgeParams.MAX_ADDR_BYTES))
        .put("body", body.orEmpty())
        .put("bodyAvailable", body != null)
        .put("date", date)
        .put("storedAt", date)
        .apply {
            subId?.let { phoneNumbers[it] }?.let { put("phoneNumber", it) }
            if (direction == SmsBridgeListParams.DIRECTION_IN) {
                dateSent?.takeIf { it > 0 }?.let { put("sentAt", it) }
            }
            read?.let { put("read", it) }
            threadId?.let { put("threadId", it.toString()) }
            subId?.let { put("subscriptionId", it) }
        }
}

internal fun truncateUtf8(value: String, maxBytes: Int): String {
    if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
    val out = StringBuilder()
    var bytes = 0
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        val size = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
        if (bytes + size > maxBytes) break
        out.appendCodePoint(codePoint)
        bytes += size
        index += Character.charCount(codePoint)
    }
    return out.toString()
}

object SmsHashes {
    /** Idempotency fingerprint of a bridge send: SHA-256 over (to, body); the sender is not part of it. */
    fun fingerprint(to: String, body: String): String = sha256Hex("$to\u001f$body")

    fun bodyHash(body: String): String = sha256Hex(body)

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

fun Iterable<SmsSimInfo>.toBridgeJsonArray(): JSONArray =
    JSONArray().also { array -> forEach { array.put(it.toBridgeJson()) } }
