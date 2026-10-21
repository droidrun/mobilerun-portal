package com.mobilerun.portal.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.mobilerun.portal.input.MobilerunKeyboardIME
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DpadControllerTest {
    private val context = mockk<Context>(relaxed = true)
    private val service = mockk<MobilerunAccessibilityService>(relaxed = true)
    private val root = mockk<AccessibilityNodeInfo>(relaxed = true)
    private val focused = mockk<AccessibilityNodeInfo>(relaxed = true)

    @Before
    fun setUp() {
        mockkObject(MobilerunAccessibilityService.Companion, MobilerunKeyboardIME.Companion, GestureController)
        every { MobilerunAccessibilityService.getInstance() } returns service
        every { MobilerunKeyboardIME.getInstance() } returns null
        every { service.rootInActiveWindow } returns root
        every { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns focused
        every { focused.isVisibleToUser } returns true
        every { focused.isEnabled } returns true
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun arrowsSearchInputFocusAndFocusTheNextNode() {
        val next = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { next.isVisibleToUser } returns true
        every { next.isEnabled } returns true
        every { next.performAction(AccessibilityNodeInfo.ACTION_FOCUS) } returns true
        for ((key, direction) in listOf(19 to View.FOCUS_UP, 20 to View.FOCUS_DOWN, 21 to View.FOCUS_LEFT, 22 to View.FOCUS_RIGHT)) {
            every { focused.focusSearch(direction) } returns next
            assertTrue(DpadController.handleKey(context, key)!!.success)
            verify { focused.focusSearch(direction) }
        }
        verify(exactly = 4) { next.recycle() }
        verify(exactly = 4) { focused.recycle() }
        verify(exactly = 4) { root.recycle() }
    }

    @Test
    fun missingInputFocusSelectsFirstVisibleFocusableNode() {
        val hidden = mockk<AccessibilityNodeInfo>(relaxed = true)
        val first = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns null
        every { root.childCount } returns 2
        every { root.getChild(0) } returns hidden
        every { root.getChild(1) } returns first
        every { hidden.isFocusable } returns true
        every { first.isFocusable } returns true
        every { first.isVisibleToUser } returns true
        every { first.isEnabled } returns true
        every { first.performAction(AccessibilityNodeInfo.ACTION_FOCUS) } returns true
        assertTrue(DpadController.handleKey(context, 22)!!.success)
        verify(exactly = 0) { hidden.performAction(any()) }
        verify { hidden.recycle(); first.recycle(); root.recycle() }
    }

    @Test
    fun centerClicksAndFallsBackToBoundsTap() {
        every { focused.performAction(AccessibilityNodeInfo.ACTION_CLICK) } returns true
        assertTrue(DpadController.handleKey(context, 23)!!.success)
        verify(exactly = 0) { GestureController.tap(any(), any()) }

        mockkConstructor(Rect::class)
        every { anyConstructed<Rect>().isEmpty } returns false
        every { anyConstructed<Rect>().centerX() } returns 120
        every { anyConstructed<Rect>().centerY() } returns 240
        every { focused.performAction(AccessibilityNodeInfo.ACTION_CLICK) } returns false
        every { GestureController.tap(120, 240) } returns true
        assertTrue(DpadController.activate().success)
        verify { GestureController.tap(120, 240) }
    }

    @Test
    fun api33SuccessStopsAndFalseReturnFallsBackToFocus() {
        val strategies = DpadStrategyPolicy.navigationStrategies(33, false, false)
        mockkObject(DpadStrategyPolicy)
        every { DpadStrategyPolicy.navigationStrategies(any(), any(), any()) } returns strategies
        every { GestureController.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_CENTER) } returns true
        assertTrue(DpadController.handleKey(context, 23)!!.success)
        verify(exactly = 0) { focused.performAction(any()) }

        every { GestureController.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_CENTER) } returns false
        every { focused.performAction(AccessibilityNodeInfo.ACTION_CLICK) } returns true
        assertTrue(DpadController.handleKey(context, 23)!!.success)
        verify(exactly = 1) { focused.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    @Test
    fun editableFocusWithConnectedImeRetainsCursorSemantics() {
        val keyboard = mockk<MobilerunKeyboardIME>()
        every { focused.isEditable } returns true
        every { MobilerunKeyboardIME.getInstance() } returns keyboard
        every { keyboard.hasInputConnection() } returns true
        every { MobilerunKeyboardIME.isSelected(context) } returns true
        every { keyboard.sendKeyEventDirect(21) } returns true
        assertTrue(DpadController.handleKey(context, 21)!!.success)
        verify { keyboard.sendKeyEventDirect(21) }
        verify(exactly = 0) { focused.focusSearch(any()) }
    }

    @Test
    fun escapeUsesBackAndUnsupportedKeysAreIgnored() {
        every { GestureController.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) } returns true
        assertTrue(DpadController.handleKey(context, 111)!!.success)
        assertNull(DpadController.handleKey(context, 29))
        verify(exactly = 0) { service.rootInActiveWindow }
    }

    @Test
    fun mediaTargetsPlayingSessionAndUsesAllTransportControls() {
        val manager = mediaManager()
        val paused = session(PlaybackState.STATE_PAUSED)
        val playing = session(PlaybackState.STATE_PLAYING)
        val controls = playing.transportControls
        every { manager.getActiveSessions(any()) } returns listOf(paused, playing)
        for (key in listOf(85, 126, 127, 87, 88)) assertTrue(DpadController.handleKey(context, key)!!.success)
        verify(exactly = 1) { controls.play() }
        verify(exactly = 2) { controls.pause() }
        verify { controls.skipToNext(); controls.skipToPrevious() }
        verify(exactly = 0) { paused.transportControls }

        every { manager.getActiveSessions(any()) } returns listOf(paused)
        assertTrue(DpadController.handleKey(context, 85)!!.success)
        verify { paused.transportControls.play() }
    }

    @Test
    fun mediaWithoutListenerAccessFailsClearlyWhileVolumeStillWorks() {
        val manager = mediaManager()
        every { manager.getActiveSessions(any()) } throws SecurityException("denied")
        val result = DpadController.handleKey(context, 85)!!
        assertFalse(result.success)
        assertEquals("Notification listener access is required for media keys", result.message)

        val audio = mockk<AudioManager>(relaxed = true)
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns audio
        for ((key, adjustment) in listOf(24 to AudioManager.ADJUST_RAISE, 25 to AudioManager.ADJUST_LOWER, 164 to AudioManager.ADJUST_TOGGLE_MUTE)) {
            assertTrue(DpadController.handleKey(context, key)!!.success)
            verify { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, adjustment, AudioManager.FLAG_SHOW_UI) }
        }
    }

    @Test
    fun noMediaSessionsAndMissingAccessibilityServiceFailWithoutCrashing() {
        val manager = mediaManager()
        every { manager.getActiveSessions(any()) } returns emptyList()
        assertEquals("No active media session", DpadController.handleKey(context, 85)!!.message)
        every { MobilerunAccessibilityService.getInstance() } returns null
        assertFalse(DpadController.activate().success)
    }

    private fun mediaManager(): MediaSessionManager {
        mockkConstructor(ComponentName::class)
        val manager = mockk<MediaSessionManager>()
        every { context.getSystemService(Context.MEDIA_SESSION_SERVICE) } returns manager
        return manager
    }

    private fun session(state: Int): MediaController {
        val controller = mockk<MediaController>(relaxed = true)
        val playback = mockk<PlaybackState>()
        every { playback.state } returns state
        every { controller.playbackState } returns playback
        return controller
    }
}
