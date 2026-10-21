package com.mobilerun.portal.auth

import com.mobilerun.portal.BuildConfig
import com.mobilerun.portal.config.ConfigManager
import com.mobilerun.portal.core.GenerationGate
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCodeAuthTest {
    @Test fun pendingUsesServerIntervalAndSlowDownPersists() {
        val state = DeviceCodePollState(7, 100_000)
        assertEquals(7000L, state.nextDelay(DeviceTokenResult.Error("authorization_pending"), 0))
        assertEquals(12000L, state.nextDelay(DeviceTokenResult.Error("slow_down"), 7000))
        assertEquals(12000L, state.nextDelay(DeviceTokenResult.Error("authorization_pending"), 19000))
        assertEquals(17000L, state.nextDelay(DeviceTokenResult.Error("slow_down"), 31000))
    }

    @Test fun networkBackoffDoesNotResetSlowDown() {
        val state = DeviceCodePollState(5, 100_000)
        assertEquals(10000L, state.nextDelay(DeviceTokenResult.NetworkError, 0))
        assertEquals(20000L, state.nextDelay(DeviceTokenResult.NetworkError, 10000))
        assertEquals(10000L, state.nextDelay(DeviceTokenResult.Error("slow_down"), 30000))
        assertEquals(10000L, state.nextDelay(DeviceTokenResult.Error("authorization_pending"), 40000))
    }

    @Test fun denialUnknownErrorAndExpiryAreTerminal() {
        for (error in listOf("access_denied", "expired_token", "invalid_client")) {
            assertNull(DeviceCodePollState(5, 1000).nextDelay(DeviceTokenResult.Error(error), 0))
        }
        val state = DeviceCodePollState(5, 1000)
        assertFalse(state.expired(999))
        assertTrue(state.expired(1000))
        assertNull(state.nextDelay(DeviceTokenResult.Error("authorization_pending"), 1000))
        assertNull(state.nextDelay(DeviceTokenResult.NetworkError, 1001))
    }

    @Test fun codeRefreshAndSignOutInvalidateInflightResults() {
        val gate = GenerationGate()
        val first = gate.advance()
        assertTrue(gate.isCurrent(first))
        val refresh = gate.advance()
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(refresh))
        gate.advance() // sign out / activity stop
        assertFalse(gate.isCurrent(refresh))
    }

    private fun client(status: Int = 200, body: String = "null", inspect: (Interceptor.Chain) -> Unit = {}): DeviceCodeAuthClient {
        return DeviceCodeAuthClient(OkHttpClient.Builder().addInterceptor { chain ->
            inspect(chain)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("test").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build())
    }

    @Test fun authorizationUsesJsonClientAndSpecificUserAgentWithDefaultInterval() {
        val auth = client(body = """{"device_code":"device","user_code":"USER","verification_uri":"https://example.com","verification_uri_complete":"https://example.com/device?user_code=USER","expires_in":120}""") { chain ->
            assertEquals("application/json", chain.request().header("Accept"))
            assertEquals("${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}", chain.request().header("User-Agent"))
            val buffer = okio.Buffer()
            chain.request().body!!.writeTo(buffer)
            val json = JSONObject(buffer.readUtf8())
            assertEquals("mobilerun-portal", json.getString("client_id"))
            assertEquals("openid profile email", json.getString("scope"))
        }
        val code = auth.authorize()
        assertEquals(5L, code.interval)
        assertEquals(120L, code.expiresIn)
    }

    @Test fun pollingMapsSuccessPendingAndTransportFailure() {
        val success = client(body = """{"access_token":"secret"}""").poll("device")
        assertTrue(success is DeviceTokenResult.Authorized)
        assertEquals("secret", (success as DeviceTokenResult.Authorized).token)
        assertEquals(DeviceTokenResult.Error("authorization_pending"),
            client(400, """{"error":"authorization_pending"}""").poll("device"))
        val offline = DeviceCodeAuthClient(OkHttpClient.Builder().addInterceptor { throw IOException("offline") }.build())
        assertEquals(DeviceTokenResult.NetworkError, offline.poll("device"))
    }

    @Test fun sessionAndWorkspace401InvalidateButPermissionBillingNetworkDoNot() {
        assertEquals(SessionResult.Invalid, client(401).session("token"))
        assertEquals(SessionResult.Invalid, client().session("token"))
        assertEquals(WorkspaceResult.Invalid, client(401).workspace("token", "org"))
        for (status in listOf(402, 403, 500)) {
            assertEquals(SessionResult.Unavailable, client(status).session("token"))
            assertEquals(WorkspaceResult.Unavailable, client(status).workspace("token", "org"))
        }
        val offline = DeviceCodeAuthClient(OkHttpClient.Builder().addInterceptor { throw IOException("offline") }.build())
        assertEquals(SessionResult.Unavailable, offline.session("token"))
        assertEquals(SessionResult.Valid("tv@example.com", "org"), client(body =
            """{"user":{"email":"tv@example.com"},"session":{"activeOrganizationId":"org"}}""").session("token"))
        assertEquals(WorkspaceResult.Name("Workspace"), client(body =
            """[{"id":"other","name":"Other"},{"id":"org","name":"Workspace"}]""").workspace("token", "org"))
    }

    @Test fun lifecyclePreservesApiKeysAndNewerSessions() {
        val config = mockk<ConfigManager>(relaxed = true)
        every { config.reverseConnectionCredentialType } returns ConfigManager.CREDENTIAL_API_KEY
        every { config.reverseConnectionToken } returns "current"
        assertFalse(SessionCredential.clear(config, "current"))
        every { config.reverseConnectionCredentialType } returns SessionCredential.TYPE
        assertFalse(SessionCredential.clear(config, "stale"))
        verify(exactly = 0) { config.setReverseConnectionCredential(any(), any()) }
        assertTrue(SessionCredential.clear(config, "current"))
        verify { config.setReverseConnectionCredential("", ConfigManager.CREDENTIAL_API_KEY) }
        verify { config.reverseConnectionEnabled = false }
    }

    @Test fun serverHtmlRateLimitsAndMalformedBodiesBackOff() {
        for ((status, body) in listOf(503 to "<html>unavailable</html>", 429 to "rate limit", 200 to "bad json", 400 to "{}")) {
            val result = client(status, body).poll("device")
            assertEquals(DeviceTokenResult.NetworkError, result)
            assertEquals(10000L, DeviceCodePollState(5, 100_000).nextDelay(result, 0))
        }
        assertEquals(DeviceTokenResult.Error("access_denied"), client(400, """{"error":"access_denied"}""").poll("device"))
    }

    @Test fun dailyCheckUsesWallClockIncludingRollback() {
        val day = DeviceCodeAuthClient.SESSION_CHECK_INTERVAL_MS
        assertTrue(DeviceCodeAuthClient.sessionCheckDue(0, 100))
        assertFalse(DeviceCodeAuthClient.sessionCheckDue(100, day))
        assertTrue(DeviceCodeAuthClient.sessionCheckDue(100, 100 + day))
        assertTrue(DeviceCodeAuthClient.sessionCheckDue(100, 99))
    }

    @Test fun qrOnlyUsesOfficialCloudOrigin() {
        assertTrue(DeviceCodeAuthClient.isSafeVerificationUri("https://cloud.mobilerun.ai/device?user_code=USER"))
        for (uri in listOf("http://cloud.mobilerun.ai/device", "https://cloud.mobilerun.ai.evil.example/device",
                "https://cloud.mobilerun.ai@evil.example/device", "https://evil.example/device")) {
            assertFalse(DeviceCodeAuthClient.isSafeVerificationUri(uri))
        }
    }

    @Test fun signOutPostsBearerAndCompletesEvenOnNetworkFailure() {
        val completed = CountDownLatch(1)
        client(503) { chain ->
            assertEquals(DeviceCodeAuthClient.BASE_URL + "sign-out", chain.request().url.toString())
            assertEquals("POST", chain.request().method)
            assertEquals("Bearer secret", chain.request().header("Authorization"))
        }.signOut("secret") { completed.countDown() }
        assertTrue(completed.await(3, TimeUnit.SECONDS))
        val offline = DeviceCodeAuthClient(OkHttpClient.Builder().addInterceptor { throw IOException("offline") }.build())
        val failed = CountDownLatch(1)
        offline.signOut("secret") { failed.countDown() }
        assertTrue(failed.await(3, TimeUnit.SECONDS))
    }
}
