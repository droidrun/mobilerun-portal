package com.mobilerun.portal.auth

import android.content.Context
import android.content.Intent
import com.mobilerun.portal.BuildConfig
import com.mobilerun.portal.config.ConfigManager
import io.mockk.*
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

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
        val gate = AuthGeneration()
        val first = gate.next()
        assertTrue(gate.isCurrent(first))
        val refresh = gate.next()
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(refresh))
        gate.next() // sign out / activity stop
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
            assertEquals("mobilerun-portal-tv/${BuildConfig.VERSION_NAME}", chain.request().header("User-Agent"))
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
        mockkObject(ConfigManager.Companion)
        try {
            val context = mockk<Context>(relaxed = true)
            val config = mockk<ConfigManager>(relaxed = true)
            every { ConfigManager.getInstance(context) } returns config
            every { config.reverseConnectionCredentialType } returns "api_key"
            every { config.reverseConnectionToken } returns "current"
            assertFalse(SessionCredential.clear(context, "current"))
            every { config.reverseConnectionCredentialType } returns SessionCredential.TYPE
            assertFalse(SessionCredential.clear(context, "stale"))
            verify(exactly = 0) { config.reverseConnectionToken = any() }
            assertTrue(SessionCredential.clear(context, "current"))
            verify { config.reverseConnectionToken = ""; config.reverseConnectionEnabled = false }
            verify(exactly = 1) { context.stopService(any<Intent>()) }
        } finally { unmockkObject(ConfigManager.Companion) }
    }
}
