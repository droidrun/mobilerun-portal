package com.mobilerun.portal.auth

import com.mobilerun.portal.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject

/** Sensitive values intentionally have no generated toString(). */
class DeviceAuthorization(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
)

sealed interface DeviceCodeResult {
    class Issued(val authorization: DeviceAuthorization) : DeviceCodeResult
    data class Rejected(val code: String) : DeviceCodeResult
    data object NetworkError : DeviceCodeResult
}

sealed interface DeviceTokenResult {
    class Authorized(val token: String) : DeviceTokenResult
    data class Error(val code: String) : DeviceTokenResult
    data object NetworkError : DeviceTokenResult
}

sealed interface SessionResult {
    data object Valid : SessionResult
    data object Invalid : SessionResult
    data object Unavailable : SessionResult
}

/** RFC 8628 polling delays on a monotonic clock supplied by the caller. */
internal class DeviceCodePollState(intervalSeconds: Long, private val expiresAtMs: Long) {
    private companion object {
        const val MAX_NETWORK_BACKOFF_MS = 60_000L
        const val SLOW_DOWN_STEP_MS = 5_000L
    }

    var intervalMs = intervalSeconds.coerceAtLeast(1) * 1000
        private set
    private var networkBackoffMs = intervalMs

    fun expired(nowMs: Long) = nowMs >= expiresAtMs

    /** Null means stop polling. */
    fun nextDelay(result: DeviceTokenResult, nowMs: Long): Long? {
        if (result is DeviceTokenResult.Authorized) return null
        if (expired(nowMs)) return null
        return when (result) {
            DeviceTokenResult.NetworkError -> {
                networkBackoffMs = (networkBackoffMs * 2).coerceAtMost(MAX_NETWORK_BACKOFF_MS)
                maxOf(intervalMs, networkBackoffMs)
            }

            is DeviceTokenResult.Error -> when (result.code) {
                "authorization_pending" -> {
                    networkBackoffMs = intervalMs
                    intervalMs
                }

                "slow_down" -> {
                    intervalMs += SLOW_DOWN_STEP_MS
                    networkBackoffMs = intervalMs
                    intervalMs
                }

                else -> null
            }

            is DeviceTokenResult.Authorized -> null
        }
    }
}

/** Blocking calls; run them off the main thread. No credentials are logged. */
class DeviceCodeAuthClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .build(),
    private val baseUrl: String = "$CLOUD_ORIGIN/api/auth/",
) {
    companion object {
        const val CLIENT_ID = "mobilerun-portal"
        const val CLOUD_ORIGIN = "https://cloud.mobilerun.ai"
        const val SESSION_CHECK_INTERVAL_MS = 72 * 60 * 60 * 1000L
        private const val DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        private val JSON = "application/json".toMediaType()

        fun sessionCheckDue(lastCheckMs: Long, nowMs: Long) =
            lastCheckMs <= 0 || nowMs < lastCheckMs || nowMs - lastCheckMs >= SESSION_CHECK_INTERVAL_MS

        fun isSafeVerificationUri(uri: String) = uri.startsWith("$CLOUD_ORIGIN/")

        /** XXXX-XXXX for 8-character codes; the approve page ignores the hyphen. */
        fun formatUserCode(code: String): String =
            if (code.length == 8) "${code.substring(0, 4)}-${code.substring(4)}" else code
    }

    private val revokeHttp by lazy { http.newBuilder().callTimeout(5, TimeUnit.SECONDS).build() }

    private fun request(path: String, token: String? = null, json: JSONObject? = null): Request {
        return Request.Builder().url(baseUrl + path)
            .header("User-Agent", "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/json")
            .apply {
                if (token != null) header("Authorization", "Bearer $token")
                if (json != null) post(json.toString().toRequestBody(JSON))
            }
            .build()
    }

    fun requestCode(deviceId: String): DeviceCodeResult = try {
        val body = JSONObject().put("client_id", CLIENT_ID).put("device_id", deviceId)
        http.newCall(request("device/code", json = body)).execute().use { response ->
            if (response.code == 429 || response.code >= 500) return DeviceCodeResult.NetworkError
            val json = JSONObject(response.body?.string().orEmpty().ifBlank { "{}" })
            if (!response.isSuccessful) {
                return DeviceCodeResult.Rejected(json.optString("error").ifBlank { "http_${response.code}" })
            }
            val authorization = DeviceAuthorization(
                deviceCode = json.getString("device_code"),
                userCode = json.getString("user_code"),
                verificationUri = json.getString("verification_uri"),
                verificationUriComplete = json.optString("verification_uri_complete"),
                expiresInSeconds = json.getLong("expires_in"),
                intervalSeconds = json.optLong("interval", 5).coerceAtLeast(1),
            )
            if (authorization.deviceCode.isBlank() || authorization.userCode.isBlank() ||
                authorization.expiresInSeconds <= 0
            ) {
                DeviceCodeResult.Rejected("invalid_response")
            } else {
                DeviceCodeResult.Issued(authorization)
            }
        }
    } catch (_: IOException) {
        DeviceCodeResult.NetworkError
    } catch (_: JSONException) {
        DeviceCodeResult.Rejected("invalid_response")
    }

    fun poll(deviceCode: String): DeviceTokenResult = try {
        val body = JSONObject()
            .put("grant_type", DEVICE_CODE_GRANT)
            .put("device_code", deviceCode)
            .put("client_id", CLIENT_ID)
        http.newCall(request("device/token", json = body)).execute().use { response ->
            if (response.code == 429 || response.code >= 500) return DeviceTokenResult.NetworkError
            val json = JSONObject(response.body?.string().orEmpty().ifBlank { "{}" })
            val token = json.optString("access_token")
            val error = json.optString("error")
            when {
                response.isSuccessful && token.isNotBlank() -> DeviceTokenResult.Authorized(token)
                error.isNotBlank() -> DeviceTokenResult.Error(error)
                else -> DeviceTokenResult.NetworkError
            }
        }
    } catch (_: IOException) {
        DeviceTokenResult.NetworkError
    } catch (_: JSONException) {
        DeviceTokenResult.NetworkError
    }

    /** GET only: POST get-session is rejected. A null body means the session is gone. */
    fun session(token: String): SessionResult = try {
        http.newCall(request("get-session", token)).execute().use { response ->
            if (response.code == 401) return SessionResult.Invalid
            if (!response.isSuccessful) return SessionResult.Unavailable
            val raw = response.body?.string()?.trim()
            if (raw.isNullOrBlank() || raw == "null") return SessionResult.Invalid
            if (JSONObject(raw).optJSONObject("session") == null) SessionResult.Invalid else SessionResult.Valid
        }
    } catch (_: IOException) {
        SessionResult.Unavailable
    } catch (_: JSONException) {
        SessionResult.Unavailable
    }

    /** Best effort: sign-out answers 200 whether or not the session existed. */
    fun signOut(token: String) {
        revokeHttp.newCall(request("sign-out", token, JSONObject())).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = Unit
            override fun onResponse(call: Call, response: Response) = response.close()
        })
    }
}
