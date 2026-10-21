package com.mobilerun.portal.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.mobilerun.portal.api.ApiHandler
import com.mobilerun.portal.api.ApiResponse
import com.mobilerun.portal.core.StateRepository
import com.mobilerun.portal.input.MobilerunKeyboardIME
import com.mobilerun.portal.model.PhoneState
import com.mobilerun.portal.streaming.ScrcpyControlChannel
import io.mockk.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.webrtc.DataChannel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class DpadKeyboardRoutingTest {
    private val context = mockk<Context>(relaxed = true)
    private val repo = mockk<StateRepository>(relaxed = true)
    private val service = mockk<MobilerunAccessibilityService>(relaxed = true)
    private val handled = DpadKeyResult(true, "Key handled")
    private val keys = listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)

    @Before
    fun setUp() {
        mockkObject(DpadController, MobilerunKeyboardIME.Companion, MobilerunAccessibilityService.Companion)
        mockkConstructor(KeyEvent::class)
        every { anyConstructed<KeyEvent>().getUnicodeChar(any()) } returns 0
        every { context.applicationContext } returns context
        every { MobilerunKeyboardIME.isAvailable() } returns false
        every { MobilerunAccessibilityService.getInstance() } returns service
        every { repo.getPhoneState() } returns PhoneState(null, false, null, null, false, null)
        every { DpadController.handleKey(any(), any()) } returns handled
        every { DpadController.activate() } returns handled
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun apiAndReverseRpcRoutePreviouslyUnsupportedKeysToSharedController() {
        val api = api()
        val dispatcher = ActionDispatcher(api)
        for (key in keys) {
            assertEquals(ApiResponse.Success("Key handled"), api.keyboardKey(key))
            assertEquals(
                ApiResponse.Success("Key handled"),
                dispatcher.dispatch("keyboard/key", JSONObject().put("key_code", key), ActionDispatcher.Origin.WEBSOCKET_REVERSE),
            )
            verify(exactly = 2) { DpadController.handleKey(context, key) }
        }
    }

    @Test
    fun apiReturnsMediaPermissionErrorsAndRetainsUnsupportedKeyError() {
        every { DpadController.handleKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) } returns DpadKeyResult(false, "Notification listener access is required for media keys")
        every { DpadController.handleKey(context, 999) } returns null
        assertEquals(ApiResponse.Error("Notification listener access is required for media keys"), api().keyboardKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(ApiResponse.Error("Unsupported key code: 999 (no unicode mapping and IME not available)"), api().keyboardKey(999))
    }

    @Test
    fun apiEnterInEditorUsesExistingImePathWithoutActivation() {
        val keyboard = mockk<MobilerunKeyboardIME>()
        every { MobilerunKeyboardIME.isAvailable() } returns true
        every { MobilerunKeyboardIME.isSelected(context) } returns true
        every { MobilerunKeyboardIME.getInstance() } returns keyboard
        every { keyboard.sendKeyEventDirect(any()) } returns true
        for (key in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) assertEquals(ApiResponse.Success("Enter sent via IME"), api().keyboardKey(key))
        verify(exactly = 0) { repo.getPhoneState(); DpadController.activate() }
    }

    @Test
    fun apiEnterRetainsNewlineAttemptAndActivatesOnlyAfterItFails() {
        every { repo.inputText("\n", false) } returns true
        assertEquals(ApiResponse.Success("Newline inserted via Accessibility"), api().keyboardKey(KeyEvent.KEYCODE_ENTER))
        verify(exactly = 0) { DpadController.activate() }

        every { repo.inputText("\n", false) } returns false
        for (key in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) assertEquals(ApiResponse.Success("Key handled"), api().keyboardKey(key))
        verify(exactly = 2) { DpadController.activate() }

        every { DpadController.activate() } returns DpadKeyResult(false, "No focus")
        assertEquals(ApiResponse.Success("Enter handled (no focused element)"), api().keyboardKey(KeyEvent.KEYCODE_ENTER))
    }

    @Test
    fun reverseRpcEnterOnFocusedItemActivatesThroughApiHandler() {
        every { repo.inputText("\n", false) } returns false
        val dispatcher = ActionDispatcher(api())
        for (key in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            assertEquals(
                ApiResponse.Success("Key handled"),
                dispatcher.dispatch("keyboard/key", JSONObject().put("key_code", key), ActionDispatcher.Origin.WEBSOCKET_REVERSE),
            )
        }
        verify(exactly = 2) { DpadController.activate() }
    }

    @Test
    fun dataChannelRoutesFallbackKeysOnKeyDownOnly() {
        val channel = channel()
        for (key in keys) {
            sendKey(channel, key, KeyEvent.ACTION_DOWN)
            sendKey(channel, key, KeyEvent.ACTION_UP)
            verify(exactly = 1) { DpadController.handleKey(context, key) }
        }
    }

    @Test
    fun dataChannelMediaAndVolumeUseContextWithoutAccessibilityService() {
        every { MobilerunAccessibilityService.getInstance() } returns null
        val channel = channel()
        for (key in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)) {
            sendKey(channel, key)
            verify(exactly = 1) { DpadController.handleKey(context, key) }
        }
        verify(exactly = 0) { MobilerunAccessibilityService.getInstance() }
    }

    @Test
    fun phoneEnterWithoutFocusedNodeRetainsNoOpAndNeverClicks() {
        unmockkObject(DpadController)
        val root = mockk<android.view.accessibility.AccessibilityNodeInfo>(relaxed = true)
        every { service.rootInActiveWindow } returns root
        every { root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT) } returns null
        every { repo.inputText("\n", false) } returns false
        for (key in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            assertEquals(ApiResponse.Success("Enter handled (no focused element)"), api().keyboardKey(key))
            sendKey(channel(), key)
        }
        verify(exactly = 0) { root.performAction(any()); root.getChild(any()) }
    }

    @Test
    fun dataChannelEnterPreservesEditorInputAndActivatesAfterFailedNewlineOnly() {
        val channel = channel()
        every { service.inputText("\n", false) } returns true
        sendKey(channel, KeyEvent.KEYCODE_ENTER)
        verify(exactly = 0) { DpadController.activate() }

        every { service.inputText("\n", false) } returns false
        sendKey(channel, KeyEvent.KEYCODE_ENTER)
        sendKey(channel, KeyEvent.KEYCODE_NUMPAD_ENTER)
        sendKey(channel, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.ACTION_UP)
        verify(exactly = 2) { DpadController.activate() }

        val keyboard = mockk<MobilerunKeyboardIME>(relaxed = true)
        every { MobilerunKeyboardIME.isAvailable() } returns true
        every { MobilerunKeyboardIME.isSelected(service) } returns true
        every { MobilerunKeyboardIME.getInstance() } returns keyboard
        sendKey(channel, KeyEvent.KEYCODE_ENTER)
        verify { keyboard.sendKeyEventDirect(KeyEvent.KEYCODE_ENTER) }
        verify(exactly = 2) { DpadController.activate() }
    }

    @Test
    fun dataChannelExistingSystemKeysAndDeleteDoNotUseFallback() {
        mockkObject(GestureController)
        every { GestureController.performGlobalAction(any()) } returns true
        val channel = channel()
        for (key in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL)) sendKey(channel, key)
        verify(exactly = 0) { DpadController.handleKey(any(), any()); DpadController.activate() }
        verify(exactly = 3) { GestureController.performGlobalAction(any()) }
        verify { service.deleteText(1); service.deleteText(1, true) }
    }

    private fun api() = ApiHandler(repo, { null }, { mockk(relaxed = true) }, { "test" }, context)

    private fun channel(): ScrcpyControlChannel {
        mockkStatic(Looper::class)
        every { Looper.getMainLooper() } returns mockk(relaxed = true)
        mockkConstructor(Handler::class)
        return ScrcpyControlChannel(context)
    }

    private fun sendKey(channel: ScrcpyControlChannel, key: Int, action: Int = KeyEvent.ACTION_DOWN) {
        val packet = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN)
            .put(0.toByte()).put(action.toByte()).putInt(key).putInt(0).putInt(0).array()
        channel.onMessage(DataChannel.Buffer(ByteBuffer.wrap(packet), true))
    }
}
