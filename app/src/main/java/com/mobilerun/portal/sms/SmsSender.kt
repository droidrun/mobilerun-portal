package com.mobilerun.portal.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import java.util.concurrent.atomic.AtomicInteger

interface SmsSender {
    fun divideMessage(message: String, subscriptionId: Int?): List<String>
    fun sendMultipart(
        request: SmsSendRequest,
        parts: List<String>,
        resolvedSubscriptionId: Int?,
        callbackToken: String,
    )
}

class AndroidSmsSender(
    private val context: Context,
) : SmsSender, SmsBridgeSender {
    override fun divideMessage(message: String, subscriptionId: Int?): List<String> {
        val parts = smsManager(subscriptionId).divideMessage(message).orEmpty()
        return parts.ifEmpty { listOf(message) }
    }

    override fun sendMultipart(
        request: SmsSendRequest,
        parts: List<String>,
        resolvedSubscriptionId: Int?,
        callbackToken: String,
    ) {
        val sentIntents = ArrayList<PendingIntent>(parts.size)
        val deliveredIntents =
            if (request.deliveryReport) ArrayList<PendingIntent>(parts.size) else null

        parts.forEachIndexed { index, _ ->
            sentIntents += resultIntent(
                action = SmsSendResultReceiver.ACTION_SMS_SENT,
                messageId = request.messageId,
                partIndex = index,
                callbackToken = callbackToken,
            )
            deliveredIntents?.add(
                resultIntent(
                    action = SmsSendResultReceiver.ACTION_SMS_DELIVERED,
                    messageId = request.messageId,
                    partIndex = index,
                    callbackToken = callbackToken,
                ),
            )
        }

        smsManager(resolvedSubscriptionId).sendMultipartTextMessage(
            request.phoneNumber,
            null,
            ArrayList(parts),
            sentIntents,
            deliveredIntents,
        )
    }

    override fun sendBridgeMultipart(
        recordId: String,
        to: String,
        parts: List<String>,
        subscriptionId: Int?,
        deliveryReport: Boolean,
        callbackToken: String,
    ) {
        val sentIntents = ArrayList<PendingIntent>(parts.size)
        val deliveredIntents = if (deliveryReport) ArrayList<PendingIntent>(parts.size) else null
        parts.indices.forEach { index ->
            sentIntents += resultIntent(
                action = SmsSendResultReceiver.ACTION_SMS_SENT,
                messageId = recordId,
                partIndex = index,
                callbackToken = callbackToken,
                origin = SmsSendRecord.ORIGIN_RELAY,
            )
            deliveredIntents?.add(
                resultIntent(
                    action = SmsSendResultReceiver.ACTION_SMS_DELIVERED,
                    messageId = recordId,
                    partIndex = index,
                    callbackToken = callbackToken,
                    origin = SmsSendRecord.ORIGIN_RELAY,
                ),
            )
        }
        smsManager(subscriptionId).sendMultipartTextMessage(
            to,
            null,
            ArrayList(parts),
            sentIntents,
            deliveredIntents,
        )
    }

    private fun smsManager(subscriptionId: Int?): SmsManager {
        @Suppress("DEPRECATION")
        return if (subscriptionId != null) {
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
        } else {
            SmsManager.getDefault()
        }
    }

    // Mutable (explicit component) so the framework's 'uri' and 'errorCode' fill-ins arrive on API 31+.
    private fun resultIntent(
        action: String,
        messageId: String,
        partIndex: Int,
        callbackToken: String,
        origin: String? = null,
    ): PendingIntent {
        val scheme = if (origin == null) "mobilerun-sms-result" else "mobilerun-sms-$origin-result"
        val intent = Intent(context, SmsSendResultReceiver::class.java)
            .setAction(action)
            .setData(
                Uri.parse(
                    "$scheme://${Uri.encode(action)}/${Uri.encode(messageId)}/$partIndex/${Uri.encode(callbackToken)}",
                ),
            )
            .putExtra(SmsSendResultReceiver.EXTRA_MESSAGE_ID, messageId)
            .putExtra(SmsSendResultReceiver.EXTRA_PART_INDEX, partIndex)
            .putExtra(SmsSendResultReceiver.EXTRA_CALLBACK_TOKEN, callbackToken)
        origin?.let { intent.putExtra(SmsSendResultReceiver.EXTRA_ORIGIN, it) }
        val mutabilityFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            context,
            RequestCodes.next(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutabilityFlag,
        )
    }

    private object RequestCodes {
        private val next = AtomicInteger(10_000)
        fun next(): Int = next.getAndIncrement()
    }
}
