package com.mobilerun.portal.auth

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DeviceCodeAuthClientTest {
    private val requests = mutableListOf<Request>()

    private fun client(code: Int, body: String): DeviceCodeAuthClient {
        val http = mockk<OkHttpClient>()
        val request = slot<Request>()
        every { http.newCall(capture(request)) } answers {
            requests += request.captured
            mockk<Call>().also { call ->
                every { call.execute() } returns Response.Builder()
                    .request(request.captured)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("x")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
        }
        return DeviceCodeAuthClient(http, "https://cloud.example.test/api/auth/")
    }

    private fun offlineClient(): DeviceCodeAuthClient {
        val http = mockk<OkHttpClient>()
        every { http.newCall(any()) } answers {
            mockk<Call>().also { every { it.execute() } throws IOException("offline") }
        }
        return DeviceCodeAuthClient(http, "https://cloud.example.test/api/auth/")
    }

    private fun Request.jsonBody(): JSONObject =
        JSONObject(Buffer().also { body!!.writeTo(it) }.readUtf8())

    @Test
    fun requestCode_sendsClientAndDeviceIdAndParsesTheCode() {
        val result = client(
            200,
            """{"device_code":"dc","user_code":"ABCD1234","verification_uri":"https://cloud.mobilerun.ai/device",
               "verification_uri_complete":"https://cloud.mobilerun.ai/device?user_code=ABCD1234",
               "expires_in":1800,"interval":5}""",
        ).requestCode("11111111-1111-4111-8111-111111111111")

        val issued = (result as DeviceCodeResult.Issued).authorization
        assertEquals("dc", issued.deviceCode)
        assertEquals("ABCD1234", issued.userCode)
        assertEquals(1800L, issued.expiresInSeconds)
        assertEquals(5L, issued.intervalSeconds)
        val request = requests.single()
        assertEquals("https://cloud.example.test/api/auth/device/code", request.url.toString())
        val body = request.jsonBody()
        assertEquals("mobilerun-portal", body.getString("client_id"))
        assertEquals("11111111-1111-4111-8111-111111111111", body.getString("device_id"))
    }

    @Test
    fun requestCode_mapsRejectionsAndRetryableFailures() {
        assertEquals(
            DeviceCodeResult.Rejected("invalid_client"),
            client(400, """{"error":"invalid_client"}""").requestCode("id"),
        )
        assertEquals(DeviceCodeResult.Rejected("http_404"), client(404, "").requestCode("id"))
        assertEquals(DeviceCodeResult.NetworkError, client(503, "").requestCode("id"))
        assertEquals(DeviceCodeResult.NetworkError, client(429, "").requestCode("id"))
        assertEquals(DeviceCodeResult.NetworkError, offlineClient().requestCode("id"))
        assertEquals(
            DeviceCodeResult.Rejected("invalid_response"),
            client(200, """{"device_code":"dc"}""").requestCode("id"),
        )
    }

    @Test
    fun poll_mapsTokenAndRfc8628Errors() {
        val authorized = client(200, """{"access_token":"session","token_type":"Bearer"}""").poll("dc")
        assertEquals("session", (authorized as DeviceTokenResult.Authorized).token)
        val body = requests.single().jsonBody()
        assertEquals("urn:ietf:params:oauth:grant-type:device_code", body.getString("grant_type"))
        assertEquals("dc", body.getString("device_code"))
        assertEquals("mobilerun-portal", body.getString("client_id"))

        assertEquals(
            DeviceTokenResult.Error("authorization_pending"),
            client(400, """{"error":"authorization_pending"}""").poll("dc"),
        )
        assertEquals(DeviceTokenResult.NetworkError, client(500, "").poll("dc"))
        assertEquals(DeviceTokenResult.NetworkError, offlineClient().poll("dc"))
    }

    @Test
    fun session_usesGetAndTreatsNullAsSignedOut() {
        assertEquals(SessionResult.Valid, client(200, """{"session":{"id":"s"},"user":{}}""").session("t"))
        val request = requests.single()
        assertEquals("GET", request.method)
        assertEquals("Bearer t", request.header("Authorization"))
        assertEquals(SessionResult.Invalid, client(200, "null").session("t"))
        assertEquals(SessionResult.Invalid, client(401, "").session("t"))
        assertEquals(SessionResult.Unavailable, client(503, "").session("t"))
        assertEquals(SessionResult.Unavailable, offlineClient().session("t"))
    }

    @Test
    fun helpers() {
        assertEquals("ABCD-1234", DeviceCodeAuthClient.formatUserCode("ABCD1234"))
        assertEquals("ABC", DeviceCodeAuthClient.formatUserCode("ABC"))
        assertTrue(DeviceCodeAuthClient.isSafeVerificationUri("https://cloud.mobilerun.ai/device?user_code=X"))
        assertFalse(DeviceCodeAuthClient.isSafeVerificationUri("https://cloud.mobilerun.ai.evil.test/device"))
        val hours72 = DeviceCodeAuthClient.SESSION_CHECK_INTERVAL_MS
        assertTrue(DeviceCodeAuthClient.sessionCheckDue(0, 1_000))
        assertFalse(DeviceCodeAuthClient.sessionCheckDue(1_000, 1_000 + hours72 - 1))
        assertTrue(DeviceCodeAuthClient.sessionCheckDue(1_000, 1_000 + hours72))
        assertTrue(DeviceCodeAuthClient.sessionCheckDue(5_000, 1_000))
    }

    @Test
    fun pollState_followsIntervalSlowDownAndBackoff() {
        val state = DeviceCodePollState(intervalSeconds = 5, expiresAtMs = 100_000)
        assertEquals(5_000L, state.nextDelay(DeviceTokenResult.Error("authorization_pending"), 0))
        assertEquals(10_000L, state.nextDelay(DeviceTokenResult.Error("slow_down"), 0))
        assertEquals(20_000L, state.nextDelay(DeviceTokenResult.NetworkError, 0))
        assertEquals(40_000L, state.nextDelay(DeviceTokenResult.NetworkError, 0))
        assertEquals(60_000L, state.nextDelay(DeviceTokenResult.NetworkError, 0))
        assertEquals(10_000L, state.nextDelay(DeviceTokenResult.Error("authorization_pending"), 0))
        assertEquals(20_000L, state.nextDelay(DeviceTokenResult.NetworkError, 0))
        assertNull(state.nextDelay(DeviceTokenResult.Error("invalid_grant"), 0))
        assertNull(state.nextDelay(DeviceTokenResult.Error("authorization_pending"), 100_000))
        assertNull(state.nextDelay(DeviceTokenResult.Authorized("t"), 0))
    }
}
