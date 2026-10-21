package com.mobilerun.portal.service

import android.view.accessibility.AccessibilityNodeInfo

/** Fire OS consent is a separate AlertDialog window; only its root identity/content is authoritative. */
internal object TvMediaProjectionAutoAccept {
    fun tryAccept(root: AccessibilityNodeInfo, appLabel: String, armed: Boolean): Boolean {
        if (!armed || root.packageName?.toString() != MediaProjectionAutoAccept.SYSTEM_UI_PACKAGE ||
            appLabel.isBlank()
        ) return false
        val positive = root.findAccessibilityNodeInfosByViewId("android:id/button1").orEmpty()
        val labels = listOf("com.android.systemui:id/dialog_title", "android:id/message")
            .flatMap { root.findAccessibilityNodeInfosByViewId(it).orEmpty() }
        try {
            if (labels.none { it.text?.contains(appLabel) == true }) return false
            return positive.firstOrNull { it.isEnabled }
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        } finally {
            (positive + labels).forEach { it.recycle() }
        }
    }

    // Do not pass a rejected static Fire OS dialog to the permissive legacy text matcher.
    // Retain the existing multi-step fallback for explicitly identified newer share-mode UIs.
    fun hasShareModeUi(root: AccessibilityNodeInfo): Boolean {
        val nodes = listOf(MediaProjectionAutoAccept.DIALOG_VIEW_ID, MediaProjectionAutoAccept.SHARE_MODE_OPTIONS_VIEW_ID)
            .flatMap { root.findAccessibilityNodeInfosByViewId(it).orEmpty() }
        return try { nodes.isNotEmpty() } finally { nodes.forEach { it.recycle() } }
    }
}
