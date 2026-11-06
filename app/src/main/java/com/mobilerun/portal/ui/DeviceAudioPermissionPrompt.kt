package com.mobilerun.portal.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.mobilerun.portal.config.ConfigManager
import com.mobilerun.portal.service.AutoAcceptGate

// Portal asks for Record audio on its own once, right after a stream's screen-share consent.
object DeviceAudioPermissionPrompt {
    internal fun shouldPrompt(
        sdk: Int,
        flagEnabled: Boolean,
        permissionGranted: Boolean,
        alreadyPrompted: Boolean,
    ): Boolean = sdk >= Build.VERSION_CODES.Q && flagEnabled && !permissionGranted && !alreadyPrompted

    // True when the caller should request RECORD_AUDIO now; marks the prompt as used and arms auto-accept.
    fun claimRequest(context: Context): Boolean {
        val config = ConfigManager.getInstance(context)
        val granted =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!shouldPrompt(Build.VERSION.SDK_INT, config.deviceAudioEnabled, granted, config.deviceAudioPermissionPrompted)) {
            return false
        }
        config.deviceAudioPermissionPrompted = true
        AutoAcceptGate.armRecordAudio()
        return true
    }
}
