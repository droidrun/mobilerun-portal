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
class ReverseConnectionRpcRoutingTest {
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
        setField(
            "actionDispatcherCache",
            ServiceInstanceCache<MobilerunAccessibilityService, ActionDispatcher>(),
        )
    }

    @After
    fun tearDown() {
        executors.forEach { it.shutdownNow() }
        unmockkAll()
    }

    @Test
    fun smsRequestIsAnsweredWhileCommandExecutorIsBlocked() {
        installRpc()
        val release = CountDownLatch(1)
        val command = newExecutor()
        command.submit { release.await() }
        setField("commandExecutor", command)
        setField("smsExecutor", newExecutor())
        every { dispatcher.dispatch("files/list", any(), any(), any()) } returns
            ApiResponse.Success("files")
        every { dispatcher.dispatch("sms/send", any(), any(), any()) } returns
            ApiResponse.Success("queued")

        receive(client, request(1, "files/list"))
        receive(client, request(2, "sms/send"))

        val smsReply = frames.poll(5, TimeUnit.SECONDS)
        assertNotNull("sms/send waited behind the blocked command", smsReply)
        assertEquals(2, JSONObject(smsReply!!).getInt("id"))
        assertEquals("queued", JSONObject(smsReply).getString("result"))
        release.countDown()
        assertEquals(1, JSONObject(frames.poll(5, TimeUnit.SECONDS)!!).getInt("id"))
    }

    @Test
    fun smsRequestQueuedWhenItsSocketIsReplaced_isNotRun() {
        installRpc()
        val release = CountDownLatch(1)
        val sms = newExecutor()
        sms.submit { release.await() }
        setField("smsExecutor", sms)
        every { dispatcher.dispatch("sms/send", any(), any(), any()) } returns
            ApiResponse.Success("queued")

        receive(client, request(2, "sms/send"))
        val newClient = mockk<WebSocketClient>(relaxed = true)
        setField("rpcSocket", ReverseRpcSocket(newClient, generation = 5))
        release.countDown()
        sms.submit {}.get(5, TimeUnit.SECONDS)

        verify(exactly = 0) { dispatcher.dispatch(any(), any(), any(), any()) }
        verify(exactly = 0) { newClient.send(any<String>()) }
        assertTrue(frames.isEmpty())
    }

    @Test
    fun replyOnTheRpcSocketCompletesTheDeviceCall() {
        val results = installRpc()
        val id = service.rpcCalls.call("sms/received", JSONObject()) { results.add(it) }!!
        frames.clear()

        receive(client, JSONObject().put("id", id).put("result", JSONObject()).toString())

        assertTrue(results.single() is ReverseRpcResult.Ok)
        assertTrue(frames.isEmpty())
    }

    @Test
    fun replyFromAnotherSocketLeavesTheCallPending() {
        val results = installRpc()
        val id = service.rpcCalls.call("sms/received", JSONObject()) { results.add(it) }!!
        val staleClient = mockk<WebSocketClient>(relaxed = true)

        receive(staleClient, JSONObject().put("id", id).put("result", JSONObject()).toString())

        assertTrue(results.isEmpty())
        assertEquals(1, service.rpcCalls.pendingCount())
    }

    private fun installRpc(): MutableList<ReverseRpcResult> {
        val socket = ReverseRpcSocket(client, generation = 4)
        setField("rpcSocket", socket)
        setField("rpcCalls", ReverseRpcCalls(transport = { socket }, schedule = { _, _ -> }))
        return mutableListOf()
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
