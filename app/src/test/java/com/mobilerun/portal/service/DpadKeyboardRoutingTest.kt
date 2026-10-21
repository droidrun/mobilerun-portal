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
    private val keys = listOf(19, 20, 21, 22, 23, 111, 85, 126, 127, 87, 88, 24, 25, 164)

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
        every { DpadController.handleKey(context, 85) } returns DpadKeyResult(false, "Notification listener access is required for media keys")
        every { DpadController.handleKey(context, 999) } returns null
        assertEquals(ApiResponse.Error("Notification listener access is required for media keys"), api().keyboardKey(85))
        assertEquals(ApiResponse.Error("Unsupported key code: 999 (no unicode mapping and IME not available)"), api().keyboardKey(999))
    }

    @Test
    fun apiEnterInEditorUsesExistingImePathWithoutActivation() {
        val keyboard = mockk<MobilerunKeyboardIME>()
        every { MobilerunKeyboardIME.isAvailable() } returns true
        every { MobilerunKeyboardIME.isSelected(context) } returns true
        every { MobilerunKeyboardIME.getInstance() } returns keyboard
        every { keyboard.sendKeyEventDirect(any()) } returns true
        for (key in listOf(66, 160)) assertEquals(ApiResponse.Success("Enter sent via IME"), api().keyboardKey(key))
        verify(exactly = 0) { repo.getPhoneState(); DpadController.activate() }
    }

    @Test
    fun apiEnterRetainsNewlineAttemptAndActivatesOnlyAfterItFails() {
        every { repo.inputText("\n", false) } returns true
        assertEquals(ApiResponse.Success("Newline inserted via Accessibility"), api().keyboardKey(66))
        verify(exactly = 0) { DpadController.activate() }

        every { repo.inputText("\n", false) } returns false
        for (key in listOf(66, 160)) assertEquals(ApiResponse.Success("Key handled"), api().keyboardKey(key))
        verify(exactly = 2) { DpadController.activate() }

        every { DpadController.activate() } returns DpadKeyResult(false, "No focus")
        assertEquals(ApiResponse.Success("Enter handled (no focused element)"), api().keyboardKey(66))
    }

    @Test
    fun reverseRpcEnterWithoutEditorActivatesThroughApiHandler() {
        every { repo.inputText("\n", false) } returns false
        val dispatcher = ActionDispatcher(api())
        for (key in listOf(66, 160)) {
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
            verify(exactly = 1) { DpadController.handleKey(service, key) }
        }
    }

    @Test
    fun dataChannelEnterPreservesEditorInputAndActivatesAfterFailedNewlineOnly() {
        val channel = channel()
        every { service.inputText("\n", false) } returns true
        sendKey(channel, 66)
        verify(exactly = 0) { DpadController.activate() }

        every { service.inputText("\n", false) } returns false
        sendKey(channel, 66)
        sendKey(channel, 160)
        sendKey(channel, 160, KeyEvent.ACTION_UP)
        verify(exactly = 2) { DpadController.activate() }

        val keyboard = mockk<MobilerunKeyboardIME>(relaxed = true)
        every { MobilerunKeyboardIME.isAvailable() } returns true
        every { MobilerunKeyboardIME.isSelected(service) } returns true
        every { MobilerunKeyboardIME.getInstance() } returns keyboard
        sendKey(channel, 66)
        verify { keyboard.sendKeyEventDirect(66) }
        verify(exactly = 2) { DpadController.activate() }
    }

    @Test
    fun dataChannelExistingSystemKeysAndDeleteDoNotUseFallback() {
        mockkObject(GestureController)
        every { GestureController.performGlobalAction(any()) } returns true
        val channel = channel()
        for (key in listOf(4, 3, 187, 67, 112)) sendKey(channel, key)
        verify(exactly = 0) { DpadController.handleKey(any(), any()); DpadController.activate() }
        verify(exactly = 3) { GestureController.performGlobalAction(any()) }
        verify { service.deleteText(1); service.deleteText(1, true) }
    }

    private fun api() = ApiHandler(repo, { null }, { mockk(relaxed = true) }, { "test" }, context)

    private fun channel(): ScrcpyControlChannel {
        mockkStatic(Looper::class)
        every { Looper.getMainLooper() } returns mockk(relaxed = true)
        mockkConstructor(Handler::class)
        return ScrcpyControlChannel()
    }

    private fun sendKey(channel: ScrcpyControlChannel, key: Int, action: Int = KeyEvent.ACTION_DOWN) {
        val packet = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN)
            .put(0.toByte()).put(action.toByte()).putInt(key).putInt(0).putInt(0).array()
        val method = ScrcpyControlChannel::class.java.getDeclaredMethod("handleKeycode", ByteArray::class.java)
        method.isAccessible = true
        method.invoke(channel, packet)
    }
}
