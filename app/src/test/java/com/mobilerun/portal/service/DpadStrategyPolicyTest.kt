package com.mobilerun.portal.service

import android.accessibilityservice.AccessibilityService
import android.media.session.PlaybackState
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DpadStrategyPolicyTest {
    @Test
    fun mapsAllSupportedKeysAndLeavesExistingSystemAndTextKeysAlone() {
        val expected = mapOf(
            19 to DpadKeyAction.UP, 20 to DpadKeyAction.DOWN, 21 to DpadKeyAction.LEFT, 22 to DpadKeyAction.RIGHT,
            23 to DpadKeyAction.ACTIVATE, 66 to DpadKeyAction.ACTIVATE, 160 to DpadKeyAction.ACTIVATE,
            111 to DpadKeyAction.BACK,
            85 to DpadKeyAction.PLAY_PAUSE, 126 to DpadKeyAction.PLAY, 127 to DpadKeyAction.PAUSE,
            87 to DpadKeyAction.NEXT, 88 to DpadKeyAction.PREVIOUS,
            24 to DpadKeyAction.VOLUME_UP, 25 to DpadKeyAction.VOLUME_DOWN, 164 to DpadKeyAction.VOLUME_MUTE,
        )
        expected.forEach { (key, action) -> assertEquals("key $key", action, DpadStrategyPolicy.actionFor(key)) }
        listOf(4, 3, 187, 67, 112, 61, 29, -1).forEach { assertNull(DpadStrategyPolicy.actionFor(it)) }
    }

    @Test
    fun api33TriesGlobalThenEditorImeThenFocusWhileOlderDevicesSkipGlobal() {
        assertEquals(
            listOf(DpadNavigationStrategy.GLOBAL_ACTION, DpadNavigationStrategy.IME, DpadNavigationStrategy.FOCUS),
            DpadStrategyPolicy.navigationStrategies(33, focusedEditable = true, imeConnected = true),
        )
        assertEquals(
            listOf(DpadNavigationStrategy.IME, DpadNavigationStrategy.FOCUS),
            DpadStrategyPolicy.navigationStrategies(30, focusedEditable = true, imeConnected = true),
        )
        for ((editable, connected) in listOf(false to false, false to true, true to false)) {
            assertEquals(listOf(DpadNavigationStrategy.FOCUS), DpadStrategyPolicy.navigationStrategies(32, editable, connected))
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
    fun mediaPrefersFirstPlayingSessionOtherwiseFirstInSystemActivityOrder() {
        assertEquals(1, DpadStrategyPolicy.mediaTargetIndex(listOf(PlaybackState.STATE_PAUSED, PlaybackState.STATE_PLAYING, PlaybackState.STATE_PLAYING)))
        assertEquals(0, DpadStrategyPolicy.mediaTargetIndex(listOf(null, PlaybackState.STATE_PAUSED)))
        assertNull(DpadStrategyPolicy.mediaTargetIndex(emptyList()))
    }
}
