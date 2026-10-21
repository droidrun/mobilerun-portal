package com.mobilerun.portal.service

import android.view.accessibility.AccessibilityEvent

/** Main records activity evidence independently of the worker's latest window-resolution event. */
internal class TvForegroundActivityTracker {
    data class Candidate(val packageName: String, val className: String)

    @Volatile private var latest: Candidate? = null

    fun onEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString().orEmpty()
        val cls = event.className?.toString().orEmpty()
        if (pkg.isNotEmpty() && cls.isNotEmpty() && !cls.startsWith("android.")) {
            latest = Candidate(pkg, cls)
        }
    }

    fun matching(packageName: String?): Candidate? = latest?.takeIf { it.packageName == packageName }
}
