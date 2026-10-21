package com.mobilerun.portal.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mobilerun.portal.auth.SessionCredential
import com.mobilerun.portal.config.ConfigManager
import com.mobilerun.portal.service.ReverseConnectionService

class TvBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val config = ConfigManager.getInstance(context)
        if (SessionCredential.isActive(config)) {
            ReverseConnectionService.start(context)
        }
    }
}
