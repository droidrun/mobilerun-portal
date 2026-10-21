package com.mobilerun.portal.service

import android.view.accessibility.AccessibilityNodeInfo

/** Fire OS dialog recognition uses system identity and this installation's label, never translated buttons. */
internal object TvMediaProjectionAutoAccept {
    const val SYSTEM_UI = "com.android.systemui"

    fun isPermissionWindow(packageName: String?, className: String): Boolean =
        packageName == SYSTEM_UI && className.endsWith(".media.MediaProjectionPermissionActivity")

    fun tryAccept(root: AccessibilityNodeInfo, windowClass: String, appLabel: String): Boolean {
        if (!isPermissionWindow(root.packageName?.toString(), windowClass) || appLabel.isBlank()) return false
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
}
