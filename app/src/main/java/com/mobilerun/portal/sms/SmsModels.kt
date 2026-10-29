package com.mobilerun.portal.sms

import org.json.JSONArray
import org.json.JSONObject

enum class SmsSendState {
    PENDING,
    SENT,
    DELIVERED,
    FAILED,
}

enum class SmsBox(val wireName: String) {
    ALL("all"),
    INBOX("inbox"),
    SENT("sent");

    companion object {
        fun fromWire(raw: String?): SmsBox? {
            return when (raw?.trim()?.lowercase()) {
                null, "", "all" -> ALL
                "inbox" -> INBOX
                "sent" -> SENT
                else -> null
            }
        }
    }
}

data class SmsSendRequest(
    val messageId: String,
    val phoneNumber: String,
    val message: String,
    val subscriptionId: Int?,
    val simSlot: Int?,
    val deliveryReport: Boolean,
)

data class SmsSendStatus(
    val messageId: String,
    val phoneNumber: String,
    val state: SmsSendState,
    val partCount: Int,
    val sentParts: Int,
    val deliveredParts: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val subscriptionId: Int?,
    val simSlot: Int?,
    val errorCode: String? = null,
    val errorMessage: String? = null,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("message_id", messageId)
            .put("phone_number", phoneNumber)
            .put("state", state.name)
            .put("part_count", partCount)
            .put("sent_parts", sentParts)
            .put("delivered_parts", deliveredParts)
            .put("created_at", createdAt)
            .put("updated_at", updatedAt)
            .apply {
                subscriptionId?.let { put("subscription_id", it) }
                simSlot?.let { put("sim_slot", it) }
                errorCode?.let { put("error_code", it) }
                errorMessage?.let { put("error_message", it) }
            }
}

data class SmsStoreCreateResult(
    val created: Boolean,
    val status: SmsSendStatus?,
    val callbackToken: String? = null,
    val error: String? = null,
)

data class SmsListRequest(
    val box: SmsBox,
    val limit: Int,
    val since: Long?,
    val phoneNumber: String?,
) {
    companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 200

        fun fromJson(params: JSONObject): SmsListRequest {
            val box = SmsBox.fromWire(params.optString("box", "all"))
                ?: throw IllegalArgumentException("Invalid box: ${params.optString("box")}")
            val rawLimit = params.optInt("limit", DEFAULT_LIMIT)
            val limit = rawLimit.coerceIn(1, MAX_LIMIT)
            val since = when {
                !params.has("since") || params.isNull("since") -> null
                else -> params.optLong("since").takeIf { it > 0L }
            }
            val phoneNumber = params.optString("phone_number", "").trim().ifBlank { null }
            return SmsListRequest(
                box = box,
                limit = limit,
                since = since,
                phoneNumber = phoneNumber,
            )
        }
    }
}

data class SmsStoredMessage(
    val smsId: String,
    val box: SmsBox,
    val phoneNumber: String,
    val message: String,
    val date: Long,
    val dateSent: Long?,
    val read: Boolean?,
    val threadId: String?,
    val subscriptionId: Int?,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("sms_id", smsId)
            .put("box", box.wireName)
            .put("phone_number", phoneNumber)
            .put("message", message)
            .put("date", date)
            .apply {
                dateSent?.let { put("date_sent", it) }
                read?.let { put("read", it) }
                threadId?.let { put("thread_id", it) }
                subscriptionId?.let { put("subscription_id", it) }
            }
}

data class SmsSubscription(
    val subscriptionId: Int,
    val simSlot: Int,
    val carrierName: String?,
    val displayName: String?,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("subscription_id", subscriptionId)
            .put("sim_slot", simSlot)
            .apply {
                carrierName?.let { put("carrier_name", it) }
                displayName?.let { put("display_name", it) }
            }
}

data class SmsJsonResult(
    val ok: Boolean,
    val json: JSONObject? = null,
    val error: String? = null,
) {
    companion object {
        fun success(json: JSONObject): SmsJsonResult = SmsJsonResult(ok = true, json = json)
        fun error(message: String): SmsJsonResult = SmsJsonResult(ok = false, error = message)
    }
}

typealias SmsReadResponse = SmsJsonResult

fun Iterable<SmsStoredMessage>.storedMessagesToJsonArray(): JSONArray =
    JSONArray().also { array ->
        forEach { array.put(it.toJson()) }
    }

fun Iterable<SmsSubscription>.subscriptionsToJsonArray(): JSONArray =
    JSONArray().also { array ->
        forEach { array.put(it.toJson()) }
    }
