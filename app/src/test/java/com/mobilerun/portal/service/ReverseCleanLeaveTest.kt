package com.mobilerun.portal.service

import io.mockk.mockk
import io.mockk.verify
import org.java_websocket.client.WebSocketClient
import org.java_websocket.framing.CloseFrame
import org.junit.Test

class ReverseCleanLeaveTest {
    @Test
    fun leaveClosesNormally() {
        val client = mockk<WebSocketClient>(relaxed = true)
        closeRelaySocket(client, leave = true)
        verify(exactly = 1) { client.close(CloseFrame.NORMAL, "user") }
        verify(exactly = 0) { client.closeConnection(any(), any()) }
    }

    @Test
    fun reconnectCleanupClosesAbnormally() {
        val client = mockk<WebSocketClient>(relaxed = true)
        closeRelaySocket(client, leave = false)
        verify(exactly = 1) { client.closeConnection(CloseFrame.ABNORMAL_CLOSE, "reconnect") }
        verify(exactly = 0) { client.close(any<Int>(), any<String>()) }
        verify(exactly = 0) { client.close() }
    }
}
