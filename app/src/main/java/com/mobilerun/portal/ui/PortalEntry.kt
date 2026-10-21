package com.mobilerun.portal.ui

import android.content.Context
import android.content.Intent

object PortalEntry {
    fun mainIntent(context: Context): Intent = resolve(
        { context.packageManager.getLaunchIntentForPackage(context.packageName) },
        { context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName) },
        { Intent(context, MainActivity::class.java) },
    )

    internal fun <T> resolve(launch: () -> T?, leanback: () -> T?, fallback: () -> T): T =
        launch() ?: leanback() ?: fallback()
}
