package com.mobilerun.portal.service

import android.content.Context
import android.os.Handler
import com.mobilerun.portal.auth.DeviceCodeAuthClient
import com.mobilerun.portal.auth.SessionCredential
import com.mobilerun.portal.auth.SessionResult
import com.mobilerun.portal.config.ConfigManager
import com.mobilerun.portal.state.ConnectionState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReverseConnectionServiceSessionTest {
    private val context = mockk<Context>(relaxed = true)
    private val config = mockk<ConfigManager>(relaxed = true)
    private val service = spyk(mockk<ReverseConnectionService>(relaxed = true), recordPrivateCalls = true)
    private val handler = mockk<Handler>(relaxed = true)
    private val executor = mockk<ExecutorService>(relaxed = true)
    private val auth = mockk<DeviceCodeAuthClient>(relaxed = true)
    private val tasks = ArrayDeque<Runnable>()
    private val posts = ArrayDeque<Runnable>()
    private val running = AtomicBoolean(true)
    private var token = "session"
    private var credentialType = SessionCredential.TYPE
    private var checkedAt = 0L

    @Before fun setUp() {
        mockkObject(ConfigManager.Companion)
        every { ConfigManager.getInstance(context) } returns config
        every { config.reverseConnectionToken } answers { token }
        every { config.reverseConnectionCredentialType } answers { credentialType }
        every { config.sessionCheckedAt } answers { checkedAt }
        every { executor.execute(any()) } answers { tasks.add(firstArg()); Unit }
        every { handler.post(any()) } answers { posts.add(firstArg()); true }
        field("configManager", config)
        field("isServiceRunning", running)
        field("handler", handler)
        field("lightweightExecutor", executor)
        field("authClient", auth)
        field("sessionCheckInFlight", AtomicBoolean(false))
        field("webSocketGeneration", ReverseWebSocketGenerationGate())
        field("sessionCheck", Runnable {})
        every { service.checkSessionIfDue() } answers { callOriginal() }
        every { service["stopAfterTerminalState"](any<ConnectionState>(), any<Long>(), any<String>()) } returns Unit
        instance(service)
    }

    @After fun tearDown() {
        instance(null)
        unmockkAll()
    }

    private fun field(name: String, value: Any) {
        ReverseConnectionService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    private fun instance(value: ReverseConnectionService?) {
        ReverseConnectionService::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, value)
    }

    @Test fun clearSessionPreservesNewerSessionAndApiKey() {
        assertFalse(ReverseConnectionService.clearSession(context, "old"))
        credentialType = ConfigManager.CREDENTIAL_API_KEY
        assertFalse(ReverseConnectionService.clearSession(context, "session"))
        verify(exactly = 0) { config.setReverseConnectionCredential(any(), any()) }
        verify(exactly = 0) { service["stopAfterTerminalState"](any<ConnectionState>(), any<Long>(), any<String>()) }
    }

    @Test fun clearSessionClearsOnlyMatchingCredentialAndStopsRunningConnection() {
        assertTrue(ReverseConnectionService.clearSession(context, "session"))
        verify(exactly = 1) { config.setReverseConnectionCredential("", ConfigManager.CREDENTIAL_API_KEY) }
        verify(exactly = 1) { config.reverseConnectionEnabled = false }
        verify(exactly = 1) { service["stopAfterTerminalState"](ConnectionState.UNAUTHORIZED, 0L, "session") }
    }

    @Test fun checkSessionSkipsApiKeyEmptyFreshAndStoppedConnections() {
        credentialType = ConfigManager.CREDENTIAL_API_KEY
        service.checkSessionIfDue()
        credentialType = SessionCredential.TYPE
        token = ""
        service.checkSessionIfDue()
        token = "session"
        checkedAt = System.currentTimeMillis()
        service.checkSessionIfDue()
        checkedAt = 0L
        running.set(false)
        service.checkSessionIfDue()
        assertTrue(tasks.isEmpty())
        verify(exactly = 0) { auth.session(any()) }
    }

    @Test fun dueCheckHasOnlyOneInflightRequestAndRecordsValidResult() {
        every { auth.session("session") } returns SessionResult.Valid("person@example.com", null)
        service.checkSessionIfDue()
        service.checkSessionIfDue()
        tasks.removeFirst().run()
        posts.removeFirst().run()
        verify(exactly = 1) { auth.session("session") }
        verify(exactly = 1) { config.sessionCheckedAt = more(0L) }
        verify(exactly = 0) { service["stopAfterTerminalState"](any<ConnectionState>(), any<Long>(), any<String>()) }
    }

    @Test fun invalidSessionStopsButUnavailableSessionDoesNot() {
        every { auth.session("session") } returnsMany listOf(SessionResult.Unavailable, SessionResult.Invalid)
        repeat(2) {
            service.checkSessionIfDue()
            tasks.removeFirst().run()
            posts.removeFirst().run()
        }
        verify(exactly = 1) { service["stopAfterTerminalState"](ConnectionState.UNAUTHORIZED, 0L, "session") }
        verify(exactly = 0) { config.sessionCheckedAt = any() }
    }

    @Test fun staleAccountCheckCannotInvalidateReplacementToken() {
        every { auth.session("session") } returns SessionResult.Invalid
        service.checkSessionIfDue()
        tasks.removeFirst().run()
        token = "replacement"
        posts.removeFirst().run()
        verify(exactly = 0) { service["stopAfterTerminalState"](any<ConnectionState>(), any<Long>(), any<String>()) }
    }

    private fun scheduleSessionCheck() {
        ReverseConnectionService::class.java.getDeclaredMethod("scheduleSessionCheck")
            .apply { isAccessible = true }.invoke(service)
    }

    @Test fun sessionTimerIsNotScheduledForPhoneApiKeysOrEmptyCredentials() {
        every { service["scheduleSessionCheck"]() } answers { callOriginal() }
        credentialType = ConfigManager.CREDENTIAL_API_KEY
        scheduleSessionCheck()
        credentialType = SessionCredential.TYPE
        token = ""
        scheduleSessionCheck()
        verify(exactly = 0) { handler.postDelayed(any(), any()) }
        token = "session"
        scheduleSessionCheck()
        verify(exactly = 1) { handler.postDelayed(any(), DeviceCodeAuthClient.SESSION_CHECK_INTERVAL_MS) }
    }
    @Test fun sessionBearerRequiresOfficialSecureWebSocket() {
        val official = "wss://api.mobilerun.ai/v1/providers/personal/join"
        assertTrue(ReverseConnectionService.sessionConnectionAllowed(official, official))
        for (url in listOf("ws://api.mobilerun.ai/v1/providers/personal/join",
                "https://api.mobilerun.ai/v1/providers/personal/join",
                "wss://evil.example/v1/providers/personal/join",
                "wss://api.mobilerun.ai.evil.example/v1/providers/personal/join",
                "wss://api.mobilerun.ai:444/v1/providers/personal/join")) {
            assertFalse(url, ReverseConnectionService.sessionConnectionAllowed(url, official))
        }
    }

}
