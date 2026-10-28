package com.mobilerun.portal.api

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.mobilerun.portal.config.ConfigManager
import com.mobilerun.portal.core.StateRepository
import com.mobilerun.portal.events.PortalWebSocketServer
import com.mobilerun.portal.service.ActionDispatcher
import com.mobilerun.portal.service.MobilerunAccessibilityService
import com.mobilerun.portal.service.ReverseConnectionService
import com.mobilerun.portal.service.ServiceInstanceCache
import com.mobilerun.portal.service.SocketServer
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Pins the screenshot handler and the production transport paths to the documented JSON contract. */
class ScreenshotContractTest {
    private val stateRepo = mockk<StateRepository>()
    private lateinit var handler: ApiHandler
    private lateinit var dispatcher: ActionDispatcher
    private lateinit var config: ConfigManager

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 0L
        every { stateRepo.takeScreenshot(any()) } returns CompletableFuture.completedFuture(PNG_BASE64)
        handler = ApiHandler(
            stateRepo = stateRepo,
            getKeyboardIME = { null },
            getPackageManager = { mockk() },
            appVersionProvider = { "test" },
            context = mockk<Context>(relaxed = true),
        )
        dispatcher = ActionDispatcher(handler)
        config = mockk()
        every { config.authToken } returns TOKEN
    }

    @After
    fun tearDown() {
        handler.close()
        unmockkAll()
    }

    @Test
    fun httpScreenshot_isJsonWithBase64PngAndNoRequestId() = withHttpServer { port ->
        val response = httpGet(port, "/screenshot")

        assertTrue(response.headers.startsWith("HTTP/1.1 200 "))
        assertTrue(response.headers.contains("Content-Type: application/json\r\n"))
        assertFalse(response.headers.contains("image/png"))
        assertPngEnvelope(JSONObject(response.body), id = null)
        verify(exactly = 1) { stateRepo.takeScreenshot(true) }

        assertPngEnvelope(JSONObject(httpGet(port, "/screenshot?hideOverlay=false").body), id = null)
        verify(exactly = 1) { stateRepo.takeScreenshot(false) }
    }

    @Test
    fun httpScreenshot_captureFailureIsAnErrorEnvelope() = withHttpServer { port ->
        captureFails()
        val response = httpGet(port, "/screenshot")

        assertTrue(response.headers.contains("Content-Type: application/json\r\n"))
        assertErrorEnvelope(JSONObject(response.body), id = null)
    }

    @Test
    fun httpScreenshot_requiresAuthenticationBeforeCapture() = withHttpServer { port ->
        val response = httpGet(port, "/screenshot", token = "wrong-token")

        assertTrue(response.headers.startsWith("HTTP/1.1 401 "))
        val json = JSONObject(response.body)
        assertEquals("error", json.getString("status"))
        assertEquals("Unauthorized", json.getString("error"))
        assertFalse(json.has("result"))
        verify(exactly = 0) { stateRepo.takeScreenshot(any()) }
    }

    @Test
    fun localWebSocketScreenshot_isATextFrameAndPreservesNumericAndStringIds() =
        withWebSocketServer { port ->
            val client = RecordingClient(URI("ws://127.0.0.1:$port/?token=$TOKEN"))
            try {
                assertTrue(client.connectBlocking(5, TimeUnit.SECONDS))
                for (id in listOf(42, "screenshot-request")) {
                    client.send(request(id, hideOverlay = false))
                    val frame = client.messages.poll(5, TimeUnit.SECONDS)
                    assertNotNull("Expected a screenshot text frame", frame)
                    assertTrue("Screenshot must not be a binary frame", frame is String)
                    assertPngEnvelope(JSONObject(frame as String), id)
                }
                verify(exactly = 2) { stateRepo.takeScreenshot(false) }
            } finally {
                client.closeBlocking()
            }
        }

    @Test
    fun localWebSocketScreenshot_rejectsUnauthenticatedConnections() = withWebSocketServer { port ->
        val client = RecordingClient(URI("ws://127.0.0.1:$port/?token=wrong-token"))
        try {
            assertFalse(client.connectBlocking(5, TimeUnit.SECONDS))
            verify(exactly = 0) { stateRepo.takeScreenshot(any()) }
        } finally {
            client.closeBlocking()
        }
    }

    @Test
    fun reverseWebSocketScreenshot_usesTextJsonAndPreservesNumericAndStringIds() {
        withReverseConnection { sendRequest, frames ->
            for (id in listOf(73, "reverse-screenshot")) {
                sendRequest(request(id, hideOverlay = false))
                val frame = frames.poll(5, TimeUnit.SECONDS)
                assertNotNull("Expected a reverse screenshot text frame", frame)
                assertTrue("Screenshot must not be a binary frame", frame is String)
                assertPngEnvelope(JSONObject(frame as String), id)
            }
            verify(exactly = 2) { stateRepo.takeScreenshot(false) }
        }
    }

    private fun withHttpServer(block: (Int) -> Unit) {
        val port = ServerSocket(0).use { it.localPort }
        val server = SocketServer(handler, config, dispatcher)
        assertTrue("HTTP server failed to start", server.start(port))
        try {
            block(port)
        } finally {
            server.stop()
        }
    }

    private fun withWebSocketServer(block: (Int) -> Unit) {
        val started = CountDownLatch(1)
        val server = PortalWebSocketServer(0, dispatcher, config) { started.countDown() }
        server.start()
        try {
            assertTrue("WebSocket server failed to start", started.await(5, TimeUnit.SECONDS))
            block(server.port)
        } finally {
            server.stopSafely()
        }
    }

    private fun httpGet(port: Int, path: String, token: String = TOKEN): HttpResponse {
        return Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write(("GET $path HTTP/1.1\r\nHost: localhost\r\n" +
                    "Authorization: Bearer $token\r\nConnection: close\r\n\r\n").toByteArray())
                flush()
            }
            val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            val boundary = response.indexOf("\r\n\r\n")
            assertTrue("Expected a complete HTTP response", boundary > 0)
            HttpResponse(response.substring(0, boundary + 2), response.substring(boundary + 4))
        }
    }

    private fun withReverseConnection(block: ((String) -> Unit, LinkedBlockingQueue<Any>) -> Unit) {
        // Allocate without Android Service construction, then invoke the real incoming-message
        // path. Only the accessibility source and socket I/O are mocked; dispatch/serialization
        // are production code, so this detects a transport switching to binary responses.
        val service = spyk(mockk<ReverseConnectionService>(), recordPrivateCalls = true)
        val accessibility = mockk<MobilerunAccessibilityService>()
        mockkObject(MobilerunAccessibilityService.Companion)
        every { MobilerunAccessibilityService.getInstance() } returns accessibility
        every { accessibility.getActionDispatcher() } returns dispatcher
        val executor = Executors.newSingleThreadExecutor()
        val frames = LinkedBlockingQueue<Any>()
        val client = mockk<WebSocketClient>(relaxed = true)
        every { client.isOpen } returns true
        every { client.send(any<String>()) } answers { frames.add(firstArg<String>()); Unit }
        every { client.send(any<ByteBuffer>()) } answers { frames.add(firstArg<ByteBuffer>()); Unit }
        every { client.send(any<ByteArray>()) } answers { frames.add(firstArg<ByteArray>()); Unit }
        setField(service, "actionDispatcherCache", ServiceInstanceCache<MobilerunAccessibilityService, ActionDispatcher>())
        setField(service, "commandExecutor", executor)
        val receive = ReverseConnectionService::class.java.getDeclaredMethod(
            "handleMessage", WebSocketClient::class.java, String::class.java,
        ).apply { isAccessible = true }
        try {
            block({ message -> receive.invoke(service, client, message); Unit }, frames)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private fun setField(target: Any, name: String, value: Any) {
        ReverseConnectionService::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(target, value)
        }
    }

    private fun captureFails() {
        every { stateRepo.takeScreenshot(any()) } returns
            CompletableFuture.completedFuture("error: Screenshot failed: Secure window cannot be captured")
    }

    private fun request(id: Any, hideOverlay: Boolean = true): String = JSONObject().apply {
        put("id", id)
        put("method", "screenshot")
        put("params", JSONObject().put("hideOverlay", hideOverlay))
    }.toString()

    private fun assertPngEnvelope(json: JSONObject, id: Any?) {
        assertEquals("success", json.getString("status"))
        assertEquals(PNG_BASE64, json.getString("result"))
        assertFalse(json.has("error"))
        assertRequestId(json, id)
    }

    private fun assertErrorEnvelope(json: JSONObject, id: Any?) {
        assertEquals("error", json.getString("status"))
        assertEquals("Screenshot failed: Secure window cannot be captured", json.getString("error"))
        assertFalse(json.has("result"))
        assertRequestId(json, id)
    }

    private fun assertRequestId(json: JSONObject, id: Any?) {
        if (id == null) assertFalse(json.has("id")) else assertEquals(id, json.get("id"))
    }

    private data class HttpResponse(val headers: String, val body: String)

    private class RecordingClient(uri: URI) : WebSocketClient(uri) {
        val messages = LinkedBlockingQueue<Any>()
        override fun onOpen(handshakedata: ServerHandshake?) = Unit
        override fun onMessage(message: String?) { message?.let(messages::add) }
        override fun onMessage(bytes: ByteBuffer?) { bytes?.let(messages::add) }
        override fun onClose(code: Int, reason: String?, remote: Boolean) = Unit
        override fun onError(ex: Exception?) = Unit
    }

    companion object {
        private const val TOKEN = "screenshot-contract-token"
        // Valid 1x1 PNG. The capture boundary is mocked so the contract is independent of
        // the device's display, Android permission prompts, and screenshot timing.
        private const val PNG_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYGD4DwABBAEAX+XDSwAAAABJRU5ErkJggg=="
    }
}
