package com.mobilerun.portal.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mobilerun.portal.api.ApiResponse
import com.mobilerun.portal.config.ConfigManager
import org.json.JSONObject

internal fun shouldResumeReverseConnectionAfterRestart(
    reconnectAfterRestartEnabled: Boolean,
    wasEnabled: Boolean,
    hasCloudCredentials: Boolean,
    serviceRunning: Boolean,
): Boolean = reconnectAfterRestartEnabled && wasEnabled && hasCloudCredentials && !serviceRunning

/** A sticky restart has no intent and only reconnects when the connection was left on. */
internal fun shouldStopStickyRestart(hasIntent: Boolean, wasEnabled: Boolean): Boolean =
    !hasIntent && !wasEnabled

/** Returns true when it started the service; [startPlainService] must not set an action. */
internal fun resumeReverseConnectionAfterRestart(
    action: String?,
    configManager: ConfigManager,
    serviceRunning: Boolean,
    startPlainService: () -> Unit,
): Boolean {
    if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
        return false
    }
    val resume = shouldResumeReverseConnectionAfterRestart(
        reconnectAfterRestartEnabled = configManager.reconnectAfterRestartEnabled,
        wasEnabled = configManager.reverseConnectionWasEnabled,
        hasCloudCredentials = configManager.hasCloudCredentials,
        serviceRunning = serviceRunning,
    )
    if (!resume) return false
    configManager.reverseConnectionEnabled = true
    startPlainService()
    return true
}

internal fun buildReconnectAfterRestartStatus(configManager: ConfigManager): ApiResponse =
    ApiResponse.RawObject(
        JSONObject().apply {
            put("enabled", configManager.reconnectAfterRestartEnabled)
            put("credentialsPresent", configManager.hasCloudCredentials)
        },
    )

internal fun setReconnectAfterRestart(configManager: ConfigManager, enabled: Boolean): ApiResponse {
    if (enabled && !configManager.hasCloudCredentials) {
        return ApiResponse.Error("Connect to Mobilerun first: no cloud credentials are saved")
    }
    configManager.reconnectAfterRestartEnabled = enabled
    return buildReconnectAfterRestartStatus(configManager)
}

/** Resumes the cloud connection after a reboot or app update when the user opted in. */
class ReverseConnectionRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        resumeReverseConnectionAfterRestart(
            action = action,
            configManager = ConfigManager.getInstance(context),
            serviceRunning = ReverseConnectionService.getInstance() != null,
        ) {
            Log.i(TAG, "Resuming cloud connection after $action")
            try {
                // A plain start keeps a stored HTTP 402 block; ACTION_RECONNECT would clear it.
                context.startForegroundService(Intent(context, ReverseConnectionService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Could not resume cloud connection after $action", e)
            }
        }
    }

    private companion object {
        const val TAG = "ReverseConnRestart"
    }
}
