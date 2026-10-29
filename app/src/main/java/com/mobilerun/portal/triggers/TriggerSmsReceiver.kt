package com.mobilerun.portal.triggers

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.mobilerun.portal.events.EventHub
import com.mobilerun.portal.events.model.EventType
import com.mobilerun.portal.events.model.PortalEvent
import org.json.JSONObject
import java.util.UUID

internal fun smsSubscriptionIdFromIntent(intent: Intent): Int? {
    return firstNonNegativeSmsIntentExtra(
        intent = intent,
        keys = arrayOf(
            SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
            "subscription",
            "sub_id",
        ),
    )
}

internal fun smsSubscriptionIdFromExtras(extras: Map<String, Any?>): Int? {
    return firstNonNegativeSmsExtra(
        extras = extras,
        SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
        "subscription",
        "sub_id",
    )
}

internal fun smsSimSlotFromIntent(intent: Intent): Int? {
    return firstNonNegativeSmsIntentExtra(
        intent = intent,
        keys = arrayOf(
            SubscriptionManager.EXTRA_SLOT_INDEX,
            "slot",
            "sim_slot",
        ),
    )
}

internal fun smsSimSlotFromExtras(extras: Map<String, Any?>): Int? {
    return firstNonNegativeSmsExtra(
        extras = extras,
        SubscriptionManager.EXTRA_SLOT_INDEX,
        "slot",
        "sim_slot",
    )
}

@Suppress("DEPRECATION")
private fun firstNonNegativeSmsIntentExtra(intent: Intent, keys: Array<String>): Int? {
    val extras = intent.extras ?: return null
    return keys
        .asSequence()
        .mapNotNull { key -> extras.get(key)?.toSmsNonNegativeIntOrNull() }
        .firstOrNull()
}

private fun firstNonNegativeSmsExtra(extras: Map<String, Any?>, vararg keys: String): Int? {
    return keys
        .asSequence()
        .mapNotNull { key -> extras[key]?.toSmsNonNegativeIntOrNull() }
        .firstOrNull()
}

private fun Any.toSmsNonNegativeIntOrNull(): Int? {
    val parsed = when (this) {
        is Int -> this
        is Long -> takeIf { it >= Int.MIN_VALUE.toLong() && it <= Int.MAX_VALUE.toLong() }?.toInt()
        is Number -> {
            val asDouble = toDouble()
            asDouble
                .takeIf { it.isFinite() && it % 1.0 == 0.0 && it <= Int.MAX_VALUE }
                ?.toInt()
        }
        is String -> trim().toIntOrNull()
        else -> null
    }
    return parsed?.takeIf { it >= 0 }
}

class TriggerSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        TriggerRuntime.initialize(context)

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return
        val messageBody = messages.joinToString(separator = "") { it.messageBody.orEmpty() }
        val address = messages.firstOrNull()?.originatingAddress.orEmpty()
        val receivedAt = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
        val subscriptionId = smsSubscriptionIdFromIntent(intent)
        val simSlot = smsSimSlotFromIntent(intent) ?: subscriptionId?.let { resolveSimSlot(context, it) }

        val payload = JSONObject().apply {
            put("phone_number", address)
            put("message", messageBody)
            put("message_id", buildMessageId(address, receivedAt, messageBody))
            put("received_at", receivedAt)
            subscriptionId?.let {
                put("subscription_id", it)
            }
            simSlot?.let { put("sim_slot", it) }
            resolveContactName(context, address)?.let { put("contact_name", it) }
        }

        EventHub.emit(
            PortalEvent(
                type = EventType.SMS_RECEIVED,
                payload = payload,
            ),
        )
    }

    private fun buildMessageId(address: String, receivedAt: Long, body: String): String {
        val source = "$address|$receivedAt|$body"
        return UUID.nameUUIDFromBytes(source.toByteArray(Charsets.UTF_8)).toString()
    }

    private fun resolveSimSlot(context: Context, subscriptionId: Int): Int? {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return null
        return try {
            val manager = context.getSystemService(SubscriptionManager::class.java)
            manager?.activeSubscriptionInfoList
                ?.firstOrNull { it.subscriptionId == subscriptionId }
                ?.simSlotIndex
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveContactName(context: Context, phoneNumber: String): String? {
        if (phoneNumber.isBlank()) return null
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return null

        val uri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon()
            .appendPath(phoneNumber)
            .build()
        val cursor: Cursor? = context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null,
            null,
            null,
        )
        cursor.use {
            if (it != null && it.moveToFirst()) {
                return it.getString(0)
            }
        }
        return null
    }
}
