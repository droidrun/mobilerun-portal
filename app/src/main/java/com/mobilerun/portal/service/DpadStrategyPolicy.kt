package com.mobilerun.portal.service

import android.accessibilityservice.AccessibilityService
import android.media.session.PlaybackState
import android.view.KeyEvent
import android.view.View

internal enum class DpadKeyAction {
    UP, DOWN, LEFT, RIGHT, ACTIVATE, BACK,
    PLAY_PAUSE, PLAY, PAUSE, NEXT, PREVIOUS,
    VOLUME_UP, VOLUME_DOWN, VOLUME_MUTE,
}

internal enum class DpadNavigationStrategy { GLOBAL_ACTION, IME, FOCUS }

internal object DpadStrategyPolicy {
    fun actionFor(keyCode: Int): DpadKeyAction? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> DpadKeyAction.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> DpadKeyAction.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> DpadKeyAction.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> DpadKeyAction.RIGHT
        KeyEvent.KEYCODE_DPAD_CENTER -> DpadKeyAction.ACTIVATE
        KeyEvent.KEYCODE_ESCAPE -> DpadKeyAction.BACK
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> DpadKeyAction.PLAY_PAUSE
        KeyEvent.KEYCODE_MEDIA_PLAY -> DpadKeyAction.PLAY
        KeyEvent.KEYCODE_MEDIA_PAUSE -> DpadKeyAction.PAUSE
        KeyEvent.KEYCODE_MEDIA_NEXT -> DpadKeyAction.NEXT
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> DpadKeyAction.PREVIOUS
        KeyEvent.KEYCODE_VOLUME_UP -> DpadKeyAction.VOLUME_UP
        KeyEvent.KEYCODE_VOLUME_DOWN -> DpadKeyAction.VOLUME_DOWN
        KeyEvent.KEYCODE_VOLUME_MUTE -> DpadKeyAction.VOLUME_MUTE
        else -> null
    }

    // Execute in order, stopping at the first successful strategy.
    fun navigationStrategies(action: DpadKeyAction, sdkInt: Int, focusedEditable: Boolean, imeConnected: Boolean): List<DpadNavigationStrategy> =
        buildList {
            // Arrow keys must stay in an editor, including when IME delivery fails.
            if (focusedEditable && focusDirection(action) != null) {
                if (imeConnected) add(DpadNavigationStrategy.IME)
                return@buildList
            }
            if (sdkInt >= 33) add(DpadNavigationStrategy.GLOBAL_ACTION)
            if (focusedEditable && imeConnected) add(DpadNavigationStrategy.IME)
            add(DpadNavigationStrategy.FOCUS)
        }

    fun globalAction(action: DpadKeyAction): Int? = when (action) {
        DpadKeyAction.UP -> AccessibilityService.GLOBAL_ACTION_DPAD_UP
        DpadKeyAction.DOWN -> AccessibilityService.GLOBAL_ACTION_DPAD_DOWN
        DpadKeyAction.LEFT -> AccessibilityService.GLOBAL_ACTION_DPAD_LEFT
        DpadKeyAction.RIGHT -> AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT
        DpadKeyAction.ACTIVATE -> AccessibilityService.GLOBAL_ACTION_DPAD_CENTER
        else -> null
    }

    fun focusDirection(action: DpadKeyAction): Int? = when (action) {
        DpadKeyAction.UP -> View.FOCUS_UP
        DpadKeyAction.DOWN -> View.FOCUS_DOWN
        DpadKeyAction.LEFT -> View.FOCUS_LEFT
        DpadKeyAction.RIGHT -> View.FOCUS_RIGHT
        else -> null
    }

    // MediaSessionManager returns sessions in system priority order (most active first).
    fun mediaTargetIndex(playbackStates: List<Int?>): Int? {
        if (playbackStates.isEmpty()) return null
        return playbackStates.indexOfFirst { it == PlaybackState.STATE_PLAYING }.takeIf { it >= 0 } ?: 0
    }
}
