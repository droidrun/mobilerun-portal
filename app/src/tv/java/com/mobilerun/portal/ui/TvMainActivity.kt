package com.mobilerun.portal.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.mobilerun.portal.R
import com.mobilerun.portal.api.ApiResponse
import com.mobilerun.portal.auth.AuthGeneration
import com.mobilerun.portal.auth.DeviceAuthorization
import com.mobilerun.portal.auth.DeviceCodeAuthClient
import com.mobilerun.portal.auth.DeviceCodePollState
import com.mobilerun.portal.auth.DeviceTokenResult
import com.mobilerun.portal.auth.SessionCredential
import com.mobilerun.portal.auth.SessionResult
import com.mobilerun.portal.auth.WorkspaceResult
import com.mobilerun.portal.config.ConfigManager
import com.mobilerun.portal.databinding.ActivityTvMainBinding
import com.mobilerun.portal.service.MobilerunAccessibilityService
import com.mobilerun.portal.service.MobilerunNotificationListener
import com.mobilerun.portal.service.ReverseConnectionService
import com.mobilerun.portal.service.connectCloudCredential
import com.mobilerun.portal.state.ConnectionStateManager
import com.mobilerun.portal.streaming.WebRtcManager
import com.mobilerun.portal.update.UpdateCheckResult
import com.mobilerun.portal.update.UpdateChecker
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class TvMainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTvMainBinding
    private lateinit var config: ConfigManager
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val auth = DeviceCodeAuthClient()
    private val generation = AuthGeneration()
    private var pollTask: ScheduledFuture<*>? = null
    private var expiresAt = 0L
    private var authorizing = false
    private var email = ""
    private var workspace = ""
    private var shownToken: String? = null
    private var active = false
    private var accountCheckInFlight = false

    private val tick = object : Runnable {
        override fun run() {
            renderStoredState()
            updateSetup()
            if (authorizing && expiresAt > 0) {
                val seconds = ((expiresAt - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 999) / 1000
                binding.countdown.text = getString(R.string.tv_countdown, seconds / 60, seconds % 60)
                if (seconds == 0L) newCode()
            }
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        config = ConfigManager.getInstance(this)
        binding.accessibility.setOnClickListener {
            openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS,
                getString(R.string.tv_a11y_fallback, packageName, MobilerunAccessibilityService::class.java.name))
        }
        binding.notifications.setOnClickListener {
            openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
                getString(R.string.tv_listener_fallback, packageName, MobilerunNotificationListener::class.java.name))
        }
        binding.capture.setOnClickListener {
            startActivity(Intent(this, ScreenCaptureActivity::class.java)
                .putExtra(ScreenCaptureActivity.EXTRA_AUTO_CAPTURE_SIZE, true))
        }
        binding.newCode.setOnClickListener { newCode() }
        binding.signOut.setOnClickListener {
            cancelAuthorization()
            SessionCredential.clear(this, config.reverseConnectionToken)
            renderStoredState()
        }
        binding.updates.setOnClickListener { checkUpdates() }
        ConnectionStateManager.connectionState.observe(this) { renderHome() }
        binding.accessibility.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        active = true
        renderStoredState()
        if (hasSession()) checkAccount(config.reverseConnectionToken)
        main.post(tick)
    }

    override fun onStop() {
        active = false
        cancelAuthorization()
        main.removeCallbacks(tick)
        super.onStop()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun hasSession() = config.reverseConnectionCredentialType == SessionCredential.TYPE &&
        config.reverseConnectionToken.isNotBlank()

    private fun cancelAuthorization() {
        generation.next()
        pollTask?.cancel(false)
        pollTask = null
        authorizing = false
        expiresAt = 0
    }

    private fun renderStoredState() {
        val signedIn = hasSession()
        binding.home.visibility = if (signedIn) View.VISIBLE else View.GONE
        binding.signIn.visibility = if (signedIn) View.GONE else View.VISIBLE
        if (signedIn) {
            if (authorizing) cancelAuthorization()
            val token = config.reverseConnectionToken
            if (shownToken != token) {
                shownToken = token
                email = getString(R.string.tv_session_saved)
                workspace = getString(R.string.tv_workspace_unknown)
                if (active) checkAccount(token)
            }
            renderHome()
        } else {
            shownToken = null
            if (!authorizing && active) newCode()
        }
    }

    private fun renderHome() {
        if (!::config.isInitialized || !::binding.isInitialized) return
        binding.account.text = getString(R.string.tv_home, email, workspace, config.deviceName,
            ConnectionStateManager.getState().name.replace('_', ' '))
    }

    private fun updateSetup() {
        fun status(on: Boolean) = getString(if (on) R.string.tv_on else R.string.tv_off)
        binding.setupStatus.text = getString(R.string.tv_status,
            status(MobilerunAccessibilityService.getInstance() != null),
            status(WebRtcManager.getExistingInstance()?.isCaptureActive() == true),
            status(NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)))
    }

    private fun openSettings(action: String, fallback: String) {
        try {
            startActivity(Intent(action))
            binding.fallback.visibility = View.GONE
        } catch (_: Exception) {
            binding.fallback.text = fallback
            binding.fallback.visibility = View.VISIBLE
        }
    }

    private fun deliver(id: Long, action: () -> Unit) {
        main.post { if (active && generation.isCurrent(id) && !hasSession()) action() }
    }

    private fun newCode() {
        cancelAuthorization()
        if (!active || hasSession()) return
        val id = generation.next()
        authorizing = true
        binding.userCode.text = ""
        binding.qr.setImageDrawable(null)
        binding.countdown.text = ""
        binding.message.setText(R.string.tv_loading)
        // Include code-request latency in expiry: never accept a code beyond the server lifetime.
        val requestedAt = SystemClock.elapsedRealtime()
        worker.execute {
            try {
                val code = auth.authorize()
                val deadline = requestedAt + code.expiresIn * 1000
                val matrix = MultiFormatWriter().encode(code.verificationUriComplete,
                    BarcodeFormat.QR_CODE, 440, 440)
                val pixels = IntArray(440 * 440) { i -> if (matrix[i % 440, i / 440]) Color.BLACK else Color.WHITE }
                val bitmap = Bitmap.createBitmap(pixels, 440, 440, Bitmap.Config.ARGB_8888)
                deliver(id) {
                    expiresAt = deadline
                    binding.userCode.text = code.userCode
                    binding.qr.setImageBitmap(bitmap)
                    binding.message.text = ""
                    schedulePoll(id, code, DeviceCodePollState(code.interval, deadline), code.interval * 1000)
                }
            } catch (_: Exception) {
                deliver(id) {
                    // Leave terminal failures visible until the user chooses a new code.
                    binding.message.setText(R.string.tv_error)
                }
            }
        }
    }

    private fun schedulePoll(id: Long, code: DeviceAuthorization, state: DeviceCodePollState, delay: Long) {
        if (!generation.isCurrent(id)) return
        pollTask = worker.schedule({
            if (!generation.isCurrent(id) || state.expired(SystemClock.elapsedRealtime())) return@schedule
            val result = auth.poll(code.deviceCode)
            deliver(id) {
                if (state.expired(SystemClock.elapsedRealtime())) {
                    newCode()
                } else if (result is DeviceTokenResult.Authorized) {
                    cancelAuthorization()
                    val connected = connectCloudCredential(applicationContext, config, result.token,
                        beforeEnable = { config.reverseConnectionCredentialType = SessionCredential.TYPE })
                    if (connected is ApiResponse.Error) binding.message.setText(R.string.tv_error)
                    renderStoredState()
                    binding.updates.requestFocus()
                } else {
                    val next = state.nextDelay(result, SystemClock.elapsedRealtime())
                    if (next != null) schedulePoll(id, code, state, next)
                    else if (result is DeviceTokenResult.Error && result.code == "expired_token") newCode()
                    else {
                        binding.message.setText(if (result is DeviceTokenResult.Error && result.code == "access_denied")
                            R.string.tv_denied else R.string.tv_error)
                        // Denial is terminal; expiry still refreshes automatically on the countdown.
                        expiresAt = 0
                    }
                }
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun checkAccount(token: String) {
        if (accountCheckInFlight) return
        accountCheckInFlight = true
        worker.execute {
            val session = auth.session(token)
            val organization = if (session is SessionResult.Valid) auth.workspace(token, session.organizationId) else null
            main.post {
                accountCheckInFlight = false
                if (isDestroyed || config.reverseConnectionToken != token || !hasSession()) return@post
                if (session == SessionResult.Invalid || organization == WorkspaceResult.Invalid) {
                    cancelAuthorization()
                    SessionCredential.clear(this, token)
                    renderStoredState()
                } else {
                    if (session is SessionResult.Valid) {
                        email = session.user
                        workspace = (organization as? WorkspaceResult.Name)?.value ?: getString(R.string.tv_workspace_unknown)
                        renderHome()
                    }
                    // Re-establish a persisted credential after process recreation, preserving HTTP 402 blocks.
                    if (active && ReverseConnectionService.getInstance() == null) {
                        config.reverseConnectionEnabled = true
                        startForegroundService(Intent(this, ReverseConnectionService::class.java))
                    }
                }
            }
        }
    }

    private fun checkUpdates() {
        UpdateChecker.checkForUpdate(this) { result ->
            if (isFinishing || isDestroyed) return@checkForUpdate
            when (result) {
                is UpdateCheckResult.Available -> AlertDialog.Builder(this)
                    .setTitle(R.string.tv_install).setMessage(result.info.latestVersion)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.tv_install) { _, _ ->
                        if (!packageManager.canRequestPackageInstalls()) {
                            UpdateChecker.openInstallPermissionSettings(this)
                        } else UpdateChecker.downloadAndInstall(this, result.info,
                            { binding.message.text = getString(R.string.tv_update_progress, it) },
                            { binding.message.text = it })
                    }.show()
                is UpdateCheckResult.Failed -> binding.message.text = result.message
                UpdateCheckResult.UpToDate -> binding.message.setText(R.string.tv_no_updates)
            }
        }
    }
}

/** TV-only boot restore. A cleared session cannot restart the connection after reboot. */
class TvBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val config = ConfigManager.getInstance(context)
        if (config.reverseConnectionCredentialType == SessionCredential.TYPE && config.reverseConnectionToken.isNotBlank()) {
            config.reverseConnectionEnabled = true
            context.startForegroundService(Intent(context, ReverseConnectionService::class.java))
        }
    }
}
