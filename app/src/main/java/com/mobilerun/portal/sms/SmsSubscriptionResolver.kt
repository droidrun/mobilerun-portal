package com.mobilerun.portal.sms

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import org.json.JSONObject

data class SmsSubscriptionResolution(
    val subscriptionId: Int?,
    val simSlot: Int?,
    val error: String? = null,
)

class SmsSubscriptionResolver(
    private val context: Context,
) {
    fun resolve(subscriptionId: Int?, simSlot: Int?): SmsSubscriptionResolution {
        if (subscriptionId != null && simSlot != null) {
            return SmsSubscriptionResolution(
                subscriptionId = null,
                simSlot = null,
                error = "Specify only one of 'subscription_id' or 'sim_slot'",
            )
        }
        if (subscriptionId != null) {
            return SmsSubscriptionResolution(subscriptionId = subscriptionId, simSlot = simSlot)
        }
        if (simSlot == null) {
            return SmsSubscriptionResolution(subscriptionId = null, simSlot = null)
        }
        if (!hasReadPhoneState()) {
            return SmsSubscriptionResolution(
                subscriptionId = null,
                simSlot = simSlot,
                error = "READ_PHONE_STATE permission not granted",
            )
        }
        val subscription = activeSubscriptions().firstOrNull { it.simSlot == simSlot }
            ?: return SmsSubscriptionResolution(
                subscriptionId = null,
                simSlot = simSlot,
                error = "No active subscription for sim_slot=$simSlot",
            )
        return SmsSubscriptionResolution(
            subscriptionId = subscription.subscriptionId,
            simSlot = subscription.simSlot,
        )
    }

    fun capabilities(): JSONObject =
        JSONObject()
            .put("telephony_available", hasTelephony())
            .put("send_sms_granted", hasPermission(Manifest.permission.SEND_SMS))
            .put("read_sms_granted", hasPermission(Manifest.permission.READ_SMS))
            .put("receive_sms_granted", hasPermission(Manifest.permission.RECEIVE_SMS))
            .put("read_phone_state_granted", hasReadPhoneState())
            .put("active_subscriptions", activeSubscriptions().subscriptionsToJsonArray())

    fun hasTelephony(): Boolean {
        val packageManager = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)
        } else {
            @Suppress("DEPRECATION")
            packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        }
    }

    fun hasSendSms(): Boolean = hasPermission(Manifest.permission.SEND_SMS)

    private fun hasReadPhoneState(): Boolean = hasPermission(Manifest.permission.READ_PHONE_STATE)

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun activeSubscriptions(): List<SmsSubscription> {
        if (!hasReadPhoneState()) return emptyList()
        return try {
            val manager = context.getSystemService(SubscriptionManager::class.java)
            manager?.activeSubscriptionInfoList.orEmpty().map {
                SmsSubscription(
                    subscriptionId = it.subscriptionId,
                    simSlot = it.simSlotIndex,
                    carrierName = it.carrierName?.toString(),
                    displayName = it.displayName?.toString(),
                )
            }
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
