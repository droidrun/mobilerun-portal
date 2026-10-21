package com.mobilerun.portal.auth

import com.mobilerun.portal.BuildConfig
import com.mobilerun.portal.config.ConfigManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Callback
import okhttp3.Call
import okhttp3.Response
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONException
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Sensitive values intentionally have no generated toString(). */
class DeviceAuthorization(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val expiresIn: Long,
    val interval: Long,
)

sealed interface DeviceTokenResult {
    class Authorized(val token: String) : DeviceTokenResult
    data class Error(val code: String) : DeviceTokenResult
    data object NetworkError : DeviceTokenResult
}

sealed interface SessionResult {
    data class Valid(val user: String, val organizationId: String?) : SessionResult
    data object Invalid : SessionResult
    data object Unavailable : SessionResult
}

sealed interface WorkspaceResult {
    data class Name(val value: String?) : WorkspaceResult
    data object Invalid : WorkspaceResult
    data object Unavailable : WorkspaceResult
}

/** RFC 8628 timing policy, using a monotonic clock supplied by the caller. */
internal class DeviceCodePollState(intervalSeconds: Long, private val expiresAt: Long) {
    var intervalMs = intervalSeconds.coerceAtLeast(1) * 1000
        private set
    private var networkBackoffMs = intervalMs
    fun expired(now: Long) = now >= expiresAt
    fun nextDelay(result: DeviceTokenResult, now: Long): Long? {
        if (expired(now)) return null
        return when (result) {
            DeviceTokenResult.NetworkError -> {
                networkBackoffMs = (networkBackoffMs * 2).coerceAtMost(60_000)
                maxOf(intervalMs, networkBackoffMs)
            }
            is DeviceTokenResult.Error -> when (result.code) {
                "authorization_pending" -> intervalMs
                "slow_down" -> { intervalMs += 5000; intervalMs }
                else -> null
            }
            is DeviceTokenResult.Authorized -> null
        }
    }
}

internal class AuthGeneration {
    private var generation = 0L
    @Synchronized fun next(): Long = ++generation
    @Synchronized fun isCurrent(value: Long): Boolean = value == generation
}

class DeviceCodeAuthClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .build(),
) {
    companion object {
        const val CLIENT_ID = "mobilerun-portal"
        const val BASE_URL = "https://cloud.mobilerun.ai/api/auth/"
        const val SESSION_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
        fun sessionCheckDue(lastSuccess: Long, now: Long) =
            lastSuccess <= 0 || now < lastSuccess || now - lastSuccess >= SESSION_CHECK_INTERVAL_MS
        fun isSafeVerificationUri(uri: String) = uri.startsWith("https://cloud.mobilerun.ai/")
    }

    private val revokeHttp = http.newBuilder().callTimeout(3, TimeUnit.SECONDS).build()

    fun signOut(token: String, onComplete: () -> Unit = {}) {
        revokeHttp.newCall(request("sign-out", token, JSONObject())).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = onComplete()
            override fun onResponse(call: Call, response: Response) {
                response.close()
                onComplete()
            }
        })
    }

    private fun request(path: String, token: String? = null, json: JSONObject? = null): Request {
        return Request.Builder().url(BASE_URL + path)
            .header("User-Agent", "mobilerun-portal-tv/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/json")
            .apply {
                if (token != null) header("Authorization", "Bearer $token")
                if (json != null) post(json.toString().toRequestBody("application/json".toMediaType()))
            }.build()
    }

    /** Blocking calls run exclusively on the TV/service auth executor. No credentials are logged. */
    fun authorize(): DeviceAuthorization = http.newCall(request("device/code", json = JSONObject()
        .put("client_id", CLIENT_ID).put("scope", "openid profile email"))).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Unable to request sign-in code")
        val json = JSONObject(response.body?.string() ?: throw IOException("Empty code response"))
        val code = json.getString("device_code")
        val user = json.getString("user_code")
        val uri = json.getString("verification_uri")
        val complete = json.optString("verification_uri_complete")
        val expiry = json.getLong("expires_in")
        require(code.isNotBlank() && user.isNotBlank() && expiry > 0)
        DeviceAuthorization(code, user, uri, complete, expiry, json.optLong("interval", 5).coerceAtLeast(1))
    }

    fun poll(deviceCode: String): DeviceTokenResult = try {
        http.newCall(request("device/token", json = JSONObject()
            .put("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .put("device_code", deviceCode).put("client_id", CLIENT_ID))).execute().use { response ->
            if (response.code == 429 || response.code >= 500) return DeviceTokenResult.NetworkError
            val json = JSONObject(response.body?.string().orEmpty())
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
    } catch (_: JSONException) { DeviceTokenResult.NetworkError }

    fun session(token: String): SessionResult = try {
        http.newCall(request("get-session", token)).execute().use { response ->
            if (response.code == 401) return SessionResult.Invalid
            if (!response.isSuccessful) return SessionResult.Unavailable
            val raw = response.body?.string()?.trim()
            if (raw.isNullOrBlank() || raw == "null") return SessionResult.Invalid
            val json = JSONObject(raw)
            val user = json.getJSONObject("user")
            val session = json.getJSONObject("session")
            SessionResult.Valid(user.optString("email").ifBlank { user.optString("name") },
                session.optString("activeOrganizationId").takeUnless { it.isBlank() || it == "null" })
        }
    } catch (_: IOException) { SessionResult.Unavailable }
      catch (_: JSONException) { SessionResult.Unavailable }

    fun workspace(token: String, organizationId: String?): WorkspaceResult = try {
        http.newCall(request("organization/list", token)).execute().use { response ->
            if (response.code == 401) return WorkspaceResult.Invalid
            if (!response.isSuccessful) return WorkspaceResult.Unavailable
            val list = JSONArray(response.body?.string().orEmpty())
            val match = (0 until list.length()).map { list.getJSONObject(it) }
                .firstOrNull { it.optString("id") == organizationId }
            WorkspaceResult.Name(match?.optString("name"))
        }
    } catch (_: IOException) { WorkspaceResult.Unavailable }
      catch (_: JSONException) { WorkspaceResult.Unavailable }
}

/** Config-only compare-and-clear; service lifecycle belongs to the service layer. */
object SessionCredential {
    const val TYPE = ConfigManager.CREDENTIAL_SESSION
    fun clear(config: ConfigManager, expectedToken: String): Boolean = synchronized(config) {
        if (config.reverseConnectionCredentialType != TYPE || config.reverseConnectionToken != expectedToken) return false
        config.setReverseConnectionCredential("")
        config.reverseConnectionEnabled = false
        true
    }
}
