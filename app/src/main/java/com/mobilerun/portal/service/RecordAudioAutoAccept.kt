package com.mobilerun.portal.service

import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

// Auto-accepts Portal's own Record audio prompt: "While using the app", or Allow on Android 10.
object RecordAudioAutoAccept {
    private const val TAG = "RecordAudioAutoAccept"
    private const val FAILURE_LOG_COOLDOWN_MS = 10_000L
    private const val MAX_SEARCH_NODES = 120

    private const val GRANT_PERMISSIONS_ACTIVITY = "GrantPermissionsActivity"
    private const val MESSAGE_ID_SUFFIX = ":id/permission_message"
    private const val FOREGROUND_ONLY_BUTTON_ID_SUFFIX = ":id/permission_allow_foreground_only_button"
    private const val ALLOW_BUTTON_ID_SUFFIX = ":id/permission_allow_button"

    private val PERMISSION_CONTROLLER_PACKAGES = setOf(
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
    )

    private var lastFailureLogAtMs = 0L

    sealed class AutoAcceptResult {
        object NoAction : AutoAcceptResult()
        object ActionPerformed : AutoAcceptResult()
        // Portal's prompt is up but its button is not clickable yet, e.g. still animating in.
        object NotReady : AutoAcceptResult()
        data class Failed(val reason: String) : AutoAcceptResult()
    }

    internal enum class Target {
        NONE,
        FOREGROUND_ONLY,
        ALLOW,
    }

    // Never "Only this time" or "Don't allow", and never another app's prompt. Android 11+ always
    // offers "While using the app" for Record audio, so a plain Allow there belongs to another permission.
    internal fun chooseTarget(
        sdk: Int,
        packageName: String,
        message: String?,
        appLabel: String,
        hasForegroundOnlyButton: Boolean,
        hasAllowButton: Boolean,
    ): Target = when {
        packageName !in PERMISSION_CONTROLLER_PACKAGES -> Target.NONE
        appLabel.isBlank() || message == null || !message.contains(appLabel) -> Target.NONE
        hasForegroundOnlyButton -> Target.FOREGROUND_ONLY
        sdk == Build.VERSION_CODES.Q && hasAllowButton -> Target.ALLOW
        else -> Target.NONE
    }

    fun isPermissionDialog(
        event: AccessibilityEvent?,
        eventClassName: String? = null,
    ): Boolean {
        if (event == null) return false
        val className = eventClassName ?: event.className?.toString().orEmpty()
        if (className.contains(GRANT_PERMISSIONS_ACTIVITY)) return true
        return event.packageName?.toString() in PERMISSION_CONTROLLER_PACKAGES
    }

    fun tryAutoAccept(
        rootNode: AccessibilityNodeInfo?,
        appLabel: String,
        sdk: Int = Build.VERSION.SDK_INT,
    ): AutoAcceptResult {
        if (rootNode == null) return AutoAcceptResult.NoAction
        val packageName = rootNode.packageName?.toString().orEmpty()
        if (packageName !in PERMISSION_CONTROLLER_PACKAGES) return AutoAcceptResult.NoAction

        var message: String? = null
        var foregroundOnlyButton: AccessibilityNodeInfo? = null
        var allowButton: AccessibilityNodeInfo? = null
        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.add(rootNode)
        var count = 0
        while (queue.isNotEmpty() && count < MAX_SEARCH_NODES) {
            val node = queue.removeFirst()
            count += 1
            val viewId = node.viewIdResourceName.orEmpty()
            var keep = false
            when {
                message == null && viewId.endsWith(MESSAGE_ID_SUFFIX) ->
                    message = node.text?.toString()?.takeIf { it.isNotEmpty() } ?: node.contentDescription?.toString()
                foregroundOnlyButton == null && viewId.endsWith(FOREGROUND_ONLY_BUTTON_ID_SUFFIX) -> {
                    foregroundOnlyButton = node
                    keep = true
                }
                allowButton == null && viewId.endsWith(ALLOW_BUTTON_ID_SUFFIX) -> {
                    allowButton = node
                    keep = true
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
            if (!keep && node != rootNode) node.recycle()
        }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node != rootNode) node.recycle()
        }

        val target = chooseTarget(
            sdk = sdk,
            packageName = packageName,
            message = message,
            appLabel = appLabel,
            hasForegroundOnlyButton = foregroundOnlyButton != null,
            hasAllowButton = allowButton != null,
        )
        try {
            val button = when (target) {
                Target.FOREGROUND_ONLY -> foregroundOnlyButton
                Target.ALLOW -> allowButton
                Target.NONE -> null
            } ?: return AutoAcceptResult.NoAction
            // A hidden or disabled choice waits; it never falls back to another button.
            if (!button.isClickable || !button.isEnabled || !button.isVisibleToUser) return AutoAcceptResult.NotReady
            val clicked = try {
                button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } catch (e: Exception) {
                false
            }
            if (!clicked) {
                logFailure("allow button click failed")
                return AutoAcceptResult.Failed("allow button click failed")
            }
            AutoAcceptGate.disarmRecordAudio()
            Log.i(TAG, "Record audio prompt accepted (${target.name.lowercase()})")
            return AutoAcceptResult.ActionPerformed
        } finally {
            foregroundOnlyButton?.let { if (it != rootNode) it.recycle() }
            allowButton?.let { if (it != rootNode) it.recycle() }
        }
    }

    private fun logFailure(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastFailureLogAtMs < FAILURE_LOG_COOLDOWN_MS) return
        lastFailureLogAtMs = now
        Log.w(TAG, "Auto-accept failed: $reason")
    }
}
