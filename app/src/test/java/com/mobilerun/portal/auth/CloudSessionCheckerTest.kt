package com.mobilerun.portal.auth

import com.mobilerun.portal.config.ConfigManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class CloudSessionCheckerTest {
    private val client = mockk<DeviceCodeAuthClient>()
    private val config = mockk<ConfigManager>(relaxed = true)
    private val ended = mutableListOf<String>()
    private val queued = mutableListOf<Runnable>()
    private var now = DeviceCodeAuthClient.SESSION_CHECK_INTERVAL_MS + 10

    private fun checker(executor: Executor = Executor { it.run() }) = CloudSessionChecker(
        client = client,
        config = config,
        executor = executor,
        nowMs = { now },
        onSessionEnded = { ended += it },
        minCallIntervalMs = 0,
    )

    private fun dueSession() {
        every { config.cloudSessionActive } returns true
        every { config.cloudSessionCheckedAtMs } returns 1L
        every { config.reverseConnectionToken } returns "session"
    }

    @Test
    fun validSessionRecordsTheCheck() {
        dueSession()
        every { client.session("session") } returns SessionResult.Valid

        assertTrue(checker().checkIfDue())

        verify { config.cloudSessionCheckedAtMs = now }
        assertTrue(ended.isEmpty())
    }

    @Test
    fun invalidSessionEndsItAndUnavailableChangesNothing() {
        dueSession()
        every { client.session("session") } returns SessionResult.Invalid
        checker().checkIfDue()
        assertEquals(listOf("session"), ended)

        ended.clear()
        every { client.session("session") } returns SessionResult.Unavailable
        checker().checkIfDue()
        assertTrue(ended.isEmpty())
        verify(exactly = 0) { config.cloudSessionCheckedAtMs = any() }
    }

    @Test
    fun skipsApiKeysAndChecksThatAreNotDue() {
        every { config.cloudSessionActive } returns false
        assertFalse(checker().checkIfDue())

        dueSession()
        every { config.cloudSessionCheckedAtMs } returns now - 1
        assertFalse(checker().checkIfDue())
        verify(exactly = 0) { client.session(any()) }
    }

    @Test
    fun onlyOneCheckRunsAtATime() {
        dueSession()
        every { client.session("session") } returns SessionResult.Valid
        val checker = checker(Executor { queued += it })

        assertTrue(checker.checkIfDue())
        assertFalse(checker.checkIfDue())
        queued.single().run()
        assertTrue(checker.checkIfDue())
    }

    @Test
    fun callsAreThrottled() {
        dueSession()
        every { client.session("session") } returns SessionResult.Valid
        val throttled = CloudSessionChecker(client, config, Executor { it.run() }, { now }, { ended += it }, 60_000)

        assertTrue(throttled.checkIfDue())
        assertFalse(throttled.checkIfDue())
        now += 60_000
        assertTrue(throttled.checkIfDue())
    }
}
