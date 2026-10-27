package com.mobilerun.portal.service

import io.mockk.mockk
import io.mockk.verify
import org.java_websocket.client.WebSocketClient
import org.junit.Test

class ReverseCleanLeaveTest {
    @Test
    fun userDisconnectClosesWithCleanLeaveCodeAndReason() {
        val client = mockk<WebSocketClient>(relaxed = true)
        closeRelaySocket(client, cleanLeave = true)
        verify(exactly = 1) { client.close(4000, "user") }
        verify(exactly = 0) { client.close() }
    }

    @Test
    fun otherDisconnectsKeepPlainClose() {
        val client = mockk<WebSocketClient>(relaxed = true)
        closeRelaySocket(client, cleanLeave = false)
        verify(exactly = 1) { client.close() }
        verify(exactly = 0) { client.close(any<Int>(), any<String>()) }
    }
}
