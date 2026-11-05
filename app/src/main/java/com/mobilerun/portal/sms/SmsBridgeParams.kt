package com.mobilerun.portal.sms

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

/** Normalized sms/list params; mirrors the bridge's normalizeList for a device without sms-services/kinds/junk. */
data class SmsBridgeListParams(
    val direction: String = DIRECTION_ALL,
    val limit: Int = DEFAULT_LIMIT,
    val since: Long? = null,
    val until: Long? = null,
    val storedFrom: Long? = null,
    val order: String = ORDER_DESC,
    val cursor: String = "",
    val address: String = "",
    val threadId: String = "",
) {
    companion object {
        const val DIRECTION_IN = "in"
        const val DIRECTION_OUT = "out"
        const val DIRECTION_ALL = "all"
        const val ORDER_DESC = "desc"
        const val ORDER_ASC = "asc"
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 200
    }
}

/** Normalized sms/send params; mirrors the bridge's normalizeSend. */
data class SmsBridgeSendParams(
    val id: String,
    val to: String,
    val body: String,
    val subscriptionId: Long?,
    val simSlot: Long?,
    val deliveryReport: Boolean,
)

sealed class SmsBridgeParse<out T> {
    data class Ok<T>(val value: T) : SmsBridgeParse<T>()
    data class Err(val error: SmsBridgeError) : SmsBridgeParse<Nothing>()
}

object SmsBridgeParams {
    const val MAX_ID_BYTES = 256
    const val MAX_ADDR_BYTES = 256
    const val MAX_CURSOR_BYTES = 1024
    const val MAX_BODY_BYTES = 16 shl 10

    private val LIST_KEYS = setOf(
        "direction", "limit", "since", "until", "storedFrom", "order", "cursor", "address", "threadId",
        "services", "kinds", "junk",
    )
    private val SEND_KEYS = setOf("id", "to", "body", "subscriptionId", "simSlot", "deliveryReport")
    private val ID_KEYS = setOf("id")

    fun parseList(params: JSONObject): SmsBridgeParse<SmsBridgeListParams> = parse(params, LIST_KEYS) {
        val direction = string("direction").ifEmpty { SmsBridgeListParams.DIRECTION_ALL }
        val rawLimit = integer("limit") ?: 0L
        val since = integer("since")
        val until = integer("until")
        val storedFrom = integer("storedFrom")
        val order = string("order").ifEmpty { SmsBridgeListParams.ORDER_DESC }
        val cursor = string("cursor")
        val address = string("address")
        val threadId = string("threadId")
        val services = arrayOrNull("services")
        val kinds = arrayOrNull("kinds")
        val junk = string("junk")

        if (direction !in DIRECTIONS) fail(SmsBridgeError.invalid("direction ${quote(direction)}"))
        if (rawLimit < 0 || rawLimit > SmsBridgeListParams.MAX_LIMIT) {
            fail(SmsBridgeError.invalid("limit must be 1-${SmsBridgeListParams.MAX_LIMIT}"))
        }
        if ((since ?: 0) < 0 || (until ?: 0) < 0 || (storedFrom ?: 0) < 0) {
            fail(SmsBridgeError.invalid("since, until and storedFrom must not be negative"))
        }
        if (since != null && until != null && since >= until) {
            fail(SmsBridgeError.invalid("since must be before until"))
        }
        if (order != SmsBridgeListParams.ORDER_DESC && order != SmsBridgeListParams.ORDER_ASC) {
            fail(SmsBridgeError.invalid("order ${quote(order)}"))
        }
        if (utf8Length(cursor) > MAX_CURSOR_BYTES) fail(SmsBridgeError.invalid("cursor over $MAX_CURSOR_BYTES bytes"))
        if (utf8Length(address) > MAX_ADDR_BYTES) fail(SmsBridgeError.invalid("address over $MAX_ADDR_BYTES bytes"))
        if (utf8Length(threadId) > MAX_ID_BYTES) fail(SmsBridgeError.invalid("threadId over $MAX_ID_BYTES bytes"))
        // Android announces none of sms-services, sms-kinds or sms-junk.
        if (services != null) fail(SmsBridgeError.unsupported("services"))
        if (kinds != null) fail(SmsBridgeError.unsupported("kinds"))
        if (junk.isNotEmpty()) fail(SmsBridgeError.unsupported("junk"))

        SmsBridgeListParams(
            direction = direction,
            limit = if (rawLimit == 0L) SmsBridgeListParams.DEFAULT_LIMIT else rawLimit.toInt(),
            since = since,
            until = until,
            storedFrom = storedFrom,
            order = order,
            cursor = cursor,
            address = address,
            threadId = threadId,
        )
    }

