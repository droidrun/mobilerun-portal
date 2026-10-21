package com.mobilerun.portal.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.mobilerun.portal.core.AccessibilityTraversalGuard
import com.mobilerun.portal.input.MobilerunKeyboardIME

internal data class DpadKeyResult(val success: Boolean, val message: String)

/** Shared fallback for keys that cannot be delivered through the existing text input paths. */
internal object DpadController {
    private const val TAG = "DpadController"

    fun handleKey(context: Context, keyCode: Int): DpadKeyResult? {
        val action = DpadStrategyPolicy.actionFor(keyCode) ?: return null
        return try {
            when (action) {
                DpadKeyAction.BACK -> {
                    val success = GestureController.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    DpadKeyResult(success, if (success) "Key handled" else "Unable to perform Back")
                }
                DpadKeyAction.PLAY_PAUSE -> media(context) {
                    if (it.playbackState?.state == PlaybackState.STATE_PLAYING) it.transportControls.pause()
                    else it.transportControls.play()
                }
                DpadKeyAction.PLAY -> media(context) { it.transportControls.play() }
                DpadKeyAction.PAUSE -> media(context) { it.transportControls.pause() }
                DpadKeyAction.NEXT -> media(context) { it.transportControls.skipToNext() }
                DpadKeyAction.PREVIOUS -> media(context) { it.transportControls.skipToPrevious() }
                DpadKeyAction.VOLUME_UP -> volume(context, AudioManager.ADJUST_RAISE)
                DpadKeyAction.VOLUME_DOWN -> volume(context, AudioManager.ADJUST_LOWER)
                DpadKeyAction.VOLUME_MUTE -> volume(context, AudioManager.ADJUST_TOGGLE_MUTE)
                else -> navigate(context, action, keyCode)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to handle key code: $keyCode", e)
            DpadKeyResult(false, "Unable to handle key code: $keyCode")
        }
    }

    /** Enter reaches this only after its existing IME/accessibility/newline attempts fail. */
    fun activate(): DpadKeyResult = try {
        withFocusedNode { _, focused ->
            result(focused != null && !focused.isEditable && focused.isVisibleToUser && focused.isEnabled &&
                focused.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
    } catch (e: RuntimeException) {
        Log.w(TAG, "Unable to activate focused item", e)
        DpadKeyResult(false, "Unable to activate focused item")
    }

    private fun navigate(context: Context, action: DpadKeyAction, keyCode: Int): DpadKeyResult =
        withFocusedNode { root, focused ->
            if (focused == null && action == DpadKeyAction.ACTIVATE) {
                return@withFocusedNode focusAction(root, null, action)
            }
            val keyboard = MobilerunKeyboardIME.getInstance()
            val imeConnected = focused?.isEditable == true && keyboard?.hasInputConnection() == true &&
                MobilerunKeyboardIME.isSelected(context)
            val strategies = DpadStrategyPolicy.navigationStrategies(action, Build.VERSION.SDK_INT, focused?.isEditable == true, imeConnected)
            for (strategy in strategies) {
                val handled = when (strategy) {
                    DpadNavigationStrategy.GLOBAL_ACTION ->
                        DpadStrategyPolicy.globalAction(action)?.let(GestureController::performGlobalAction) == true
                    DpadNavigationStrategy.IME -> keyboard?.sendKeyEventDirect(keyCode) == true
                    DpadNavigationStrategy.FOCUS -> return@withFocusedNode focusAction(root, focused, action)
                }
                if (handled) return@withFocusedNode result(true)
            }
            DpadKeyResult(false, "Unsupported key code: $keyCode (no unicode mapping and IME not available)")
        }

    private inline fun withFocusedNode(
        block: (AccessibilityNodeInfo?, AccessibilityNodeInfo?) -> DpadKeyResult,
    ): DpadKeyResult {
        val service = MobilerunAccessibilityService.getInstance()
            ?: return DpadKeyResult(false, "Accessibility service not available")
        val root = service.rootInActiveWindow
        var focused: AccessibilityNodeInfo? = null
        return try {
            focused = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            block(root, focused)
        } finally {
            if (focused !== root) focused?.recycle()
            root?.recycle()
        }
    }

    private fun focusAction(root: AccessibilityNodeInfo?, focused: AccessibilityNodeInfo?, action: DpadKeyAction): DpadKeyResult {
        if (focused == null) {
            return result(root != null && focusFirst(root, depth = 0))
        }
        if (action == DpadKeyAction.ACTIVATE) return result(click(focused))
        val direction = DpadStrategyPolicy.focusDirection(action) ?: return result(false)
        val next = focused.focusSearch(direction) ?: return result(false)
        return try {
            result(next.isVisibleToUser && next.isEnabled && next.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
        } finally {
            if (next !== focused && next !== root) next.recycle()
        }
    }

    private fun focusFirst(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (AccessibilityTraversalGuard.isTooDeep(depth)) return false
        if (node.isFocusable && node.isVisibleToUser && node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
            return true
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                if (focusFirst(child, depth + 1)) return true
            } finally {
                child.recycle()
            }
        }
        return false
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser || !node.isEnabled) return false
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return !bounds.isEmpty && GestureController.tap(bounds.centerX(), bounds.centerY())
    }

    private inline fun media(context: Context, action: (MediaController) -> Unit): DpadKeyResult {
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: return DpadKeyResult(false, "Media session service not available")
        return try {
            val sessions = manager.getActiveSessions(ComponentName(context, MobilerunNotificationListener::class.java))
            val targetIndex = DpadStrategyPolicy.mediaTargetIndex(sessions.map { it.playbackState?.state })
                ?: return DpadKeyResult(false, "No active media session")
            action(sessions[targetIndex])
            result(true)
        } catch (_: SecurityException) {
            DpadKeyResult(false, "Notification listener access is required for media keys")
        }
    }

    private fun volume(context: Context, adjustment: Int): DpadKeyResult {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return DpadKeyResult(false, "Audio service not available")
        manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, adjustment, AudioManager.FLAG_SHOW_UI)
        return result(true)
    }

    private fun result(success: Boolean) = DpadKeyResult(success, if (success) "Key handled" else "Unable to move or activate focus")
}
