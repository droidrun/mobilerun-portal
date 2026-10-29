package com.mobilerun.portal.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
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

class SmsSendResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val messageId = intent.getStringExtra(EXTRA_MESSAGE_ID).orEmpty()
        if (messageId.isBlank()) return
        val partIndex = intent.getIntExtra(EXTRA_PART_INDEX, -1)
        if (partIndex < 0) return
        val callbackToken = intent.getStringExtra(EXTRA_CALLBACK_TOKEN).orEmpty()
        if (callbackToken.isBlank()) return

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
    }

    private fun extractDeliveryStatus(intent: Intent): Int? {
        val pdu = intent.getByteArrayExtra("pdu") ?: return null
        return runCatching {
            SmsMessage.createFromPdu(pdu, intent.getStringExtra("format")).status
        }.getOrNull()
    }

    companion object {
        const val ACTION_SMS_SENT = "com.mobilerun.portal.sms.SENT"
        const val ACTION_SMS_DELIVERED = "com.mobilerun.portal.sms.DELIVERED"
        const val EXTRA_MESSAGE_ID = "message_id"
        const val EXTRA_PART_INDEX = "part_index"
        const val EXTRA_CALLBACK_TOKEN = "callback_token"

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
