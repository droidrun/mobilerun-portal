package com.mobilerun.portal.auth

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class DeviceSignInControllerTest {
    private class FakeMain : MainScheduler {
        val tasks = mutableListOf<Pair<Runnable, Long>>()
        override fun postDelayed(task: Runnable, delayMs: Long) {
            tasks += task to delayMs
        }

        override fun remove(task: Runnable) {
            tasks.removeAll { it.first === task }
        }

        /** Runs queued callbacks; returns the delays of polls left scheduled. */
        fun drain(): List<Long> {
            while (tasks.any { it.second == 0L }) {
                val next = tasks.first { it.second == 0L }
                tasks.remove(next)
                next.first.run()
            }
            return tasks.map { it.second }
        }

        fun runScheduledPoll() {
            val next = tasks.first()
            tasks.remove(next)
            next.first.run()
            drain()
        }
    }

    private val client = mockk<DeviceCodeAuthClient>()
    private val main = FakeMain()
    private var now = 0L
    private val states = mutableListOf<DeviceSignInState>()
    private val tokens = mutableListOf<String>()
    private var broughtToFront = 0
    private val controller = DeviceSignInController(
        client = client,
        executor = Executor { it.run() },
        main = main,
        elapsedRealtime = { now },
        onState = { states += it },
        onAuthorized = { tokens += it },
        onAuthorizedWhilePaused = { broughtToFront++ },
    )

    private fun issued(expiresIn: Long = 1800, interval: Long = 5) = DeviceCodeResult.Issued(
        DeviceAuthorization("dc", "ABCD1234", "https://cloud.mobilerun.ai/device", "", expiresIn, interval),
    )

    @Test
    fun approvedCodeDeliversTheTokenOnce() {
        every { client.requestCode("device") } returns issued()
        every { client.poll("dc") } returnsMany listOf(
            DeviceTokenResult.Error("authorization_pending"),
            DeviceTokenResult.Authorized("session"),
        )

        controller.start("device")
        assertEquals(listOf(5_000L), main.drain())
        assertTrue(controller.state is DeviceSignInState.Waiting)

        main.runScheduledPoll()
        assertEquals(listOf(5_000L), main.tasks.map { it.second })
        main.runScheduledPoll()

        assertEquals(listOf("session"), tokens)
        assertEquals(DeviceSignInState.Idle, controller.state)
        assertTrue(main.tasks.isEmpty())
    }

    @Test
    fun pausedControllerKeepsPollingAndTheTokenWaitsForResume() {
        every { client.requestCode(any()) } returns issued()
        every { client.poll("dc") } returnsMany listOf(
            DeviceTokenResult.Error("authorization_pending"),
            DeviceTokenResult.Authorized("session"),
        )

        controller.start("device")
        main.drain()
        controller.pause()
        main.runScheduledPoll()
        controller.resume()
        controller.pause()
        verify(exactly = 1) { client.poll("dc") }
        assertEquals(listOf(5_000L), main.tasks.map { it.second })

        main.runScheduledPoll()
        assertTrue(tokens.isEmpty())
        assertEquals(1, broughtToFront)
        assertTrue(main.tasks.isEmpty())

        controller.resume()
        assertEquals(listOf("session"), tokens)
        assertEquals(DeviceSignInState.Idle, controller.state)
    }

    @Test
    fun resumeRetriesRightAwayAfterANetworkError() {
        every { client.requestCode(any()) } returns issued()
        every { client.poll("dc") } returnsMany listOf(
            DeviceTokenResult.NetworkError,
            DeviceTokenResult.Authorized("session"),
        )

        controller.start("device")
        main.drain()
        controller.pause()
        main.runScheduledPoll()
        assertEquals(listOf(10_000L), main.tasks.map { it.second })

        controller.resume()
        main.drain()
        assertEquals(listOf("session"), tokens)
        assertEquals(0, broughtToFront)
        assertTrue(main.tasks.isEmpty())
    }

    @Test
    fun resumeDropsTheBackoffBuiltUpInTheBackground() {
        every { client.requestCode(any()) } returns issued()
        every { client.poll("dc") } returns DeviceTokenResult.NetworkError

        controller.start("device")
        main.drain()
        controller.pause()
        repeat(3) { main.runScheduledPoll() }
        assertEquals(listOf(40_000L), main.tasks.map { it.second })

        controller.resume()
        main.drain()
        assertEquals(listOf(10_000L), main.tasks.map { it.second })
    }

    @Test
    fun pendingTokenCanBeTakenForRevocation() {
        every { client.requestCode(any()) } returns issued()
        every { client.poll("dc") } answers {
            controller.pause()
            DeviceTokenResult.Authorized("session")
        }
        controller.start("device")
        main.drain()
        main.runScheduledPoll()

        assertEquals("session", controller.takePendingToken())
        controller.resume()
        assertTrue(tokens.isEmpty())
        assertEquals(null, controller.takePendingToken())
    }

    @Test
    fun expiryDenialAndFailuresEndWithoutANewCode() {
        every { client.requestCode(any()) } returns issued(expiresIn = 10)
        every { client.poll("dc") } returns DeviceTokenResult.Error("authorization_pending")
        controller.start("device")
        main.drain()
        now = 10_000
        main.runScheduledPoll()
        assertEquals(DeviceSignInState.Expired, controller.state)
        assertTrue(main.tasks.isEmpty())

        every { client.poll("dc") } returns DeviceTokenResult.Error("access_denied")
        controller.start("device")
        main.drain()
        main.runScheduledPoll()
        assertEquals(DeviceSignInState.Denied, controller.state)

        every { client.requestCode(any()) } returns DeviceCodeResult.Rejected("invalid_client")
        controller.start("device")
        main.drain()
        assertEquals(DeviceSignInState.Failed("invalid_client"), controller.state)

        every { client.requestCode(any()) } returns DeviceCodeResult.NetworkError
        controller.start("device")
        main.drain()
        assertEquals(DeviceSignInState.Offline, controller.state)
        verify(exactly = 4) { client.requestCode(any()) }
    }

    @Test
    fun cancelDropsLateResults() {
        every { client.requestCode(any()) } returns issued()
        controller.start("device")
        controller.cancel()
        main.drain()

        assertEquals(DeviceSignInState.Idle, controller.state)
        assertTrue(main.tasks.isEmpty())
        verify(exactly = 0) { client.poll(any()) }
    }
}
