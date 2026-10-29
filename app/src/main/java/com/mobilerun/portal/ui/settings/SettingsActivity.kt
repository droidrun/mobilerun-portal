package com.mobilerun.portal.ui.settings

import android.Manifest
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.mobilerun.portal.R
import com.mobilerun.portal.api.ApiHandler
import com.mobilerun.portal.config.ConfigManager
import com.mobilerun.portal.databinding.ActivitySettingsBinding
import com.mobilerun.portal.events.model.EventType
import com.mobilerun.portal.keepalive.KeepAliveController
import com.mobilerun.portal.service.MobilerunNotificationListener
import com.mobilerun.portal.service.ReverseConnectionService
import com.mobilerun.portal.service.performExplicitReverseConnectionDisconnect
import com.mobilerun.portal.state.ConnectionState
import com.mobilerun.portal.state.ConnectionStateManager
import com.mobilerun.portal.taskprompt.PortalBalanceRepository
import com.mobilerun.portal.taskprompt.PortalCloudClient
import com.mobilerun.portal.triggers.TriggerRepository
import com.mobilerun.portal.ui.addWhitespaceStrippingWatcher
import com.mobilerun.portal.ui.triggers.TriggerRulesActivity
import com.mobilerun.portal.update.InstallResult
import com.mobilerun.portal.update.UpdateCheckResult
import com.mobilerun.portal.update.UpdateChecker
import com.mobilerun.portal.update.UpdateInfo
import com.mobilerun.portal.update.UpdateInstallReceiver
import java.text.NumberFormat

class SettingsActivity : AppCompatActivity(), ConfigManager.ConfigChangeListener {

    private lateinit var configManager: ConfigManager
    private lateinit var binding: ActivitySettingsBinding
    private val portalCloudClient = PortalCloudClient()
    private var suppressSocketServerSwitchCallback = false
    private var suppressWebSocketSwitchCallback = false
    private var isInstallReceiverRegistered = false
    private var isSignatureConflictReceiverRegistered = false

