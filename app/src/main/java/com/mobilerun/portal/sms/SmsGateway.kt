package com.mobilerun.portal.sms

import org.json.JSONObject
import java.util.UUID

class SmsGateway(
    private val stateStore: SmsStateStore,
    private val sender: SmsSender,
    private val subscriptionResolver: SmsSubscriptionResolver,
) {
    fun capabilities(): SmsJsonResult =
        SmsJsonResult.success(subscriptionResolver.capabilities())

    fun send(params: JSONObject): SmsJsonResult {
        if (!subscriptionResolver.hasTelephony()) {
            return SmsJsonResult.error("Device does not report telephony support")
        }
        if (!subscriptionResolver.hasSendSms()) {
            return SmsJsonResult.error("SEND_SMS permission not granted")
        }

        val parsed = parseSendRequest(params)
        if (parsed.error != null) {
            return SmsJsonResult.error(parsed.error)
        }
        val request = parsed.request
            ?: return SmsJsonResult.error("Missing required params: 'phone_number' and 'message'")

        if (request.phoneNumber.isBlank()) {
            return SmsJsonResult.error("Missing required param: 'phone_number'")
        }
        if (request.message.isBlank()) {
            return SmsJsonResult.error("Missing required param: 'message'")
        }

        val resolved = subscriptionResolver.resolve(request.subscriptionId, request.simSlot)
        if (resolved.error != null) {
            return SmsJsonResult.error(resolved.error)
        }

        val parts = try {
            sender.divideMessage(request.message, resolved.subscriptionId)
        } catch (e: Exception) {
            return SmsJsonResult.error("Failed to divide SMS message: ${e.message}")
        }

        val stored = stateStore.createOrGet(
            request.copy(subscriptionId = resolved.subscriptionId, simSlot = resolved.simSlot),
            partCount = parts.size,
        )
        if (stored.error != null) {
            return SmsJsonResult.error(stored.error)
        }
        val status = stored.status ?: return SmsJsonResult.error("Failed to create SMS status")
        if (!stored.created) {
            return SmsJsonResult.success(status.toJson())
        }

        return try {
            sender.sendMultipart(
                request.copy(subscriptionId = resolved.subscriptionId, simSlot = resolved.simSlot),
                parts,
                resolved.subscriptionId,
                stored.callbackToken ?: return SmsJsonResult.error("Failed to create SMS callback token"),
            )
            SmsJsonResult.success(status.toJson())
        } catch (e: Exception) {
            val failed = stateStore.markFailed(
                status.messageId,
                "SEND_EXCEPTION",
                e.message ?: e.javaClass.simpleName,
            ) ?: status
            SmsSendResultReceiver.emitStatus(failed)
            SmsJsonResult.success(failed.toJson())
        }
    }

    fun status(params: JSONObject): SmsJsonResult {
        val messageId = params.optString("message_id").trim()
        if (messageId.isBlank()) {
            return SmsJsonResult.error("Missing required param: 'message_id'")
        }
        val status = stateStore.get(messageId)
            ?: return SmsJsonResult.error("SMS status not found: $messageId")
        return SmsJsonResult.success(status.toJson())
    }

    private fun parseSendRequest(params: JSONObject): SendRequestParseResult {
        val phoneNumber = params.optString("phone_number", params.optString("to", "")).trim()
        val message = params.optString("message", params.optString("text", ""))
        if (phoneNumber.isBlank() || message.isBlank()) {
            return SendRequestParseResult(
                request = null,
                error = "Missing required params: 'phone_number' and 'message'",
            )
        }
        val subscriptionId = optionalInt(params, "subscription_id")
        if (subscriptionId.error != null) {
            return SendRequestParseResult(request = null, error = subscriptionId.error)
        }
        val simSlot = optionalInt(params, "sim_slot")
        if (simSlot.error != null) {
            return SendRequestParseResult(request = null, error = simSlot.error)
        }

        return SendRequestParseResult(
            request = SmsSendRequest(
                messageId = params.optString("message_id").trim().ifBlank {
                    UUID.randomUUID().toString()
                },
                phoneNumber = phoneNumber,
                message = message,
                subscriptionId = subscriptionId.value,
                simSlot = simSlot.value,
                deliveryReport = params.optBoolean("delivery_report", true),
            ),
        )
    }

    private fun optionalInt(params: JSONObject, key: String): OptionalIntParseResult {
        if (!params.has(key) || params.isNull(key)) return OptionalIntParseResult(value = null)
        val raw = params.opt(key)
        val value = when (raw) {
            is Int -> raw
            is Long -> raw
                .takeIf { it >= Int.MIN_VALUE.toLong() && it <= Int.MAX_VALUE.toLong() }
                ?.toInt()
            is Number -> {
                val asDouble = raw.toDouble()
                asDouble
                    .takeIf { it.isFinite() && it % 1.0 == 0.0 && it <= Int.MAX_VALUE }
                    ?.toInt()
            }
            is String -> raw.trim().toIntOrNull()
            else -> null
        } ?: return OptionalIntParseResult(
            value = null,
            error = "Invalid integer param: '$key'",
        )
        if (value < 0) {
            return OptionalIntParseResult(
                value = null,
                error = "Invalid integer param: '$key' must be >= 0",
            )
        }
        return OptionalIntParseResult(value = value)
    }

    private data class SendRequestParseResult(
        val request: SmsSendRequest?,
        val error: String? = null,
    )

    private data class OptionalIntParseResult(
        val value: Int?,
        val error: String? = null,
    )
}
