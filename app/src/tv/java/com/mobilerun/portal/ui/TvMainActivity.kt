package com.mobilerun.portal.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.mobilerun.portal.BuildConfig
import com.mobilerun.portal.R
import com.mobilerun.portal.auth.DeviceCodeAuthClient
import com.mobilerun.portal.config.ConfigManager
import com.mobilerun.portal.databinding.ActivityTvMainBinding
import com.mobilerun.portal.service.MobilerunAccessibilityService
import com.mobilerun.portal.service.MobilerunNotificationListener
import com.mobilerun.portal.service.TvAccessibilityHealth
import com.mobilerun.portal.service.TvAccessibilityHealthState
import com.mobilerun.portal.state.ConnectionState
import com.mobilerun.portal.state.ConnectionStateManager
import com.mobilerun.portal.streaming.WebRtcManager

/** Maps controller and permission/connection callbacks to TV views. */
class TvMainActivity : AppCompatActivity(), TvSignInController.Listener {
    private companion object {
        const val QR_SIZE = 640
        const val FOCUS_SCALE = 1.04f
        const val FOCUS_DURATION_MS = 120L
        const val COUNTDOWN_TICK_MS = 1_000L
        const val EXPIRY_PROGRESS_MAX = 1_000
        const val HEALTH_PROBE_INTERVAL_MS = 10_000L
    }
    private lateinit var binding: ActivityTvMainBinding
    private lateinit var controller: TvSignInController
    private val main = Handler(Looper.getMainLooper())
    private var deadline = 0L
    private var lifetimeMs = 1L
    private var signedIn = false
    private var displayedQr: String? = null
    private lateinit var updates: TvUpdateFlow
    private var started = false
    private val healthTick = object : Runnable {
        override fun run() {
            if (!started) return
            MobilerunAccessibilityService.getInstance()?.probeTvWindowHealth(hasWindowFocus())
            main.postDelayed(this, HEALTH_PROBE_INTERVAL_MS)
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!started || signedIn || deadline <= 0) return
            val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            val seconds = (remaining + COUNTDOWN_TICK_MS - 1) / COUNTDOWN_TICK_MS
            binding.countdown.text = getString(R.string.tv_countdown, seconds / 60, seconds % 60)
            binding.expiryProgress.progress = ((remaining * EXPIRY_PROGRESS_MAX) / lifetimeMs).toInt().coerceIn(0, EXPIRY_PROGRESS_MAX)
            if (remaining == 0L) {
                controller.refreshCode()
                return
            }
            main.postDelayed(this, COUNTDOWN_TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        controller = TvSignInController(this, this)
        updates = TvUpdateFlow(this, binding, { reset -> updateFocus(reset) }, { it.showTvDialog() })
        binding.footer.text = getString(R.string.tv_footer, BuildConfig.VERSION_NAME)
        binding.deviceName.text = ConfigManager.getInstance(this).deviceName
        binding.homeDeviceName.text = binding.deviceName.text
        MobilerunAccessibilityService.isConnected.observe(this) {
            renderSetup()
            if (started) MobilerunAccessibilityService.getInstance()?.probeTvWindowHealth(hasWindowFocus())
        }
        TvAccessibilityHealth.state.observe(this) { renderSetup() }
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
        binding.updates.setOnClickListener { updates.checkUpdates() }
        listOf(binding.accessibility, binding.capture, binding.notifications, binding.newCode,
            binding.updates, binding.signOut).forEach { button ->
            configureFocus(button)
        }
        ConnectionStateManager.connectionState.observe(this) {
            controller.start()
            renderSetup()
            renderConnection()
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        controller.setForeground(true)
        controller.start()
        renderSetup()
    }

    override fun onResume() {
        super.onResume()
        val deviceName = ConfigManager.getInstance(this).deviceName
        binding.deviceName.text = deviceName
        binding.homeDeviceName.text = deviceName
        updates.onResume()
        renderSetup()
        main.removeCallbacks(healthTick)
        main.post(healthTick)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (started && hasFocus) MobilerunAccessibilityService.getInstance()?.probeTvWindowHealth(focusedApp = true)
    }

    override fun onStop() {
        started = false
        controller.setForeground(false)
        main.removeCallbacks(tick)
        main.removeCallbacks(healthTick)
        super.onStop() // Approval polling intentionally continues while Settings is in front.
    }

    override fun onDestroy() {
        controller.close()
        updates.close()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onSignedIn(signedIn: Boolean) {
        val changed = this.signedIn != signedIn
        this.signedIn = signedIn
        binding.home.visibility = if (signedIn) View.VISIBLE else View.GONE
        binding.signIn.visibility = if (signedIn) View.GONE else View.VISIBLE
        binding.headline.setText(if (signedIn) R.string.tv_headline_home else R.string.tv_headline)
        listOf(binding.stepRow1, binding.stepRow2, binding.stepRow3).forEach {
            it.visibility = if (signedIn) View.GONE else View.VISIBLE
        }
        listOf(binding.homeInstructions, binding.homeDeviceName).forEach {
            it.visibility = if (signedIn) View.VISIBLE else View.GONE
        }
        if (signedIn) {
            deadline = 0
            main.removeCallbacks(tick)
        }
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
        updates.cancelMessageTimer()
        binding.message.visibility = if (message == null) View.GONE else View.VISIBLE
        if (message != null) binding.message.setText(message)
    }

    override fun onCode(userCode: String, verificationUri: String, qrUri: String?, deadline: Long, lifetimeMs: Long) {
        this.deadline = deadline
        this.lifetimeMs = lifetimeMs.coerceAtLeast(1)
        val compact = userCode.filter(Char::isLetterOrDigit)
        binding.userCode.text = if (compact.length == 8) compact.chunked(4).joinToString("-") else userCode
        binding.countdown.visibility = if (deadline > 0) View.VISIBLE else View.INVISIBLE
        main.removeCallbacks(tick)
        if (deadline > 0 && started && !signedIn) main.post(tick)
        if (qrUri == null && userCode.isNotEmpty()) {
            updates.cancelMessageTimer()
            binding.message.text = getString(R.string.tv_manual_sign_in, verificationUri)
            binding.message.visibility = View.VISIBLE
        }
        if (qrUri == null || qrUri != displayedQr) {
            displayedQr = qrUri
            binding.qr.setImageDrawable(null)
            // The controller validates too; keep the rendering boundary explicit.
            if (qrUri != null && DeviceCodeAuthClient.isSafeVerificationUri(qrUri)) {
                val matrix = MultiFormatWriter().encode(qrUri, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE)
                val ink = getColor(R.color.tv_foreground)
                val background = getColor(R.color.tv_card)
                val pixels = IntArray(QR_SIZE * QR_SIZE) { i -> if (matrix[i % QR_SIZE, i / QR_SIZE]) ink else background }
                binding.qr.setImageBitmap(Bitmap.createBitmap(pixels, QR_SIZE, QR_SIZE, Bitmap.Config.ARGB_8888))
            }
        }
    }

    private fun renderSetup() {
        fun row(status: TextView, button: Button, ready: Boolean) {
            status.setText(if (ready) R.string.tv_ready else R.string.tv_needed)
            status.setTextColor(getColor(if (ready) R.color.tv_primary else R.color.tv_foreground))
            status.setBackgroundResource(if (ready) R.drawable.tv_chip_ready else R.drawable.tv_chip_needed)
            button.visibility = if (ready) View.GONE else View.VISIBLE
        }
        val bound = MobilerunAccessibilityService.isConnected.value == true
        val health = if (bound) {
            TvAccessibilityHealth.state.value?.takeUnless { it == TvAccessibilityHealthState.NEEDED }
                ?: TvAccessibilityHealthState.RECONNECTING
        } else TvAccessibilityHealthState.NEEDED
        row(binding.accessibilityStatus, binding.accessibility, health == TvAccessibilityHealthState.READY)
        when (health) {
            TvAccessibilityHealthState.RECONNECTING -> {
                binding.accessibilityStatus.setText(R.string.tv_a11y_reconnecting)
                binding.accessibility.visibility = View.GONE
            }
            TvAccessibilityHealthState.NOT_RESPONDING -> {
                binding.accessibilityStatus.setText(R.string.tv_a11y_not_responding)
                binding.accessibilityStatus.setTextColor(getColor(R.color.tv_destructive))
                binding.accessibilityStatus.setBackgroundResource(R.drawable.tv_chip_error)
            }
            else -> Unit
        }
        binding.accessibility.setText(if (health == TvAccessibilityHealthState.NOT_RESPONDING) R.string.tv_fix else R.string.tv_open_settings)
        row(binding.captureStatus, binding.capture, WebRtcManager.getExistingInstance()?.isCaptureActive() == true)
        row(binding.notificationStatus, binding.notifications,
            NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName))
        if (health == TvAccessibilityHealthState.READY) binding.accessibilityHint.visibility = View.GONE
        if (binding.notifications.visibility == View.GONE) binding.notificationHint.visibility = View.GONE
        updateFocus()
    }

    private fun updateFocus(reset: Boolean = false) {
        val needed = listOf(binding.accessibility, binding.capture, binding.notifications).filter { it.visibility == View.VISIBLE }
        val primary = if (signedIn) {
            if (binding.updates.isEnabled) binding.updates else binding.signOut
        } else binding.newCode
        val first = needed.firstOrNull() ?: primary
        val mainButtons = if (signedIn) listOf(binding.updates, binding.signOut) else listOf(binding.newCode)
        mainButtons.filter { it.isEnabled }.forEach { it.nextFocusRightId = first.id; it.nextFocusLeftId = needed.lastOrNull()?.id ?: primary.id }
        needed.forEachIndexed { i, button ->
            button.nextFocusLeftId = primary.id
            button.nextFocusRightId = primary.id
            button.nextFocusUpId = needed.getOrNull(i - 1)?.id ?: primary.id
            button.nextFocusDownId = needed.getOrNull(i + 1)?.id ?: primary.id
        }
        val enabledMain = mainButtons.filter { it.isEnabled }
        enabledMain.forEachIndexed { index, button ->
            button.nextFocusUpId = enabledMain.getOrNull(index - 1)?.id ?: needed.lastOrNull()?.id ?: button.id
            button.nextFocusDownId = enabledMain.getOrNull(index + 1)?.id ?: first.id
        }
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
        val dotColor = when (text) {
            R.string.tv_connected -> R.color.tv_connected_dot
            R.string.tv_connecting -> R.color.tv_connecting_dot
            else -> R.color.tv_muted_foreground
        }
        binding.connectionStatus.text = SpannableString(getString(R.string.tv_status_pill, getString(text))).apply {
            setSpan(ForegroundColorSpan(getColor(dotColor)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        binding.homeStatus.setText(text)
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
            .setPositiveButton(android.R.string.ok, null).showTvDialog()
        dialog.findViewById<TextView>(android.R.id.message)?.textSize = 20f
    }

    private fun configureFocus(button: Button) {
        // Keep the platform button animator from adding a second elevation on focus/press.
        button.stateListAnimator = null
        button.elevation = 0f
        fun animateFocus(focused: Boolean) {
            button.animate().scaleX(if (focused) FOCUS_SCALE else 1f).scaleY(if (focused) FOCUS_SCALE else 1f)
                .translationZ(if (focused) resources.getDimension(R.dimen.tv_focus_elevation) else 0f).setDuration(FOCUS_DURATION_MS).start()
        }
        button.setOnFocusChangeListener { _, focused -> animateFocus(focused) }
        animateFocus(button.hasFocus())
    }

    private fun AlertDialog.Builder.showTvDialog(): AlertDialog = show().also { dialog ->
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)
            .mapNotNull(dialog::getButton).forEach { button ->
                button.backgroundTintList = null
                button.setBackgroundResource(R.drawable.tv_button)
                button.setTextColor(ContextCompat.getColorStateList(
                    this@TvMainActivity, R.color.tv_button_text))
                configureFocus(button)
            }
    }
}
