package com.mobilerun.portal.ui

import android.content.Context
import com.mobilerun.portal.auth.DeviceAuthorization
import com.mobilerun.portal.auth.DeviceCodeAuthClient
import com.mobilerun.portal.auth.DeviceTokenResult
import com.mobilerun.portal.auth.SessionCredential
import com.mobilerun.portal.auth.SessionResult
import com.mobilerun.portal.auth.WorkspaceResult
import com.mobilerun.portal.config.ConfigManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import java.util.ArrayDeque
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class TvSignInControllerTest {
    private val context = mockk<Context>(relaxed = true)
    private val config = mockk<ConfigManager>(relaxed = true)
    private val worker = mockk<ScheduledExecutorService>(relaxed = true)
    private val auth = mockk<DeviceCodeAuthClient>(relaxed = true)
    private val listener = mockk<TvSignInController.Listener>(relaxed = true)
    private val tasks = ArrayDeque<Runnable>()
    private val posts = ArrayDeque<() -> Unit>()
    private var token = ""
    private var elapsed = 1_000L
    private lateinit var controller: TvSignInController

    @Before fun setUp() {
        every { context.applicationContext } returns context
        mockkObject(ConfigManager.Companion)
        every { ConfigManager.getInstance(context) } returns config
        every { config.reverseConnectionCredentialType } returns SessionCredential.TYPE
        every { config.reverseConnectionToken } answers { token }
        every { worker.schedule(any<Runnable>(), any<Long>(), TimeUnit.MILLISECONDS) } answers {
            tasks.add(firstArg())
            mockk<ScheduledFuture<*>>(relaxed = true)
        }
        every { worker.execute(any()) } answers { tasks.add(firstArg()); Unit }
        controller = TvSignInController(context, listener, worker, { posts.add(it) }, auth, { elapsed }, { 50_000L })
    }

    @After fun tearDown() {
        controller.close()
        unmockkAll()
    }

    private fun authorization(code: String = "USER", complete: String = "https://cloud.mobilerun.ai/device?user_code=USER") =
        DeviceAuthorization("device", code, "https://cloud.mobilerun.ai/custom-device", complete, 10, 1)

    private fun showCode() {
        every { auth.authorize() } returns authorization()
        controller.refreshCode()
        tasks.removeFirst().run()
        posts.removeFirst().invoke()
    }

    @Test fun expiredCodeRefreshesWithoutPollingItAgain() {
        showCode()
        elapsed += 10_000L
        tasks.removeFirst().run()
        posts.removeFirst().invoke()
        every { auth.authorize() } returns authorization("NEXT")
        tasks.removeFirst().run()
        posts.removeFirst().invoke()
        verify(exactly = 2) { auth.authorize() }
        verify(exactly = 0) { auth.poll(any()) }
        verify { listener.onCode("NEXT", "https://cloud.mobilerun.ai/custom-device", any(), 21_000L, 10_000L) }
    }

    @Test fun authorizedResultPostedAfterCancelIsRevoked() {
        showCode()
        every { auth.poll("device") } returns DeviceTokenResult.Authorized("discarded")
        tasks.removeFirst().run()
        controller.cancel()
        posts.removeFirst().invoke()
        verify(exactly = 1) { auth.signOut("discarded", any()) }
        verify(exactly = 0) { config.setReverseConnectionCredential(any(), any()) }
    }

    @Test fun replacedTokenRestartsTheAccountCheckAndIgnoresOldMetadata() {
        token = "old"
        every { auth.session(any()) } returns SessionResult.Valid("person@example.com", "org")
        every { auth.workspace(any(), "org") } returns WorkspaceResult.Name("Workspace")
        controller.checkAccount()
        tasks.removeFirst().run()
        token = "replacement"
        posts.removeFirst().invoke()
        assertEquals(1, tasks.size)
        tasks.removeFirst().run()
        posts.removeFirst().invoke()
        verify(exactly = 1) { auth.session("old") }
        verify(exactly = 1) { auth.session("replacement") }
        verify(exactly = 1) { listener.onAccount("person@example.com", "Workspace") }
        verify(exactly = 1) { config.sessionCheckedAt = 50_000L }
    }

    @Test fun manualUriComesFromServerWhenNoSafeQrIsAvailable() {
        every { auth.authorize() } returns authorization(complete = "https://other.example/device")
        controller.refreshCode()
        tasks.removeFirst().run()
        posts.removeFirst().invoke()
        verify { listener.onCode("USER", "https://cloud.mobilerun.ai/custom-device", null, 11_000L, 10_000L) }
    }

    @Test fun signOutWithoutAnActiveSessionDoesNotCallTheServer() {
        controller.signOut()
        verify(exactly = 0) { auth.signOut(any(), any()) }
        token = "api-key"
        every { config.reverseConnectionCredentialType } returns ConfigManager.CREDENTIAL_API_KEY
        controller.signOut()
        verify(exactly = 0) { auth.signOut(any(), any()) }
    }
}
