package com.mobilerun.portal.ui

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import com.mobilerun.portal.api.ApiHandler
import com.mobilerun.portal.auth.DeviceCodeAuthClient
import com.mobilerun.portal.config.ConfigManager
import com.mobilerun.portal.databinding.ActivityTvMainBinding
import com.mobilerun.portal.service.MobilerunAccessibilityService
import com.mobilerun.portal.service.TvAccessibilityEvents
import com.mobilerun.portal.service.MobilerunNotificationListener
import com.mobilerun.portal.state.ConnectionState
import com.mobilerun.portal.state.ConnectionStateManager
import com.mobilerun.portal.streaming.WebRtcManager
import com.mobilerun.portal.update.InstallResult
import com.mobilerun.portal.update.UpdateInfo
import com.mobilerun.portal.update.UpdateInstallReceiver
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
    private var updateBusy = false
    private var downloadingUpdate = false
    private val clearUpdateMessage = Runnable { binding.message.visibility = View.GONE }
    private val updateInstallReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == UpdateInstallReceiver.ACTION_SIGNATURE_CONFLICT ||
                (intent?.action == ApiHandler.ACTION_INSTALL_RESULT &&
                    intent.getBooleanExtra(UpdateInstallReceiver.EXTRA_IS_PORTAL_UPDATE, false))
            ) {
                if (UpdateChecker.pendingInstallResult != null) consumeUpdateInstallResult()
                else finishUpdateInstall(intent.getBooleanExtra(ApiHandler.EXTRA_INSTALL_SUCCESS, false))
            }
        }
    }
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
        ContextCompat.registerReceiver(this, updateInstallReceiver, IntentFilter().apply {
            addAction(ApiHandler.ACTION_INSTALL_RESULT)
            addAction(UpdateInstallReceiver.ACTION_SIGNATURE_CONFLICT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        binding.footer.text = getString(R.string.tv_footer, BuildConfig.VERSION_NAME)
        binding.deviceName.text = ConfigManager.getInstance(this).deviceName
        binding.homeDeviceName.text = binding.deviceName.text
        TvAccessibilityEvents.connected.observe(this) { renderSetup() }
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
            configureFocus(button)
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
        val deviceName = ConfigManager.getInstance(this).deviceName
        binding.deviceName.text = deviceName
        binding.homeDeviceName.text = deviceName
        if (UpdateChecker.isUpdateInstallInProgress() && !downloadingUpdate) {
            downloadingUpdate = true
            setUpdateLoading(true, getString(R.string.tv_update_downloading))
        } else if (!UpdateChecker.isUpdateInstallInProgress() && downloadingUpdate) {
            downloadingUpdate = false
            setUpdateLoading(false)
        }
        consumeUpdateInstallResult()
        renderSetup()
    }

    override fun onStop() {
        controller.setForeground(false)
        main.removeCallbacks(tick)
        super.onStop() // Approval polling intentionally continues while Settings is in front.
    }

    override fun onDestroy() {
        controller.close()
        unregisterReceiver(updateInstallReceiver)
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
        main.removeCallbacks(clearUpdateMessage)
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
        row(binding.accessibilityStatus, binding.accessibility, TvAccessibilityEvents.connected.value == true)
        row(binding.captureStatus, binding.capture, WebRtcManager.getExistingInstance()?.isCaptureActive() == true)
        row(binding.notificationStatus, binding.notifications,
            NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName))
        if (binding.accessibility.visibility == View.GONE) binding.accessibilityHint.visibility = View.GONE
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
        val dotColor = when (text) {
            R.string.tv_connected -> R.color.tv_connected_dot
            R.string.tv_connecting -> R.color.tv_connecting_dot
            else -> R.color.tv_muted_foreground
        }
        binding.connectionStatus.text = SpannableString(getString(R.string.tv_status_pill, getString(text))).apply {
            setSpan(ForegroundColorSpan(getColor(dotColor)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
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
            .setPositiveButton(android.R.string.ok, null).showTvDialog()
        dialog.findViewById<TextView>(android.R.id.message)?.textSize = 20f
    }

    private fun configureFocus(button: Button) {
        // Keep the platform button animator from adding a second elevation on focus/press.
        button.stateListAnimator = null
        button.elevation = 0f
        fun animateFocus(focused: Boolean) {
            button.animate().scaleX(if (focused) 1.04f else 1f).scaleY(if (focused) 1.04f else 1f)
                .translationZ(if (focused) dp(6f) else 0f).setDuration(120).start()
        }
        button.setOnFocusChangeListener { _, focused -> animateFocus(focused) }
        animateFocus(button.hasFocus())
    }

    private fun AlertDialog.Builder.showTvDialog(): AlertDialog = show().also { dialog ->
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)
            .mapNotNull(dialog::getButton).forEach { button ->
                button.backgroundTintList = null
                button.setBackgroundResource(R.drawable.tv_button)
                button.setTextColor(androidx.core.content.ContextCompat.getColorStateList(
                    this@TvMainActivity, R.color.tv_button_text))
                configureFocus(button)
            }
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private fun setUpdateLoading(loading: Boolean, label: String = getString(R.string.tv_update_checking)) {
        val wasFocused = binding.updates.hasFocus()
        updateBusy = loading
        binding.updates.isEnabled = !loading
        binding.updates.text = if (loading) label else getString(R.string.tv_updates)
        binding.updates.setPaddingRelative(dp(if (loading) 50f else 18f).toInt(),
            binding.updates.paddingTop, dp(18f).toInt(), binding.updates.paddingBottom)
        binding.updateSpinner.visibility = if (loading) View.VISIBLE else View.GONE
        updateFocus(reset = loading && wasFocused)
    }

    private fun showUpdateMessage(text: String, temporary: Boolean = true) {
        main.removeCallbacks(clearUpdateMessage)
        binding.message.text = text
        binding.message.visibility = View.VISIBLE
        if (temporary) main.postDelayed(clearUpdateMessage, 10_000)
    }

    private fun consumeUpdateInstallResult() {
        val result = UpdateChecker.pendingInstallResult ?: return
        UpdateChecker.pendingInstallResult = null
        finishUpdateInstall(result is InstallResult.Done && result.success)
    }

    private fun finishUpdateInstall(success: Boolean) {
        downloadingUpdate = false
        setUpdateLoading(false)
        showUpdateMessage(getString(if (success) R.string.tv_update_installed else R.string.tv_update_install_error))
    }

    private fun downloadUpdate(info: UpdateInfo) {
        if (!packageManager.canRequestPackageInstalls()) {
            UpdateChecker.openInstallPermissionSettings(this)
            return
        }
        downloadingUpdate = true
        setUpdateLoading(true, getString(R.string.tv_update_downloading))
        showUpdateMessage(getString(R.string.tv_update_downloading), temporary = false)
        UpdateChecker.downloadAndInstall(this, info,
            { progress ->
                if (!isFinishing && !isDestroyed) {
                    showUpdateMessage(getString(R.string.tv_update_progress, progress), temporary = false)
                }
            },
            {
                if (!isFinishing && !isDestroyed) {
                    downloadingUpdate = false
                    setUpdateLoading(false)
                    showUpdateMessage(getString(R.string.tv_update_install_error))
                }
            })
    }

    private fun checkUpdates() {
        if (updateBusy) return
        onMessage(null)
        setUpdateLoading(true)
        UpdateChecker.checkForUpdate(this) { result ->
            if (isFinishing || isDestroyed) return@checkForUpdate
            setUpdateLoading(false)
            when (result) {
                is UpdateCheckResult.Available -> {
                    showUpdateMessage(getString(R.string.tv_update_available, result.info.latestVersion))
                    AlertDialog.Builder(this)
                        .setTitle(R.string.tv_install).setMessage(result.info.latestVersion)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.tv_install) { _, _ -> downloadUpdate(result.info) }
                        .showTvDialog()
                }
                is UpdateCheckResult.Failed -> showUpdateMessage(getString(
                    if (result.message == "Check failed: HTTP 404") R.string.tv_update_unpublished
                    else R.string.tv_update_check_error))
                UpdateCheckResult.UpToDate -> showUpdateMessage(getString(R.string.tv_no_updates, BuildConfig.VERSION_NAME))
            }
        }
    }
}
