package com.mobilerun.portal.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import com.mobilerun.portal.config.ConfigManager
import com.mobilerun.portal.events.EventHub
import com.mobilerun.portal.events.model.EventType
import com.mobilerun.portal.events.model.PortalEvent

internal enum class SmsDeliveryOutcome {
    DELIVERED,
    TEMPORARY_FAILURE,
    PERMANENT_FAILURE,
}

internal fun smsDeliveryOutcome(status: Int?): SmsDeliveryOutcome {
    return when {
        status == null -> SmsDeliveryOutcome.DELIVERED
        status < 0x20 -> SmsDeliveryOutcome.DELIVERED
        status >= 0x40 -> SmsDeliveryOutcome.PERMANENT_FAILURE
        else -> SmsDeliveryOutcome.TEMPORARY_FAILURE
    }
}

/** A status report's TP-Status; a 3GPP2 one (error class << 24 | status << 16) is mapped onto it, as AOSP Messaging does. */
internal fun smsDeliveryStatus(status: Int, format: String?): Int {
    if (format != SmsMessage.FORMAT_3GPP2) return status
    return when ((status shr 24) and 0x03) {
        0 -> if (((status shr 16) and 0x3f) == 0x02) Telephony.Sms.STATUS_COMPLETE else Telephony.Sms.STATUS_PENDING
        3 -> Telephony.Sms.STATUS_FAILED
        else -> Telephony.Sms.STATUS_PENDING
    }
}

class SmsSendResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val messageId = intent.getStringExtra(EXTRA_MESSAGE_ID).orEmpty()
        if (messageId.isBlank()) return
        val partIndex = intent.getIntExtra(EXTRA_PART_INDEX, -1)
        if (partIndex < 0) return
        val callbackToken = intent.getStringExtra(EXTRA_CALLBACK_TOKEN).orEmpty()
        if (callbackToken.isBlank()) return
        val providerUri = intent.getStringExtra(EXTRA_PROVIDER_URI)

        if (intent.getStringExtra(EXTRA_ORIGIN) == SmsSendRecord.ORIGIN_RELAY) {
            onRelayResult(context, intent, action, messageId, partIndex, callbackToken, providerUri)
            return
        }

        val status = when (action) {
            ACTION_SMS_SENT -> {
                if (resultCode == Activity.RESULT_OK) {
                    SmsStateRegistry.store.markPartSent(messageId, partIndex, callbackToken)
                } else {
                    SmsStateRegistry.store.markFailed(
                        messageId,
                        SmsErrorMapper.codeName(resultCode),
                        SmsErrorMapper.message(resultCode),
                        callbackToken,
                    )
                }
            }

            ACTION_SMS_DELIVERED -> {
                val deliveryStatus = extractDeliveryStatus(intent)
                when (smsDeliveryOutcome(deliveryStatus)) {
                    SmsDeliveryOutcome.DELIVERED ->
                        SmsStateRegistry.store.markPartDelivered(messageId, partIndex, callbackToken)
                    SmsDeliveryOutcome.PERMANENT_FAILURE -> SmsStateRegistry.store.markFailed(
                        messageId,
                        "DELIVERY_FAILED",
                        "Delivery failed (status=$deliveryStatus)",
                        callbackToken,
                    )
                    SmsDeliveryOutcome.TEMPORARY_FAILURE -> null
                }
            }

            else -> null
        }

        status?.let { emitStatus(it) }

        // Local API sends are the Portal's own too: owner-sent sync must never report them.
        if (action == ACTION_SMS_SENT && providerUri != null &&
            ConfigManager.getInstance(context).smsCloudEnabled
        ) {
            val pending = goAsync()
            SmsStore.getInstance(context).recordOwnSendAsync(providerUri, null) { pending.finish() }
        }
    }

    private fun onRelayResult(
        context: Context,
        intent: Intent,
        action: String,
        recordId: String,
        partIndex: Int,
        callbackToken: String,
        providerUri: String?,
    ) {
        val callback = when (action) {
            ACTION_SMS_SENT -> SmsSendCallback(
                sent = true,
                recordId = recordId,
                partIndex = partIndex,
                callbackToken = callbackToken,
                resultOk = resultCode == Activity.RESULT_OK,
                resultCode = resultCode,
                errorCode = intent.getIntExtra(EXTRA_ERROR_CODE, -1),
                providerUri = providerUri,
            )
            ACTION_SMS_DELIVERED -> {
                val deliveryStatus = extractDeliveryStatus(intent)
                SmsSendCallback(
                    sent = false,
                    recordId = recordId,
                    partIndex = partIndex,
                    callbackToken = callbackToken,
                    resultOk = true,
                    deliveryOutcome = smsDeliveryOutcome(deliveryStatus),
                    deliveryStatus = deliveryStatus,
                )
            }
            else -> return
        }
        val pending = goAsync()
        SmsStore.getInstance(context).applyCallbackAsync(callback) { pending.finish() }
    }

    private fun extractDeliveryStatus(intent: Intent): Int? {
        val pdu = intent.getByteArrayExtra("pdu") ?: return null
        val format = intent.getStringExtra("format")
        return runCatching {
            smsDeliveryStatus(SmsMessage.createFromPdu(pdu, format).status, format)
        }.getOrNull()
    }

    companion object {
        const val ACTION_SMS_SENT = "com.mobilerun.portal.sms.SENT"
        const val ACTION_SMS_DELIVERED = "com.mobilerun.portal.sms.DELIVERED"
        const val EXTRA_MESSAGE_ID = "message_id"
        const val EXTRA_PART_INDEX = "part_index"
        const val EXTRA_CALLBACK_TOKEN = "callback_token"
        const val EXTRA_ORIGIN = "origin"

        // Fill-ins the telephony framework adds to sent intents.
        const val EXTRA_PROVIDER_URI = "uri"
        const val EXTRA_ERROR_CODE = "errorCode"

        fun emitStatus(status: SmsSendStatus) {
            EventHub.emit(
                PortalEvent(
                    type = EventType.SMS_SEND_STATE_CHANGED,
                    payload = status.toJson(),
                ),
            )
        }
    }
}
