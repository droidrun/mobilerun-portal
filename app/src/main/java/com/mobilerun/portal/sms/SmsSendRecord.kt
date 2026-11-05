package com.mobilerun.portal.sms

import org.json.JSONObject

/** prepared: persisted before SmsManager; dispatched: SmsManager accepted it; terminal: no further change expected. */
enum class SmsSendPhase(val wire: String) {
    PREPARED("prepared"),
    DISPATCHED("dispatched"),
    TERMINAL("terminal");

    companion object {
        fun fromWire(raw: String?): SmsSendPhase = entries.firstOrNull { it.wire == raw } ?: PREPARED
    }
}

val SmsSendState.wire: String get() = name.lowercase()

fun smsSendStateFromWire(raw: String?): SmsSendState =
    SmsSendState.entries.firstOrNull { it.wire == raw } ?: SmsSendState.PENDING

/** A persisted send. The body is never stored: only its hash and the (to, body) fingerprint. */
data class SmsSendRecord(
    val id: String,
    val origin: String,
    val to: String,
    val fingerprint: String,
    val bodyHash: String,
    val subscriptionId: Int?,
    val simSlot: Int?,
    val deliveryReport: Boolean,
    val phase: SmsSendPhase,
    val state: SmsSendState,
    val parts: Int,
    val sentParts: Set<Int>,
    val deliveredParts: Set<Int>,
    val callbackToken: String,
    val messageUri: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val lastReportedState: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    /** The store instance (one per process) that inserted it; empty for records from before instance ids. */
    val instanceId: String = "",
    /** elapsedRealtime at the insert; 0 for records from before. */
    val createdElapsed: Long = 0L,
) {
    val isTerminal: Boolean get() = phase == SmsSendPhase.TERMINAL

    fun withDispatched(now: Long): SmsSendRecord? =
        if (phase == SmsSendPhase.PREPARED) copy(phase = SmsSendPhase.DISPATCHED, updatedAt = now) else null

    fun withPartSent(index: Int, now: Long): SmsSendRecord? {
        if (state == SmsSendState.FAILED || index !in 0 until parts || index in sentParts) return null
        val sent = sentParts + index
        val nextState = when {
            state == SmsSendState.DELIVERED -> SmsSendState.DELIVERED
            sent.size >= parts -> SmsSendState.SENT
            else -> state
        }
        return settle(copy(sentParts = sent, state = nextState, updatedAt = now))
    }

    fun withPartDelivered(index: Int, now: Long): SmsSendRecord? {
        if (state == SmsSendState.FAILED || index !in 0 until parts || index in deliveredParts) return null
        val delivered = deliveredParts + index
        val nextState = if (delivered.size >= parts) SmsSendState.DELIVERED else state
        return settle(copy(deliveredParts = delivered, state = nextState, updatedAt = now))
    }

    fun withFailure(code: String, message: String, now: Long): SmsSendRecord? {
        if (state == SmsSendState.DELIVERED || state == SmsSendState.FAILED) return null
        return copy(
            state = SmsSendState.FAILED,
            phase = SmsSendPhase.TERMINAL,
            errorCode = code,
            errorMessage = message,
            updatedAt = now,
        )
    }

    /** Provider-row evidence after a restart: every part reached the sent box. */
    fun withAllSent(now: Long): SmsSendRecord? {
        if (state != SmsSendState.PENDING) return null
        return settle(copy(sentParts = (0 until parts).toSet(), state = SmsSendState.SENT, updatedAt = now))
    }

    private fun settle(next: SmsSendRecord): SmsSendRecord {
        val terminal = next.state == SmsSendState.FAILED ||
            next.state == SmsSendState.DELIVERED ||
            (next.state == SmsSendState.SENT && !next.deliveryReport)
        return next.copy(phase = if (terminal) SmsSendPhase.TERMINAL else SmsSendPhase.DISPATCHED)
    }

    /** The bridge SMSSendStatus. */
    fun toBridgeStatus(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("to", to)
            .put("state", state.wire)
            .put("parts", parts)
            .put("sentParts", sentParts.size)
            .put("deliveredParts", deliveredParts.size)
            .put("createdAt", createdAt)
            .put("updatedAt", updatedAt)
            .apply {
                subscriptionId?.let { put("subscriptionId", it) }
                simSlot?.let { put("simSlot", it) }
                if (state == SmsSendState.FAILED) {
                    put(
                        "error",
                        JSONObject()
                            .put("code", errorCode ?: "UNKNOWN")
                            .apply { errorMessage?.let { put("message", it) } },
                    )
                }
            }

    companion object {
        const val ORIGIN_RELAY = "relay"
    }
}

/** A sent/delivered PendingIntent result for a relay send. */
internal data class SmsSendCallback(
    val sent: Boolean,
    val recordId: String,
    val partIndex: Int,
    val callbackToken: String,
    val resultOk: Boolean,
    val deliveryOutcome: SmsDeliveryOutcome? = null,
    val deliveryStatus: Int? = null,
    val resultCode: Int = 0,
    val errorCode: Int = -1,
    val providerUri: String? = null,
)

internal object SmsSendCallbacks {
    const val RESULT_INTERRUPTED = "RESULT_INTERRUPTED"
    const val SEND_EXCEPTION = "SEND_EXCEPTION"
    const val DELIVERY_FAILED = "DELIVERY_FAILED"

    /** Applies a callback; null when the token does not match or nothing changed. */
    fun apply(record: SmsSendRecord, callback: SmsSendCallback, now: Long): SmsSendRecord? {
        if (record.callbackToken != callback.callbackToken) return null
        val withUri = callback.providerUri
            ?.takeIf { callback.sent && record.messageUri == null }
            ?.let { record.copy(messageUri = it) }
            ?: record
        val changed = if (callback.sent) {
            if (callback.resultOk) {
                withUri.withPartSent(callback.partIndex, now)
            } else {
                withUri.withFailure(
                    SmsErrorMapper.codeName(callback.resultCode),
                    failureMessage(callback.resultCode, callback.errorCode),
                    now,
                )
            }
        } else {
            when (callback.deliveryOutcome) {
                SmsDeliveryOutcome.DELIVERED, null -> withUri.withPartDelivered(callback.partIndex, now)
                SmsDeliveryOutcome.PERMANENT_FAILURE -> withUri.withFailure(
                    DELIVERY_FAILED,
                    "Delivery failed (status=${callback.deliveryStatus})",
                    now,
                )
                SmsDeliveryOutcome.TEMPORARY_FAILURE -> null
            }
        }
        return changed ?: withUri.takeIf { it !== record }
    }

    fun failureMessage(resultCode: Int, errorCode: Int): String {
        val base = SmsErrorMapper.message(resultCode)
        return if (errorCode != -1) "$base (errorCode=$errorCode)" else base
    }

    /** Provider _id from the framework's 'uri' extra (content://sms/<id> or content://sms/<box>/<id>). */
    fun providerIdFromUri(uri: String?): Long? {
        val match = Regex("^content://sms(?:/[a-z]+)?/(\\d+)$").matchEntire(uri?.trim().orEmpty()) ?: return null
        return match.groupValues[1].toLongOrNull()
    }
}
