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
) : SmsSender {
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
                mutable = false,
            )
            deliveredIntents?.add(
                resultIntent(
                    action = SmsSendResultReceiver.ACTION_SMS_DELIVERED,
                    messageId = request.messageId,
                    partIndex = index,
                    callbackToken = callbackToken,
                    mutable = true,
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

    private fun resultIntent(
        action: String,
        messageId: String,
        partIndex: Int,
        callbackToken: String,
        mutable: Boolean,
    ): PendingIntent {
        val intent = Intent(context, SmsSendResultReceiver::class.java)
            .setAction(action)
            .setData(
                Uri.parse(
                    "mobilerun-sms-result://${Uri.encode(action)}/${Uri.encode(messageId)}/$partIndex/${Uri.encode(callbackToken)}",
                ),
            )
            .putExtra(SmsSendResultReceiver.EXTRA_MESSAGE_ID, messageId)
            .putExtra(SmsSendResultReceiver.EXTRA_PART_INDEX, partIndex)
            .putExtra(SmsSendResultReceiver.EXTRA_CALLBACK_TOKEN, callbackToken)
        val mutabilityFlag = if (mutable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        } else {
            PendingIntent.FLAG_IMMUTABLE
        }
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
