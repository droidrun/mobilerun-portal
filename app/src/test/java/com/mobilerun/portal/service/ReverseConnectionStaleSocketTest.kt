package com.mobilerun.portal.service

import android.os.SystemClock
import android.util.Log
import com.mobilerun.portal.api.ApiResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import org.java_websocket.client.WebSocketClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Runs the real reverse-socket receive path with only Android and socket I/O mocked. */
class ReverseConnectionStaleSocketTest {
    private val dispatcher = mockk<ActionDispatcher>()
    private val frames = LinkedBlockingQueue<String>()
    private val client = mockk<WebSocketClient>(relaxed = true)
    private val executors = mutableListOf<ExecutorService>()
    private lateinit var service: ReverseConnectionService

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
        val accessibility = mockk<MobilerunAccessibilityService>()
        mockkObject(MobilerunAccessibilityService.Companion)
        every { MobilerunAccessibilityService.getInstance() } returns accessibility
        every { accessibility.getActionDispatcher() } returns dispatcher
        every { client.isOpen } returns true
        every { client.send(any<String>()) } answers { frames.add(firstArg()); Unit }
        service = spyk(mockk<ReverseConnectionService>(), recordPrivateCalls = true)
        setField("webSocketClient", client)
        setField("rpcSocket", ReverseRpcSocket(client, generation = 1))
        setField(
            "actionDispatcherCache",
            ServiceInstanceCache<MobilerunAccessibilityService, ActionDispatcher>(),
        )
        every { dispatcher.dispatch(any(), any(), any(), any()) } returns ApiResponse.Success("ok")
    }

    @After
    fun tearDown() {
        executors.forEach { it.shutdownNow() }
        unmockkAll()
    }

    @Test
    fun commandOnTheCurrentSocket_runsAndReplies() {
        setField("commandExecutor", newExecutor())

        receive(client, request(1, "tap"))

        val reply = frames.poll(5, TimeUnit.SECONDS)
        assertNotNull(reply)
        assertEquals(1, JSONObject(reply!!).getInt("id"))
    }

    @Test
    fun commandQueuedWhenItsSocketIsReplaced_isNotRun() {
        val (command, release) = blockedExecutor()
        setField("commandExecutor", command)

        receive(client, request(2, "tap"))
        val newClient = replaceSocket()
        release.countDown()
        command.submit {}.get(5, TimeUnit.SECONDS)

        verify(exactly = 0) { dispatcher.dispatch(any(), any(), any(), any()) }
        verify(exactly = 0) { newClient.send(any<String>()) }
        assertTrue(frames.isEmpty())
    }

    @Test
    fun installQueuedWhenItsSocketCloses_isNotRun() {
        val (install, release) = blockedExecutor()
        setField("installExecutor", install)

        receive(client, request(3, "install"))
        every { client.isOpen } returns false
        release.countDown()
        install.submit {}.get(5, TimeUnit.SECONDS)

        verify(exactly = 0) { dispatcher.dispatch(any(), any(), any(), any()) }
        assertTrue(frames.isEmpty())
    }

    @Test
    fun signalingQueuedWhenItsSocketIsReplaced_stillRunsWithoutReplyingOnTheNewSocket() {
        val (signaling, release) = blockedExecutor()
        setField("signalingExecutor", signaling)

        receive(client, request(4, "webrtc/ice"))
        val newClient = replaceSocket()
        release.countDown()
        signaling.submit {}.get(5, TimeUnit.SECONDS)

        verify(exactly = 1) { dispatcher.dispatch("webrtc/ice", any(), any(), any()) }
        verify(exactly = 0) { newClient.send(any<String>()) }
    }

    /** A reconnect: the old socket is closed and a new one becomes current. */
    private fun replaceSocket(): WebSocketClient {
        every { client.isOpen } returns false
        val newClient = mockk<WebSocketClient>(relaxed = true)
        every { newClient.isOpen } returns true
        setField("webSocketClient", newClient)
        setField("rpcSocket", ReverseRpcSocket(newClient, generation = 2))
        return newClient
    }

    private fun blockedExecutor(): Pair<ExecutorService, CountDownLatch> {
        val release = CountDownLatch(1)
        val executor = newExecutor()
        executor.submit { release.await() }
        return executor to release
    }

    private fun newExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor().also { executors.add(it) }

    private fun request(id: Int, method: String): String =
        JSONObject().put("id", id).put("method", method).put("params", JSONObject()).toString()

    private fun receive(from: WebSocketClient, message: String) {
        ReverseConnectionService::class.java.getDeclaredMethod(
            "handleMessage", WebSocketClient::class.java, String::class.java,
        ).apply { isAccessible = true }.invoke(service, from, message)
    }

    private fun setField(name: String, value: Any) {
        ReverseConnectionService::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(service, value)
        }
    }
}
