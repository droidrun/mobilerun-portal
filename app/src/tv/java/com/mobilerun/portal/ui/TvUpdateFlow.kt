package com.mobilerun.portal.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.mobilerun.portal.BuildConfig
import com.mobilerun.portal.R
import com.mobilerun.portal.api.ApiHandler
import com.mobilerun.portal.databinding.ActivityTvMainBinding
import com.mobilerun.portal.update.InstallResult
import com.mobilerun.portal.update.UpdateCheckResult
import com.mobilerun.portal.update.UpdateChecker
import com.mobilerun.portal.update.UpdateInfo
import com.mobilerun.portal.update.UpdateInstallReceiver

/** Activity-scoped TV update feedback; installation remains owned by the shared updater. */
internal class TvUpdateFlow(
    private val activity: AppCompatActivity,
    private val binding: ActivityTvMainBinding,
    private val onLoadingChanged: (Boolean) -> Unit,
    private val showDialog: (AlertDialog.Builder) -> Unit,
) : AutoCloseable {
    private companion object {
        const val MESSAGE_TTL_MS = 10_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    private val active get() = !closed && !activity.isFinishing && !activity.isDestroyed
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

    init {
        ContextCompat.registerReceiver(activity, updateInstallReceiver, IntentFilter().apply {
            addAction(ApiHandler.ACTION_INSTALL_RESULT)
            addAction(UpdateInstallReceiver.ACTION_SIGNATURE_CONFLICT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun onResume() {
        if (UpdateChecker.isUpdateInstallInProgress() && !downloadingUpdate) {
            downloadingUpdate = true
            setUpdateLoading(true, getString(R.string.tv_update_downloading))
        } else if (!UpdateChecker.isUpdateInstallInProgress() && downloadingUpdate) {
            downloadingUpdate = false
            setUpdateLoading(false)
        }
        consumeUpdateInstallResult()
    }

    fun cancelMessageTimer() = main.removeCallbacks(clearUpdateMessage)

    override fun close() {
        closed = true
        activity.unregisterReceiver(updateInstallReceiver)
        main.removeCallbacksAndMessages(null)
    }

    private fun getString(id: Int, vararg args: Any) = activity.getString(id, *args)

    private fun setUpdateLoading(loading: Boolean, label: String = getString(R.string.tv_update_checking)) {
        val wasFocused = binding.updates.hasFocus()
        updateBusy = loading
        binding.updates.isEnabled = !loading
        binding.updates.text = if (loading) label else getString(R.string.tv_updates)
        binding.updates.setPaddingRelative(
            activity.resources.getDimensionPixelSize(if (loading) R.dimen.tv_update_loading_padding else R.dimen.tv_button_padding),
            binding.updates.paddingTop,
            activity.resources.getDimensionPixelSize(R.dimen.tv_button_padding), binding.updates.paddingBottom)
        binding.updateSpinner.visibility = if (loading) View.VISIBLE else View.GONE
        onLoadingChanged(loading && wasFocused)
    }

    private fun showUpdateMessage(text: String, temporary: Boolean = true) {
        main.removeCallbacks(clearUpdateMessage)
        binding.message.text = text
        binding.message.visibility = View.VISIBLE
        if (temporary) main.postDelayed(clearUpdateMessage, MESSAGE_TTL_MS)
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
        if (!activity.packageManager.canRequestPackageInstalls()) {
            UpdateChecker.openInstallPermissionSettings(activity)
            return
        }
        downloadingUpdate = true
        setUpdateLoading(true, getString(R.string.tv_update_downloading))
        showUpdateMessage(getString(R.string.tv_update_downloading), temporary = false)
        UpdateChecker.downloadAndInstall(activity, info,
            { progress ->
                if (active) {
                    showUpdateMessage(getString(R.string.tv_update_progress, progress), temporary = false)
                }
            },
            {
                if (active) {
                    downloadingUpdate = false
                    setUpdateLoading(false)
                    showUpdateMessage(getString(R.string.tv_update_install_error))
                }
            })
    }

    fun checkUpdates() {
        if (updateBusy) return
        cancelMessageTimer()
        binding.message.visibility = View.GONE
        setUpdateLoading(true)
        UpdateChecker.checkForUpdate(activity) { result ->
            if (!active) return@checkForUpdate
            setUpdateLoading(false)
            when (result) {
                is UpdateCheckResult.Available -> {
                    showUpdateMessage(getString(R.string.tv_update_available, result.info.latestVersion))
                    showDialog(AlertDialog.Builder(activity)
                        .setTitle(R.string.tv_install).setMessage(result.info.latestVersion)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.tv_install) { _, _ -> downloadUpdate(result.info) }
                    )
                }
                is UpdateCheckResult.Failed -> showUpdateMessage(getString(
                    if (result.httpStatus == 404) R.string.tv_update_unpublished
                    else R.string.tv_update_check_error))
                UpdateCheckResult.UpToDate -> showUpdateMessage(getString(R.string.tv_no_updates, BuildConfig.VERSION_NAME))
            }
        }
    }
}
