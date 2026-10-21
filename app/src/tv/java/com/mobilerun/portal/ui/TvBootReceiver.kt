package com.mobilerun.portal.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mobilerun.portal.auth.SessionCredential
import com.mobilerun.portal.config.ConfigManager
import com.mobilerun.portal.service.ReverseConnectionService

class TvBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val config = ConfigManager.getInstance(context)
        if (config.reverseConnectionCredentialType == SessionCredential.TYPE && config.reverseConnectionToken.isNotBlank()) {
            ReverseConnectionService.start(context)
        }
    }
}
