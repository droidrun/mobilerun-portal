package com.mobilerun.portal.auth

import android.content.Context
import android.content.Intent
import com.mobilerun.portal.config.ConfigManager
import com.mobilerun.portal.service.ReverseConnectionService

object SessionCredential {
    const val TYPE = "session"

    fun shouldInvalidate(credentialType: String, httpStatus: Int? = null, nullSession: Boolean = false) =
        credentialType == TYPE && (httpStatus == 401 || nullSession)

    /** Compare the checked credential before clearing: late responses must not clear a newer login. */
    @Synchronized fun clear(context: Context, expectedToken: String): Boolean {
        val config = ConfigManager.getInstance(context)
        if (config.reverseConnectionCredentialType != TYPE ||
            config.reverseConnectionToken != expectedToken) return false
        config.reverseConnectionToken = ""
        config.reverseConnectionEnabled = false
        context.stopService(Intent(context, ReverseConnectionService::class.java))
        return true
    }
}
