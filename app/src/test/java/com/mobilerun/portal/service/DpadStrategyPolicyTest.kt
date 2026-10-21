package com.mobilerun.portal.service

import android.accessibilityservice.AccessibilityService
import android.media.session.PlaybackState
import android.view.KeyEvent
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DpadStrategyPolicyTest {
    @Test
    fun mapsAllSupportedKeysAndLeavesExistingSystemAndTextKeysAlone() {
        val expected = mapOf(
            KeyEvent.KEYCODE_DPAD_UP to DpadKeyAction.UP, KeyEvent.KEYCODE_DPAD_DOWN to DpadKeyAction.DOWN, KeyEvent.KEYCODE_DPAD_LEFT to DpadKeyAction.LEFT, KeyEvent.KEYCODE_DPAD_RIGHT to DpadKeyAction.RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER to DpadKeyAction.ACTIVATE,
            KeyEvent.KEYCODE_ESCAPE to DpadKeyAction.BACK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to DpadKeyAction.PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY to DpadKeyAction.PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE to DpadKeyAction.PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT to DpadKeyAction.NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS to DpadKeyAction.PREVIOUS,
            KeyEvent.KEYCODE_VOLUME_UP to DpadKeyAction.VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN to DpadKeyAction.VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE to DpadKeyAction.VOLUME_MUTE,
        )
        expected.forEach { (key, action) -> assertEquals("key $key", action, DpadStrategyPolicy.actionFor(key)) }
        listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, -1).forEach { assertNull(DpadStrategyPolicy.actionFor(it)) }
    }

    @Test
    fun api33TriesGlobalThenEditorImeThenFocusWhileOlderDevicesSkipGlobal() {
        assertEquals(
            listOf(DpadNavigationStrategy.GLOBAL_ACTION, DpadNavigationStrategy.IME, DpadNavigationStrategy.FOCUS),
            DpadStrategyPolicy.navigationStrategies(DpadKeyAction.ACTIVATE, 33, focusedEditable = true, imeConnected = true),
        )
        assertEquals(
            listOf(DpadNavigationStrategy.IME, DpadNavigationStrategy.FOCUS),
            DpadStrategyPolicy.navigationStrategies(DpadKeyAction.ACTIVATE, 30, focusedEditable = true, imeConnected = true),
        )
        for ((editable, connected) in listOf(false to false, false to true, true to false)) {
            assertEquals(listOf(DpadNavigationStrategy.FOCUS), DpadStrategyPolicy.navigationStrategies(DpadKeyAction.ACTIVATE, 32, editable, connected))
        }
    }

    @Test
    fun directionsAndCenterUseMatchingPlatformActions() {
        val expected = listOf(
            Triple(DpadKeyAction.UP, View.FOCUS_UP, AccessibilityService.GLOBAL_ACTION_DPAD_UP),
            Triple(DpadKeyAction.DOWN, View.FOCUS_DOWN, AccessibilityService.GLOBAL_ACTION_DPAD_DOWN),
            Triple(DpadKeyAction.LEFT, View.FOCUS_LEFT, AccessibilityService.GLOBAL_ACTION_DPAD_LEFT),
            Triple(DpadKeyAction.RIGHT, View.FOCUS_RIGHT, AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT),
        )
        expected.forEach { (action, focus, global) ->
            assertEquals(focus, DpadStrategyPolicy.focusDirection(action))
            assertEquals(global, DpadStrategyPolicy.globalAction(action))
        }
        assertEquals(AccessibilityService.GLOBAL_ACTION_DPAD_CENTER, DpadStrategyPolicy.globalAction(DpadKeyAction.ACTIVATE))
        assertNull(DpadStrategyPolicy.focusDirection(DpadKeyAction.ACTIVATE))
    }

    @Test
    fun editableArrowsUseOnlyImeAndNeverGlobalOrFocusNavigation() {
        for (action in listOf(DpadKeyAction.UP, DpadKeyAction.DOWN, DpadKeyAction.LEFT, DpadKeyAction.RIGHT)) {
            for (sdk in listOf(30, 33)) {
                assertEquals(emptyList<DpadNavigationStrategy>(), DpadStrategyPolicy.navigationStrategies(action, sdk, true, false))
                assertEquals(listOf(DpadNavigationStrategy.IME), DpadStrategyPolicy.navigationStrategies(action, sdk, true, true))
            }
        }
    }

    @Test
    fun mediaPrefersFirstPlayingSessionOtherwiseFirstInSystemActivityOrder() {
        assertEquals(1, DpadStrategyPolicy.mediaTargetIndex(listOf(PlaybackState.STATE_PAUSED, PlaybackState.STATE_PLAYING, PlaybackState.STATE_PLAYING)))
        assertEquals(0, DpadStrategyPolicy.mediaTargetIndex(listOf(null, PlaybackState.STATE_PAUSED)))
        assertNull(DpadStrategyPolicy.mediaTargetIndex(emptyList()))
    }
}