    fun parseSend(params: JSONObject): SmsBridgeParse<SmsBridgeSendParams> = parse(params, SEND_KEYS) {
        val id = string("id")
        val to = string("to")
        val body = string("body")
        val subscriptionId = integer("subscriptionId")
        val simSlot = integer("simSlot")
        val deliveryReport = bool("deliveryReport")

        checkId("id", id, required = false)
        if (to.isEmpty()) fail(SmsBridgeError.invalid("to is required"))
        if (utf8Length(to) > MAX_ADDR_BYTES) fail(SmsBridgeError.invalid("to over $MAX_ADDR_BYTES bytes"))
        if (body.isEmpty()) fail(SmsBridgeError.invalid("body is required"))
        if (utf8Length(body) > MAX_BODY_BYTES) fail(SmsBridgeError.invalid("body over $MAX_BODY_BYTES bytes"))
        if (subscriptionId != null && simSlot != null) {
            fail(SmsBridgeError.invalid("subscriptionId and simSlot are mutually exclusive"))
        }
        if (simSlot != null && simSlot < 0) fail(SmsBridgeError.invalid("simSlot must not be negative"))

        SmsBridgeSendParams(
            id = id,
            to = to,
            body = body,
            subscriptionId = subscriptionId,
            simSlot = simSlot,
            deliveryReport = deliveryReport ?: true,
        )
    }

    /** Params of sms/get and sms/sendStatus: a required id. */
    fun parseId(params: JSONObject): SmsBridgeParse<String> = parse(params, ID_KEYS) {
        val id = string("id")
        checkId("id", id, required = true)
        id
    }

    fun utf8Length(value: String): Int = value.toByteArray(Charsets.UTF_8).size

    private val DIRECTIONS = setOf(
        SmsBridgeListParams.DIRECTION_IN,
        SmsBridgeListParams.DIRECTION_OUT,
        SmsBridgeListParams.DIRECTION_ALL,
    )

    private fun <T> parse(params: JSONObject, allowed: Set<String>, block: Reader.() -> T): SmsBridgeParse<T> {
        params.keys().asSequence().sorted().firstOrNull { it !in allowed }?.let {
            return SmsBridgeParse.Err(SmsBridgeError.unsupported(it))
        }
        return try {
            SmsBridgeParse.Ok(Reader(params).block())
        } catch (e: ParamError) {
            SmsBridgeParse.Err(e.error)
        }
    }

    private fun quote(value: String): String = JSONObject.quote(value.take(32))

    private class ParamError(val error: SmsBridgeError) : Exception(null, null, false, false)

    private class Reader(private val json: JSONObject) {
        fun fail(error: SmsBridgeError): Nothing = throw ParamError(error)

        private fun raw(key: String): Any? =
            json.opt(key)?.takeUnless { it == JSONObject.NULL }

        fun string(key: String): String = when (val value = raw(key)) {
            null -> ""
            is String -> value
            else -> fail(SmsBridgeError.invalid("$key must be a string"))
        }

        fun integer(key: String): Long? = when (val value = raw(key)) {
            null -> null
            is Int -> value.toLong()
            is Long -> value
            is Short -> value.toLong()
            is Byte -> value.toLong()
            is BigInteger -> if (value.bitLength() < 64) value.toLong() else fail(SmsBridgeError.invalid("$key out of range"))
            else -> fail(SmsBridgeError.invalid("$key must be an integer"))
        }

        fun bool(key: String): Boolean? = when (val value = raw(key)) {
            null -> null
            is Boolean -> value
            else -> fail(SmsBridgeError.invalid("$key must be a boolean"))
        }

        fun arrayOrNull(key: String): JSONArray? = when (val value = raw(key)) {
            null -> null
            is JSONArray -> value
            else -> fail(SmsBridgeError.invalid("$key must be an array"))
        }

        fun checkId(name: String, id: String, required: Boolean) {
            if (id.isEmpty()) {
                if (required) fail(SmsBridgeError.invalid("$name is required"))
                return
            }
            if (utf8Length(id) > MAX_ID_BYTES) fail(SmsBridgeError.invalid("$name over $MAX_ID_BYTES bytes"))
        }
    }
}
