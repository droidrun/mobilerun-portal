package com.mobilerun.portal.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.java_websocket.client.WebSocketClient
import org.java_websocket.framing.CloseFrame
import org.java_websocket.handshake.ServerHandshake
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI

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
        every { client.isOpen } returns true
        closeRelaySocket(client, leave = false)
        verify(exactly = 1) { client.closeConnection(CloseFrame.ABNORMAL_CLOSE, "reconnect") }
        verify(exactly = 0) { client.close(any<Int>(), any<String>()) }
        verify(exactly = 0) { client.close() }
    }

    @Test
    fun reconnectCleanupBeforeOpenStillClosesTheSocket() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { relay ->
            val client = object : WebSocketClient(URI("ws://127.0.0.1:${relay.localPort}/")) {
                override fun onOpen(handshake: ServerHandshake) = Unit
                override fun onMessage(message: String) = Unit
                override fun onClose(code: Int, reason: String, remote: Boolean) = Unit
                override fun onError(ex: Exception) = Unit
            }
            closeRelaySocket(client, leave = false)
            client.connect()
            relay.accept().use { socket ->
                // A connect that outlives the cleanup keeps the socket open, so this read times out.
                socket.soTimeout = 5_000
                while (socket.getInputStream().read() != -1) Unit
            }
        }
    }
}