    private val installResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ApiHandler.ACTION_INSTALL_RESULT) return
            if (!intent.getBooleanExtra(UpdateInstallReceiver.EXTRA_IS_PORTAL_UPDATE, false)) return
            val success = intent.getBooleanExtra(ApiHandler.EXTRA_INSTALL_SUCCESS, false)
            val message = intent.getStringExtra(ApiHandler.EXTRA_INSTALL_MESSAGE).orEmpty()
            runOnUiThread {
                resetUpdateButton()
                android.widget.Toast.makeText(
                    this@SettingsActivity,
                    message.ifBlank {
                        if (success) "Update installed successfully" else "Update failed"
                    },
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private val signatureConflictReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != UpdateInstallReceiver.ACTION_SIGNATURE_CONFLICT) return
            val apkSavedToDownloads =
                intent.getBooleanExtra(UpdateInstallReceiver.EXTRA_APK_SAVED_TO_DOWNLOADS, false)
            val apkUrl = intent.getStringExtra(UpdateInstallReceiver.EXTRA_APK_URL)
            runOnUiThread {
                resetUpdateButton()
                showSignatureConflictDialog(apkSavedToDownloads, apkUrl)
            }
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        binding.switchPostNotifications.isChecked = isGranted
        if (isGranted) {
            android.widget.Toast.makeText(
                this,
                "Notification permission granted",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private val requestSmsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        updatePermissionSwitches()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configManager = ConfigManager.getInstance(this)

        setupToolbar()
        setupCreditsSection()
        setupDevMode()
        setupServerSettings()
        setupWebSocketSettings()
        setupReverseConnectionSettings()
        setupStreamSessionLimit()
        setupPermissions()
        setupAutomation()
        setupEventFilters()
        setupUpdateSection()
        setupResetButton()
    }

    private fun setupToolbar() {
        binding.topAppBar.setNavigationOnClickListener {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionSwitches()
        syncServerSettingsFromConfig()
        refreshCreditsBalance()
        refreshCurrentVersion()
        consumePendingUpdateInstallResult()
        syncUpdateButtonForActiveInstall()
    }

    private fun setupCreditsSection() {
        binding.btnRefreshCreditsSettings.setOnClickListener {
            refreshCreditsBalance(force = true)
        }
        renderCreditsUi()
    }

    override fun onStart() {
        super.onStart()
        configManager.addListener(this)
        syncServerSettingsFromConfig()
        registerUpdateReceivers()
    }

    override fun onStop() {
        super.onStop()
        configManager.removeListener(this)
        commitStreamSessionLimitInput(showError = false)
        persistReverseConnectionInputs()
        unregisterUpdateReceivers()
    }

    private fun setupDevMode() {
        binding.switchDevMode.isChecked = configManager.devModeEnabled
        updateDevModeVisibility(configManager.devModeEnabled)

        binding.switchDevMode.setOnCheckedChangeListener { _, isChecked ->
            configManager.devModeEnabled = isChecked
            updateDevModeVisibility(isChecked)
        }
    }

    private fun updateDevModeVisibility(enabled: Boolean) {
        binding.devModeSection.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun setupServerSettings() {
        // HTTP Server
        binding.switchSocketServerEnabled.isChecked = configManager.socketServerEnabled
        binding.switchSocketServerEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSocketServerSwitchCallback) return@setOnCheckedChangeListener
            configManager.setSocketServerEnabledWithNotification(isChecked)
        }

        binding.inputSocketServerPort.setText(configManager.socketServerPort.toString())
        binding.inputSocketServerPort.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val port = v.text.toString().toIntOrNull()
                if (port != null && port in MIN_PORT..MAX_PORT) {
                    configManager.setSocketServerPortWithNotification(port)
                    binding.inputSocketServerPort.clearFocus()
                } else {
                    binding.inputSocketServerPort.error = "Invalid Port"
                }
                true
            } else {
                false
            }
        }
    }

    private fun setupWebSocketSettings() {
        binding.switchWsEnabled.isChecked = configManager.websocketEnabled
        binding.switchWsEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (suppressWebSocketSwitchCallback) return@setOnCheckedChangeListener
            configManager.setWebSocketEnabledWithNotification(isChecked)
        }

        binding.inputWsPort.setText(configManager.websocketPort.toString())
        binding.inputWsPort.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val port = v.text.toString().toIntOrNull()
                if (port != null && port in MIN_PORT..MAX_PORT) {
                    configManager.setWebSocketPortWithNotification(port)
                    binding.inputWsPort.clearFocus()
                } else {
                    binding.inputWsPort.error = "Invalid Port"
                }
                true
            } else {
                false
            }
        }
    }

    private fun setupStreamSessionLimit() {
        binding.textStreamSessionLimitDescription.text = getString(
            com.mobilerun.portal.R.string.stream_session_limit_description,
            ConfigManager.MIN_STREAM_SESSION_LIMIT,
            ConfigManager.MAX_STREAM_SESSION_LIMIT,
        )
        binding.inputStreamSessionLimit.setText(configManager.streamSessionLimit.toString())
        binding.inputStreamSessionLimit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                if (commitStreamSessionLimitInput(showError = true)) {
                    binding.inputStreamSessionLimit.clearFocus()
                }
                true
            } else {
                false
            }
        }
        binding.inputStreamSessionLimit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) commitStreamSessionLimitInput(showError = false)
        }
    }

    // Invalid input shows an error on Done and is reverted when the field is left.
    private fun commitStreamSessionLimitInput(showError: Boolean): Boolean {
        val min = ConfigManager.MIN_STREAM_SESSION_LIMIT
        val max = ConfigManager.MAX_STREAM_SESSION_LIMIT
        val limit = binding.inputStreamSessionLimit.text?.toString()?.toIntOrNull()
        if (limit != null && limit in min..max) {
            if (limit != configManager.streamSessionLimit) {
                configManager.setStreamSessionLimitWithNotification(limit)
            }
            return true
        }
        if (showError) {
            binding.inputStreamSessionLimit.error = getString(com.mobilerun.portal.R.string.stream_session_limit_invalid, min, max)
        } else {
            updateStreamSessionLimitUi(configManager.streamSessionLimit)
        }
        return false
    }

    private fun setupReverseConnectionSettings() {
        binding.switchReverseEnabled.isChecked = configManager.reverseConnectionEnabled
        binding.inputReverseUrl.setText(configManager.reverseConnectionUrl)
        binding.inputReverseToken.setText(configManager.reverseConnectionToken)
        binding.inputReverseToken.addWhitespaceStrippingWatcher()

        binding.switchReverseEnabled.setOnCheckedChangeListener { _, isChecked ->
            configManager.reverseConnectionEnabled = isChecked

            val intent = Intent(
                this,
                ReverseConnectionService::class.java,
            )
            if (isChecked) {
                val url = binding.inputReverseUrl.text.toString().ifBlank {
                    configManager.reverseConnectionUrlOrDefault
                }
                val token = sanitizeToken(binding.inputReverseToken.text?.toString())
                binding.inputReverseToken.error = null
                configManager.reverseConnectionUrl = url
                configManager.reverseConnectionToken = token
                intent.action = ReverseConnectionService.ACTION_RECONNECT
                startForegroundService(intent)
            } else {
                intent.action = ReverseConnectionService.ACTION_DISCONNECT
                performExplicitReverseConnectionDisconnect(
                    markExplicitlyDisconnected =
                        configManager::markReverseJoinExplicitlyDisconnected,
                    publishDisconnected = ConnectionStateManager::setState,
                    dispatchDisconnect = { startService(intent) },
                )
            }
            refreshCreditsBalance(force = true)
        }

        binding.inputReverseUrl.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                val url = v.text.toString().trim()
                val shouldReconnect =
                    ReverseConnectionSettingsPolicy.shouldReconnectAfterInputPersistence(
                        enabled = configManager.reverseConnectionEnabled,
                        currentEffectiveUrl = configManager.reverseConnectionUrlOrDefault,
                        currentToken = configManager.reverseConnectionToken,
                        candidateUrl = url,
                        candidateToken = configManager.reverseConnectionToken,
                        defaultUrl = configManager.defaultReverseConnectionUrl,
                    )
                configManager.reverseConnectionUrl = url
                if (actionId == EditorInfo.IME_ACTION_DONE) binding.inputReverseUrl.clearFocus()
                if (shouldReconnect) restartServiceIfEnabled()
                refreshCreditsBalance(force = true)
                true
            } else {
                false
            }
        }

        binding.inputReverseToken.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val token = sanitizeToken(v.text?.toString())
                binding.inputReverseToken.error = null
                val shouldReconnect =
                    ReverseConnectionSettingsPolicy.shouldReconnectAfterInputPersistence(
                        enabled = configManager.reverseConnectionEnabled,
                        currentEffectiveUrl = configManager.reverseConnectionUrlOrDefault,
                        currentToken = configManager.reverseConnectionToken,
                        candidateUrl = configManager.reverseConnectionUrlOrDefault,
                        candidateToken = token,
                        defaultUrl = configManager.defaultReverseConnectionUrl,
                    )
                configManager.reverseConnectionToken = token
                binding.inputReverseToken.clearFocus()
                if (shouldReconnect) restartServiceIfEnabled()
                refreshCreditsBalance(force = true)
                true
            } else {
                false
            }
        }

        binding.switchScreenShareAutoAccept.isChecked = configManager.screenShareAutoAcceptEnabled
        binding.switchScreenShareAutoAccept.setOnCheckedChangeListener { _, isChecked ->
            configManager.screenShareAutoAcceptEnabled = isChecked
        }

        binding.switchInstallAutoAccept.isChecked = configManager.installAutoAcceptEnabled
        binding.switchInstallAutoAccept.setOnCheckedChangeListener { _, isChecked ->
            configManager.installAutoAcceptEnabled = isChecked
        }

        binding.switchKeepScreenAwake.isChecked = configManager.keepScreenAwakeEnabled
        binding.switchKeepScreenAwake.setOnCheckedChangeListener { _, isChecked ->
            KeepAliveController.setEnabled(this, isChecked)
        }
    }

    private fun setupPermissions() {
        updatePermissionSwitches()

        binding.switchNotificationAccess.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                startActivity(intent)
                android.widget.Toast.makeText(
                    this,
                    "Please grant Notification Access to Mobilerun Portal",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    this,
                    "Error opening settings",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            // Revert visual state until onResume confirms change
            binding.switchNotificationAccess.isChecked = !binding.switchNotificationAccess.isChecked
        }

        binding.switchPostNotifications.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (binding.switchPostNotifications.isChecked) {
                    // User wants to enable
                    requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    // User wants to disable - must go to settings
                    try {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        // Fallback
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", packageName, null)
                        }
                        startActivity(intent)
                    }
                    // Revert visual state
                    binding.switchPostNotifications.isChecked = true
                }
            }
        }

        binding.switchInstallUnknownApps.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                // Fallback to security settings
                try {
                    val fallbackIntent = Intent(Settings.ACTION_SECURITY_SETTINGS)
                    startActivity(fallbackIntent)
                } catch (_: Exception) {
                    // Last resort: open app details
                    val detailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(detailsIntent)
                }
            }
            // Revert visual state until onResume confirms change
            binding.switchInstallUnknownApps.isChecked = !binding.switchInstallUnknownApps.isChecked
        }

        binding.switchUnrestrictedBattery.setOnClickListener {
            // An exemption can only be revoked from the system list.
            val intent = if (isIgnoringBatteryOptimizations()) {
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            } else {
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    )
                }
            }
            // Revert visual state until onResume confirms change
            binding.switchUnrestrictedBattery.isChecked = !binding.switchUnrestrictedBattery.isChecked
        }

        binding.switchSmsGateway.setOnCheckedChangeListener { _, isChecked ->
            configManager.smsGatewayEnabled = isChecked
        }
        binding.buttonRequestSendSms.setOnClickListener {
            requestSmsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
        }
        binding.buttonRequestReadSms.setOnClickListener {
            requestSmsPermissionLauncher.launch(Manifest.permission.READ_SMS)
        }
        binding.buttonRequestReceiveSms.setOnClickListener {
            requestSmsPermissionLauncher.launch(Manifest.permission.RECEIVE_SMS)
        }
        binding.buttonRequestPhoneState.setOnClickListener {
            requestSmsPermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
        }
    }

    private fun setupEventFilters() {
        setupEventToggle(binding.switchEventNotification, EventType.NOTIFICATION)
    }

    private fun setupAutomation() {
        binding.openTriggersButton.setOnClickListener {
            startActivity(TriggerRulesActivity.createIntent(this))
        }
    }

    private fun setupUpdateSection() {
        refreshCurrentVersion()
        binding.btnCheckUpdates.setOnClickListener {
            runLiveUpdateCheck()
        }
    }

    private fun refreshCurrentVersion() {
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "N/A"
        } catch (e: Exception) {
            "N/A"
        }
        binding.tvCurrentVersion.text = getString(R.string.update_current_version, versionName)
    }

    private fun runLiveUpdateCheck() {
        if (UpdateChecker.isUpdateInstallInProgress()) {
            showUpdateInProgressState()
            return
        }
        binding.btnCheckUpdates.isEnabled = false
        binding.btnCheckUpdates.text = getString(R.string.update_checking)
        UpdateChecker.checkForUpdate(this) { result ->
            if (isDestroyed || isFinishing) return@checkForUpdate
            resetUpdateButton()
            when (result) {
                is UpdateCheckResult.Available -> showUpdateAvailableDialog(result.info)
                UpdateCheckResult.UpToDate -> {
                    val currentVersion = try {
                        packageManager.getPackageInfo(packageName, 0).versionName ?: "N/A"
                    } catch (e: Exception) {
                        "N/A"
                    }
                    android.widget.Toast.makeText(
                        this,
                        getString(R.string.update_up_to_date, currentVersion),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
                is UpdateCheckResult.Failed -> android.widget.Toast.makeText(
                    this,
                    getString(R.string.update_check_failed, result.message),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun showUpdateAvailableDialog(info: UpdateInfo) {
        AlertDialog.Builder(this)
            .setTitle(R.string.update_available)
            .setMessage(getString(R.string.update_available_version, info.latestVersion))
            .setPositiveButton(R.string.update_now) { _, _ -> startUpdate(info) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startUpdate(info: UpdateInfo) {
        if (UpdateChecker.isUpdateInstallInProgress()) {
            showUpdateInProgressState()
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            showInstallPermissionDialogForUpdate()
            return
        }

        showUpdateInProgressState()
        UpdateChecker.downloadAndInstall(
            context = this,
            updateInfo = info,
            onProgress = { percent ->
                if (isDestroyed || isFinishing) return@downloadAndInstall
                binding.btnCheckUpdates.text =
                    getString(R.string.update_downloading_percent, percent)
            },
            onError = { message ->
                if (isDestroyed || isFinishing) return@downloadAndInstall
                resetUpdateButton()
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG)
                    .show()
            },
        )
    }

    private fun resetUpdateButton() {
        if (::binding.isInitialized) {
            if (UpdateChecker.isUpdateInstallInProgress()) {
                showUpdateInProgressState()
                return
            }
            binding.btnCheckUpdates.isEnabled = true
            binding.btnCheckUpdates.text = getString(R.string.update_check_for_updates)
        }
    }

    private fun syncUpdateButtonForActiveInstall() {
        if (UpdateChecker.isUpdateInstallInProgress()) {
            showUpdateInProgressState()
        }
    }

    private fun showUpdateInProgressState() {
        if (!::binding.isInitialized) return
        binding.btnCheckUpdates.isEnabled = false
        binding.btnCheckUpdates.text = getString(R.string.update_downloading)
    }

    private fun consumePendingUpdateInstallResult() {
        when (val result = UpdateChecker.pendingInstallResult) {
            is InstallResult.Done -> {
                UpdateChecker.pendingInstallResult = null
                resetUpdateButton()
                android.widget.Toast.makeText(this, result.message, android.widget.Toast.LENGTH_LONG).show()
            }
            is InstallResult.SignatureConflict -> {
                UpdateChecker.pendingInstallResult = null
                resetUpdateButton()
                showSignatureConflictDialog(result.apkSavedToDownloads, result.apkUrl)
            }
            null -> Unit
        }
    }

    private fun showInstallPermissionDialogForUpdate() {
        AlertDialog.Builder(this)
            .setTitle(R.string.update_install_permission_title)
            .setMessage(R.string.update_install_permission_message)
            .setPositiveButton(R.string.update_open_install_settings) { _, _ ->
                UpdateChecker.openInstallPermissionSettings(this)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSignatureConflictDialog(apkSavedToDownloads: Boolean, apkUrl: String?) {
        if (isDestroyed || isFinishing) return
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.update_requires_reinstall)
            .setNegativeButton(R.string.cancel, null)

        if (apkSavedToDownloads) {
            builder
                .setMessage(R.string.update_signature_mismatch_message)
                .setPositiveButton(R.string.update_uninstall) { _, _ ->
                    UpdateChecker.openAppDetailsSettings(this)
                }
        } else {
            builder.setMessage(R.string.update_signature_mismatch_save_failed_message)
            if (!apkUrl.isNullOrBlank()) {
                builder.setPositiveButton(R.string.update_download_apk) { _, _ ->
                    openUpdateApkUrl(apkUrl)
                }
            } else {
                builder.setPositiveButton(android.R.string.ok, null)
            }
        }

        builder.show()
    }

    private fun openUpdateApkUrl(apkUrl: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl)))
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                this,
                R.string.update_open_download_failed,
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun registerUpdateReceivers() {
        if (!isInstallReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                installResultReceiver,
                IntentFilter(ApiHandler.ACTION_INSTALL_RESULT),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            isInstallReceiverRegistered = true
        }
        if (!isSignatureConflictReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                signatureConflictReceiver,
                IntentFilter(UpdateInstallReceiver.ACTION_SIGNATURE_CONFLICT),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            isSignatureConflictReceiverRegistered = true
        }
    }

    private fun unregisterUpdateReceivers() {
        if (isInstallReceiverRegistered) {
            try {
                unregisterReceiver(installResultReceiver)
            } catch (_: Exception) {
            } finally {
                isInstallReceiverRegistered = false
            }
        }
        if (isSignatureConflictReceiverRegistered) {
            try {
                unregisterReceiver(signatureConflictReceiver)
            } catch (_: Exception) {
            } finally {
                isSignatureConflictReceiverRegistered = false
            }
        }
    }

    private fun setupResetButton() {
        binding.btnResetSettings.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset to Defaults")
                .setMessage("This will reset all settings to their default values and disconnect any active connections. Continue?")
                .setPositiveButton("Reset") { _, _ ->
                    val serviceIntent = Intent(this, ReverseConnectionService::class.java).apply {
                        action = ReverseConnectionService.ACTION_DISCONNECT
                    }
                    startService(serviceIntent)

                    configManager.resetToDefaults()
                    TriggerRepository.getInstance(this).clearAll()
                    ConnectionStateManager.setState(ConnectionState.DISCONNECTED)

                    android.widget.Toast.makeText(
                        this,
                        "Settings reset to defaults",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()

                    val intent = Intent(this, SettingsActivity::class.java)
                    finish()
                    startActivity(intent)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun restartServiceIfEnabled() {
        if (configManager.reverseConnectionEnabled) {
            val intent = Intent(
                ReverseConnectionService.ACTION_RECONNECT,
                null,
                this,
                ReverseConnectionService::class.java,
            )
            startForegroundService(intent)
        }
    }

    private fun sanitizeToken(value: String?): String {
        return value?.replace("\\s+".toRegex(), "") ?: ""
    }

    private fun persistReverseConnectionInputs() {
        val url = binding.inputReverseUrl.text?.toString()?.trim() ?: ""
        val token = sanitizeToken(binding.inputReverseToken.text?.toString())
        val shouldReconnect =
            ReverseConnectionSettingsPolicy.shouldReconnectAfterInputPersistence(
                enabled = configManager.reverseConnectionEnabled,
                currentEffectiveUrl = configManager.reverseConnectionUrlOrDefault,
                currentToken = configManager.reverseConnectionToken,
                candidateUrl = url,
                candidateToken = token,
                defaultUrl = configManager.defaultReverseConnectionUrl,
            )
        configManager.reverseConnectionUrl = url
        configManager.reverseConnectionToken = token
        if (shouldReconnect) restartServiceIfEnabled()
    }

    private fun currentCreditsToken(): String {
        return sanitizeToken(binding.inputReverseToken.text?.toString()).trim()
    }

    private fun currentCreditsReverseConnectionUrl(): String {
        val rawValue = binding.inputReverseUrl.text?.toString()?.trim().orEmpty()
        return rawValue.ifBlank { configManager.reverseConnectionUrlOrDefault }
    }

    private fun refreshCreditsBalance(force: Boolean = false) {
        val authToken = currentCreditsToken()
        val cloudBaseUrl = PortalCloudClient.deriveCloudBaseUrl(currentCreditsReverseConnectionUrl())
        val fingerprint = currentCreditsFingerprint(authToken, cloudBaseUrl)
        PortalBalanceRepository.observeFingerprint(fingerprint)

        renderCreditsUi(authToken, cloudBaseUrl)

        if (authToken.isBlank() || cloudBaseUrl == null || fingerprint == null) {
            return
        }

        PortalBalanceRepository.loadBalance(
            fingerprint = fingerprint,
            cloudBaseUrl = cloudBaseUrl,
            authToken = authToken,
            force = force,
            loader = portalCloudClient::loadBalance,
        ) {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread

                renderCreditsUi(authToken, cloudBaseUrl)
            }
        }
    }

    private fun renderCreditsUi(
        authToken: String = currentCreditsToken(),
        cloudBaseUrl: String? = PortalCloudClient.deriveCloudBaseUrl(currentCreditsReverseConnectionUrl()),
    ) {
        val showSection = authToken.isNotBlank()
        binding.textCreditsSectionHeader.visibility = if (showSection) View.VISIBLE else View.GONE
        binding.cardCreditsSettings.visibility = if (showSection) View.VISIBLE else View.GONE
        if (!showSection) {
            return
        }

        val creditsState = PortalBalanceRepository.snapshot(currentCreditsFingerprint(authToken, cloudBaseUrl))
        val info = if (cloudBaseUrl != null) creditsState.info else null
        val balanceLine = info?.let {
            if (info.unlimited) {
                getString(com.mobilerun.portal.R.string.credits_balance_unlimited)
            } else {
                getString(
                    com.mobilerun.portal.R.string.credits_balance_line,
                    formatCreditsCount(info.balance),
                )
            }
        }?.takeIf { it.isNotBlank() }
        val usageLine = info?.let {
            getString(
                com.mobilerun.portal.R.string.credits_usage_line,
                formatCreditsCount(info.usage),
            )
        }?.takeIf { it.isNotBlank() }

        val hasMetrics =
            bindCreditsLine(binding.textCreditsBalanceSettings, balanceLine) or
                bindCreditsLine(binding.textCreditsUsageSettings, usageLine)
        binding.cardCreditsMetricsSettings.visibility = if (hasMetrics) View.VISIBLE else View.GONE

        val message = when {
            cloudBaseUrl == null -> getString(com.mobilerun.portal.R.string.credits_unsupported_host)
            creditsState.isLoading && hasMetrics -> getString(com.mobilerun.portal.R.string.credits_refreshing)
            creditsState.isLoading -> getString(com.mobilerun.portal.R.string.credits_loading)
            !creditsState.message.isNullOrBlank() -> creditsState.message
            else -> null
        }
        binding.textCreditsMessageSettings.text = message
        binding.textCreditsMessageSettings.visibility =
            if (message.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.btnRefreshCreditsSettings.isEnabled =
            !creditsState.isLoading && authToken.isNotBlank() && cloudBaseUrl != null
    }

    private fun formatCreditsCount(value: Int): String {
        return NumberFormat.getIntegerInstance().format(value)
    }

    private fun currentCreditsFingerprint(authToken: String, cloudBaseUrl: String?): String? {
        if (authToken.isBlank() || cloudBaseUrl == null) {
            return null
        }
        return PortalBalanceRepository.buildFingerprint(cloudBaseUrl, authToken)
    }

    private fun bindCreditsLine(view: TextView, text: String?): Boolean {
        val normalized = text?.takeIf { it.isNotBlank() }
        view.text = normalized.orEmpty()
        view.visibility = if (normalized == null) View.GONE else View.VISIBLE
        return normalized != null
    }

    private fun setupEventToggle(
        switch: com.google.android.material.switchmaterial.SwitchMaterial,
        type: EventType,
    ) {
        switch.isChecked = configManager.isEventEnabled(type)

        switch.setOnCheckedChangeListener { _, isChecked ->
            configManager.setEventEnabled(type, isChecked)
        }
    }

    private fun syncServerSettingsFromConfig() {
        updateSocketServerEnabledUi(configManager.socketServerEnabled)
        updateSocketServerPortUi(configManager.socketServerPort)
        updateWebSocketEnabledUi(configManager.websocketEnabled)
        updateWebSocketPortUi(configManager.websocketPort)
        updateStreamSessionLimitUi(configManager.streamSessionLimit)
        binding.switchKeepScreenAwake.isChecked = configManager.keepScreenAwakeEnabled
    }

    private fun updateSocketServerEnabledUi(enabled: Boolean) {
        suppressSocketServerSwitchCallback = true
        binding.switchSocketServerEnabled.isChecked = enabled
        suppressSocketServerSwitchCallback = false
    }

    private fun updateSocketServerPortUi(port: Int) {
        val current = binding.inputSocketServerPort.text?.toString()
        val expected = port.toString()
        if (current != expected) {
            binding.inputSocketServerPort.setText(expected)
        }
    }

    private fun updateWebSocketEnabledUi(enabled: Boolean) {
        suppressWebSocketSwitchCallback = true
        binding.switchWsEnabled.isChecked = enabled
        suppressWebSocketSwitchCallback = false
    }

    private fun updateWebSocketPortUi(port: Int) {
        val current = binding.inputWsPort.text?.toString()
        val expected = port.toString()
        if (current != expected) {
            binding.inputWsPort.setText(expected)
        }
    }

    private fun updateStreamSessionLimitUi(limit: Int) {
        val current = binding.inputStreamSessionLimit.text?.toString()
        val expected = limit.toString()
        if (current != expected) {
            binding.inputStreamSessionLimit.setText(expected)
        }
        binding.inputStreamSessionLimit.error = null
    }

    private fun updatePermissionSwitches() {
        // Notification Access
        binding.switchNotificationAccess.isChecked = isNotificationServiceEnabled()

        // Post Notifications
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val isGranted =
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            binding.switchPostNotifications.isChecked = isGranted
            binding.switchPostNotifications.isEnabled = true
        } else {
            // Pre-Tiramisu, permission is granted at install time
            binding.switchPostNotifications.isChecked = true
            binding.switchPostNotifications.isEnabled = false
        }

        // Install Unknown Apps
        binding.switchInstallUnknownApps.isChecked = packageManager.canRequestPackageInstalls()

        binding.switchUnrestrictedBattery.isChecked = isIgnoringBatteryOptimizations()

        updateSmsDiagnosticsStatus()
    }

    private fun isIgnoringBatteryOptimizations(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

    private fun updateSmsDiagnosticsStatus() {
        binding.switchSmsGateway.isChecked = configManager.smsGatewayEnabled
        val send = grantLabel(Manifest.permission.SEND_SMS)
        val read = grantLabel(Manifest.permission.READ_SMS)
        val receive = grantLabel(Manifest.permission.RECEIVE_SMS)
        val phoneState = grantLabel(Manifest.permission.READ_PHONE_STATE)
        val hasSmsMessaging = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)
        } else {
            @Suppress("DEPRECATION")
            packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        }
        val telephony = if (hasSmsMessaging) {
            "available"
        } else {
            "not available"
        }
        binding.textSmsPermissionsStatus.text =
            "SMS messaging: $telephony\nSEND_SMS: $send\nREAD_SMS: $read\nRECEIVE_SMS: $receive\nREAD_PHONE_STATE: $phoneState"
    }

    private fun grantLabel(permission: String): String {
        return if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            "granted"
        } else {
            "missing"
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val componentName = ComponentName(this, MobilerunNotificationListener::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(componentName.flattenToString()) == true
    }

    override fun onOverlayVisibilityChanged(visible: Boolean) {}

    override fun onOverlayOffsetChanged(offset: Int) {}

    override fun onSocketServerEnabledChanged(enabled: Boolean) {
        runOnUiThread {
            updateSocketServerEnabledUi(enabled)
        }
    }

    override fun onSocketServerPortChanged(port: Int) {
        runOnUiThread {
            updateSocketServerPortUi(port)
        }
    }

    override fun onWebSocketEnabledChanged(enabled: Boolean) {
        runOnUiThread {
            updateWebSocketEnabledUi(enabled)
        }
    }

    override fun onWebSocketPortChanged(port: Int) {
        runOnUiThread {
            updateWebSocketPortUi(port)
        }
    }

    override fun onKeepScreenAwakeEnabledChanged(enabled: Boolean) {
        runOnUiThread {
            binding.switchKeepScreenAwake.isChecked = enabled
        }
    }

    override fun onSmsGatewayEnabledChanged(enabled: Boolean) {
        runOnUiThread {
            binding.switchSmsGateway.isChecked = enabled
        }
    }

    override fun onStreamSessionLimitChanged(limit: Int) {
        runOnUiThread {
            updateStreamSessionLimitUi(limit)
        }
    }

    companion object {
        private const val MIN_PORT = 1024
        private const val MAX_PORT = 65535
    }
}
