package com.mobilerun.portal.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.mobilerun.portal.BuildConfig
import com.mobilerun.portal.R
import com.mobilerun.portal.auth.DeviceCodeAuthClient
import com.mobilerun.portal.config.ConfigManager
import com.mobilerun.portal.databinding.ActivityTvMainBinding
import com.mobilerun.portal.service.MobilerunAccessibilityService
import com.mobilerun.portal.service.MobilerunNotificationListener
import com.mobilerun.portal.state.ConnectionState
import com.mobilerun.portal.state.ConnectionStateManager
import com.mobilerun.portal.streaming.WebRtcManager
import com.mobilerun.portal.update.UpdateCheckResult
import com.mobilerun.portal.update.UpdateChecker

/** Maps controller and permission/connection callbacks to TV views. */
class TvMainActivity : AppCompatActivity(), TvSignInController.Listener {
    private lateinit var binding: ActivityTvMainBinding
    private lateinit var controller: TvSignInController
    private val main = Handler(Looper.getMainLooper())
    private var deadline = 0L
    private var lifetimeMs = 1L
    private var signedIn = false
    private var displayedQr: String? = null
    private val tick = object : Runnable {
        override fun run() {
            if (deadline > 0) {
                val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                val seconds = (remaining + 999) / 1000
                binding.countdown.text = getString(R.string.tv_countdown, seconds / 60, seconds % 60)
                binding.expiryProgress.progress = ((remaining * 1000) / lifetimeMs).toInt().coerceIn(0, 1000)
                if (remaining == 0L) controller.refreshCode()
            }
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        controller = TvSignInController(this, this)
        binding.footer.text = getString(R.string.tv_footer, BuildConfig.VERSION_NAME)
        binding.deviceName.text = ConfigManager.getInstance(this).deviceName
        binding.accessibility.setOnClickListener {
            openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS, binding.accessibilityHint,
                getString(R.string.tv_a11y_fallback, packageName, MobilerunAccessibilityService::class.java.name))
        }
        binding.notifications.setOnClickListener {
            openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, binding.notificationHint,
                getString(R.string.tv_listener_fallback, packageName, MobilerunNotificationListener::class.java.name))
        }
        binding.capture.setOnClickListener {
            startActivity(Intent(this, ScreenCaptureActivity::class.java)
                .putExtra(ScreenCaptureActivity.EXTRA_AUTO_CAPTURE_SIZE, true))
        }
        binding.newCode.setOnClickListener { controller.refreshCode() }
        binding.signOut.setOnClickListener { controller.signOut() }
        binding.updates.setOnClickListener { checkUpdates() }
        listOf(binding.accessibility, binding.capture, binding.notifications, binding.newCode,
            binding.updates, binding.signOut).forEach { button ->
            button.setOnFocusChangeListener { view, focused ->
                view.animate().scaleX(if (focused) 1.04f else 1f).scaleY(if (focused) 1.04f else 1f)
                    .translationZ(if (focused) dp(4f) else 0f).setDuration(120).start()
            }
        }
        listOf(binding.step1, binding.step2, binding.step3).forEach {
            it.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(1.5f).toInt(), getColor(R.color.tv_on_primary))
            }
        }
        ConnectionStateManager.connectionState.observe(this) {
            controller.start()
            renderSetup()
            renderConnection()
        }
    }

    override fun onStart() {
        super.onStart()
        controller.setForeground(true)
        controller.start()
        renderSetup()
        main.post(tick)
    }

    override fun onResume() {
        super.onResume()
        renderSetup()
    }

    override fun onStop() {
        controller.setForeground(false)
        main.removeCallbacks(tick)
        super.onStop() // Approval polling intentionally continues while Settings is in front.
    }

    override fun onDestroy() {
        controller.close()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onSignedIn(signedIn: Boolean) {
        val changed = this.signedIn != signedIn
        this.signedIn = signedIn
        binding.home.visibility = if (signedIn) View.VISIBLE else View.GONE
        binding.signIn.visibility = if (signedIn) View.GONE else View.VISIBLE
        binding.headline.setText(if (signedIn) R.string.tv_headline_home else R.string.tv_headline)
        if (signedIn) deadline = 0
        if (changed) {
            binding.account.setText(R.string.tv_session_saved)
            binding.workspace.setText(R.string.tv_workspace_unknown)
            displayedQr = null
        }
        renderConnection()
        updateFocus(changed)
    }

    override fun onAccount(user: String?, workspace: String?) {
        binding.account.text = user ?: getString(R.string.tv_session_saved)
        binding.workspace.text = workspace ?: getString(R.string.tv_workspace_unknown)
    }

    override fun onMessage(message: Int?) {
        binding.message.visibility = if (message == null) View.GONE else View.VISIBLE
        if (message != null) binding.message.setText(message)
    }

    override fun onCode(userCode: String, verificationUri: String?, deadline: Long, lifetimeMs: Long) {
        this.deadline = deadline
        this.lifetimeMs = lifetimeMs.coerceAtLeast(1)
        val compact = userCode.filter(Char::isLetterOrDigit)
        binding.userCode.text = if (compact.length == 8) compact.chunked(4).joinToString("-") else userCode
        binding.countdown.visibility = if (deadline > 0) View.VISIBLE else View.INVISIBLE
        if (verificationUri == null && userCode.isNotEmpty()) onMessage(R.string.tv_manual_sign_in)
        if (verificationUri == null || verificationUri != displayedQr) {
            displayedQr = verificationUri
            binding.qr.setImageDrawable(null)
            // The controller validates too; keep the rendering boundary explicit.
            if (verificationUri != null && DeviceCodeAuthClient.isSafeVerificationUri(verificationUri)) {
                val matrix = MultiFormatWriter().encode(verificationUri, BarcodeFormat.QR_CODE, 640, 640)
                val ink = getColor(R.color.tv_foreground)
                val pixels = IntArray(640 * 640) { i -> if (matrix[i % 640, i / 640]) ink else Color.WHITE }
                binding.qr.setImageBitmap(Bitmap.createBitmap(pixels, 640, 640, Bitmap.Config.ARGB_8888))
            }
        }
    }

    private fun renderSetup() {
        fun row(status: TextView, button: Button, ready: Boolean) {
            status.setText(if (ready) R.string.tv_ready else R.string.tv_needed)
            status.setTextColor(getColor(if (ready) R.color.tv_primary else R.color.tv_foreground))
            status.background = GradientDrawable().apply {
                cornerRadius = dp(8f)
                setColor(getColor(if (ready) R.color.tv_primary_tint else R.color.tv_muted_surface))
            }
            button.visibility = if (ready) View.GONE else View.VISIBLE
        }
        row(binding.accessibilityStatus, binding.accessibility, MobilerunAccessibilityService.getInstance() != null)
        row(binding.captureStatus, binding.capture, WebRtcManager.getExistingInstance()?.isCaptureActive() == true)
        row(binding.notificationStatus, binding.notifications,
            NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName))
        if (binding.accessibility.visibility == View.GONE) binding.accessibilityHint.visibility = View.GONE
        if (binding.notifications.visibility == View.GONE) binding.notificationHint.visibility = View.GONE
        updateFocus()
    }

    private fun updateFocus(reset: Boolean = false) {
        val needed = listOf(binding.accessibility, binding.capture, binding.notifications).filter { it.visibility == View.VISIBLE }
        val primary = if (signedIn) binding.updates else binding.newCode
        val first = needed.firstOrNull() ?: primary
        val mainButtons = if (signedIn) listOf(binding.updates, binding.signOut) else listOf(binding.newCode)
        mainButtons.forEach { it.nextFocusRightId = first.id; it.nextFocusLeftId = needed.lastOrNull()?.id ?: primary.id }
        needed.forEachIndexed { i, button ->
            button.nextFocusLeftId = primary.id
            button.nextFocusRightId = primary.id
            button.nextFocusUpId = needed.getOrNull(i - 1)?.id ?: primary.id
            button.nextFocusDownId = needed.getOrNull(i + 1)?.id ?: primary.id
        }
        primary.nextFocusDownId = if (signedIn) binding.signOut.id else first.id
        if (reset || currentFocus?.isShown != true) first.requestFocus()
    }

    private fun renderConnection() {
        val text = if (!signedIn) R.string.tv_signed_out else when (ConnectionStateManager.getState()) {
            ConnectionState.CONNECTED -> R.string.tv_connected
            ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> R.string.tv_connecting
            ConnectionState.DISCONNECTED -> R.string.tv_disconnected
            ConnectionState.UNAUTHORIZED -> R.string.tv_unauthorized
            ConnectionState.LIMIT_EXCEEDED -> R.string.tv_limit
            ConnectionState.BAD_REQUEST, ConnectionState.ERROR -> R.string.tv_connection_error
        }
        binding.connectionStatus.text = getString(R.string.tv_status_pill, getString(text))
        binding.homeStatus.setText(text)
        binding.homeStatus.background = GradientDrawable().apply {
            cornerRadius = dp(20f)
            setColor(getColor(R.color.tv_primary_tint))
        }
    }

    private fun openSettings(action: String, hint: TextView, fallback: String) {
        try {
            startActivity(Intent(action))
            hint.visibility = View.GONE
        } catch (_: ActivityNotFoundException) { showSetupHelp(hint, fallback) }
          catch (_: SecurityException) { showSetupHelp(hint, fallback) }
    }

    private fun showSetupHelp(hint: TextView, fallback: String) {
        hint.setText(R.string.tv_adb_hint)
        hint.visibility = View.VISIBLE
        renderSetup()
        val dialog = AlertDialog.Builder(this).setTitle(R.string.tv_setup_help).setMessage(fallback)
            .setPositiveButton(android.R.string.ok, null).show()
        dialog.findViewById<TextView>(android.R.id.message)?.textSize = 20f
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private fun checkUpdates() {
        UpdateChecker.checkForUpdate(this) { result ->
            if (isFinishing || isDestroyed) return@checkForUpdate
            when (result) {
                is UpdateCheckResult.Available -> AlertDialog.Builder(this)
                    .setTitle(R.string.tv_install).setMessage(result.info.latestVersion)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.tv_install) { _, _ ->
                        if (!packageManager.canRequestPackageInstalls()) UpdateChecker.openInstallPermissionSettings(this)
                        else UpdateChecker.downloadAndInstall(this, result.info,
                            { binding.message.visibility = View.VISIBLE; binding.message.text = getString(R.string.tv_update_progress, it) },
                            { binding.message.visibility = View.VISIBLE; binding.message.text = it })
                    }.show()
                is UpdateCheckResult.Failed -> { binding.message.visibility = View.VISIBLE; binding.message.text = result.message }
                UpdateCheckResult.UpToDate -> onMessage(R.string.tv_no_updates)
            }
        }
    }
}
