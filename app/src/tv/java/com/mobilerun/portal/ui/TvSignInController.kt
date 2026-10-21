package com.mobilerun.portal.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.mobilerun.portal.R
import com.mobilerun.portal.api.ApiResponse
import com.mobilerun.portal.auth.*
import com.mobilerun.portal.config.ConfigManager
import com.mobilerun.portal.service.ReverseConnectionService
import com.mobilerun.portal.service.connectCloudCredential
import org.json.JSONException
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Owns device-code/account work across settings trips; views only observe state. */
class TvSignInController(context: Context, private var listener: Listener?) {
    interface Listener {
        fun onSignedIn(signedIn: Boolean)
        fun onCode(userCode: String, verificationUri: String?, deadline: Long, lifetimeMs: Long)
        fun onAccount(user: String?, workspace: String?)
        fun onMessage(message: Int?)
    }

    private val context = context.applicationContext
    private val config = ConfigManager.getInstance(context)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val auth = DeviceCodeAuthClient()
    private val generation = AuthGeneration()
    private var task: ScheduledFuture<*>? = null
    private var requesting = false
    private var code: DeviceAuthorization? = null
    private var deadline = 0L
    private var metadataToken: String? = null
    private var checkingToken: String? = null
    private var foreground = false
    private var closed = false
    private var signingOut = false

    fun hasSession() = config.reverseConnectionCredentialType == SessionCredential.TYPE &&
        config.reverseConnectionToken.isNotBlank()

    fun start() {
        if (closed || signingOut) return
        val signedIn = hasSession()
        listener?.onSignedIn(signedIn)
        if (signedIn) {
            val token = config.reverseConnectionToken
            if (foreground && ReverseConnectionService.getInstance() == null) ReverseConnectionService.start(context)
            if (metadataToken != token || DeviceCodeAuthClient.sessionCheckDue(config.sessionCheckedAt, System.currentTimeMillis())) checkAccount(token)
        } else if (code == null && !requesting) refreshCode()
        else code?.let { listener?.onCode(it.userCode, safeUri(it), deadline, it.expiresIn * 1000) }
    }

    fun setForeground(value: Boolean) { foreground = value }

    fun refreshCode() {
        cancel()
        if (closed || hasSession() || signingOut) return
        requesting = true
        listener?.onCode("", null, 0L, 1L)
        listener?.onMessage(R.string.tv_loading)
        val id = generation.next()
        requestCode(id, 0, 5000)
    }

    /** Explicit cancellation is used for a new code, sign-out or final Activity destruction. */
    fun cancel() {
        generation.next()
        task?.cancel(false)
        task = null
        requesting = false
        code = null
        deadline = 0L
    }

    fun close() {
        closed = true
        listener = null
        cancel()
        worker.shutdown() // Let bounded in-flight requests finish so discarded approvals can be revoked.
    }

    fun signOut() {
        if (signingOut) return
        cancel()
        signingOut = true
        val token = config.reverseConnectionToken
        auth.signOut(token) {
            main.post {
                ReverseConnectionService.clearSession(context, token)
                signingOut = false
                metadataToken = null
                if (!closed) start()
            }
        }
    }

    private fun safeUri(code: DeviceAuthorization) = code.verificationUriComplete
        .takeIf(DeviceCodeAuthClient::isSafeVerificationUri)

    private fun requestCode(id: Long, delay: Long, backoff: Long) {
        task = worker.schedule({
            val requestedAt = SystemClock.elapsedRealtime()
            try {
                val value = auth.authorize()
                main.post {
                    if (closed || !generation.isCurrent(id) || hasSession()) return@post
                    code = value
                    deadline = requestedAt + value.expiresIn * 1000
                    listener?.onMessage(null)
                    listener?.onCode(value.userCode, safeUri(value), deadline, value.expiresIn * 1000)
                    schedulePoll(id, value, DeviceCodePollState(value.interval, deadline), value.interval * 1000)
                }
            } catch (_: IOException) {
                main.post {
                    if (closed || !generation.isCurrent(id) || hasSession()) return@post
                    listener?.onMessage(R.string.tv_retrying)
                    requestCode(id, backoff, (backoff * 2).coerceAtMost(60_000))
                }
            } catch (_: JSONException) { failedCode(id) }
              catch (_: IllegalArgumentException) { failedCode(id) }
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun failedCode(id: Long) {
        main.post { if (!closed && generation.isCurrent(id)) listener?.onMessage(R.string.tv_error) }
    }

    private fun schedulePoll(id: Long, authorization: DeviceAuthorization, state: DeviceCodePollState, delay: Long) {
        task = worker.schedule({
            if (!generation.isCurrent(id)) return@schedule
            if (state.expired(SystemClock.elapsedRealtime())) {
                main.post { if (!closed && generation.isCurrent(id)) refreshCode() }
                return@schedule
            }
            val result = auth.poll(authorization.deviceCode)
            main.post {
                if (closed || !generation.isCurrent(id) || hasSession()) {
                    if (result is DeviceTokenResult.Authorized) auth.signOut(result.token)
                    return@post
                }
                if (state.expired(SystemClock.elapsedRealtime())) {
                    if (result is DeviceTokenResult.Authorized) auth.signOut(result.token)
                    refreshCode()
                } else if (result is DeviceTokenResult.Authorized) {
                    requesting = false
                    code = null
                    val connected = connectCloudCredential(context, config, result.token,
                        credentialType = SessionCredential.TYPE,
                        startReverseConnectionService = { ctx, action ->
                            if (foreground) ReverseConnectionService.start(ctx, action)
                        })
                    if (connected is ApiResponse.Error) listener?.onMessage(R.string.tv_error)
                    else listener?.onMessage(null)
                    start()
                } else {
                    val next = state.nextDelay(result, SystemClock.elapsedRealtime())
                    if (next != null) schedulePoll(id, authorization, state, next)
                    else if (result is DeviceTokenResult.Error && result.code == "expired_token") refreshCode()
                    else {
                        deadline = 0L
                        listener?.onCode(authorization.userCode, safeUri(authorization), 0, authorization.expiresIn * 1000)
                        listener?.onMessage(if (result is DeviceTokenResult.Error && result.code == "access_denied") R.string.tv_denied else R.string.tv_error)
                    }
                }
            }
        }, minOf(delay, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)), TimeUnit.MILLISECONDS)
    }

    fun checkAccount(token: String = config.reverseConnectionToken) {
        if (checkingToken == token || closed) return
        checkingToken = token
        worker.execute {
            val session = auth.session(token)
            val workspace = if (session is SessionResult.Valid) auth.workspace(token, session.organizationId) else null
            main.post {
                if (checkingToken == token) checkingToken = null
                if (closed) return@post
                if (config.reverseConnectionToken != token) {
                    start() // A new token must get its own account check even if the old one was in flight.
                    return@post
                }
                if (!hasSession()) return@post
                if (session == SessionResult.Invalid || workspace == WorkspaceResult.Invalid) {
                    cancel()
                    ReverseConnectionService.clearSession(context, token)
                    metadataToken = null
                    start()
                } else if (session is SessionResult.Valid) {
                    synchronized(config) {
                        if (config.reverseConnectionToken == token) config.sessionCheckedAt = System.currentTimeMillis()
                    }
                    metadataToken = token
                    listener?.onAccount(session.user, (workspace as? WorkspaceResult.Name)?.value)
                } else listener?.onAccount(null, null)
            }
        }
    }
}
