package com.mobilerun.portal.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.mobilerun.portal.config.ConfigManager

/**
 * Asks the user to exempt Portal from battery optimization while a local
 * server is enabled. Battery-optimized apps can be frozen or lose network
 * access on many devices, which makes the local servers stop answering.
 */
object BatteryOptimizationPrompt {
    private const val TAG = "BatteryOptimization"

    @Volatile
    private var promptedThisProcess = false

    fun shouldPrompt(localServerEnabled: Boolean, exempt: Boolean): Boolean =
        localServerEnabled && !exempt

    fun isExempt(context: Context): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return true
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Prompts once per app process if a local server is enabled. */
    fun promptOnceIfNeeded(activity: Activity) {
        if (promptedThisProcess) return
        val config = ConfigManager.getInstance(activity)
        val enabled = config.socketServerEnabled || config.websocketEnabled
        if (shouldPrompt(enabled, isExempt(activity))) {
            promptedThisProcess = true
            request(activity)
        }
    }

    /** Prompts right after the user enables a local server. */
    fun promptAfterEnable(activity: Activity) {
        if (shouldPrompt(localServerEnabled = true, exempt = isExempt(activity))) {
            promptedThisProcess = true
            request(activity)
        }
    }

    private fun request(activity: Activity) {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${activity.packageName}"),
        )
        try {
            activity.startActivity(direct)
        } catch (e: ActivityNotFoundException) {
            try {
                activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: ActivityNotFoundException) {
                Log.w(TAG, "No battery optimization settings screen: ${e2.message}")
            }
        }
    }
}
