package com.mobilerun.portal.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.mobilerun.portal.R
import com.mobilerun.portal.api.ApiHandler
import com.mobilerun.portal.service.AutoAcceptGate

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        Log.d(TAG, "Install status=$status message=$message")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        AutoAcceptGate.armInstall()
                        context.startActivity(confirmIntent)
                    } catch (e: Exception) {
                        AutoAcceptGate.disarmInstall()
                        UpdateChecker.pendingInstallApkUrl = null
                        UpdateChecker.clearActiveInstallState()
                        UpdateChecker.clearRelaunchAfterUpdate(context)
                        broadcastResult(
                            context,
                            success = false,
                            message = context.getString(R.string.update_install_confirm_failed, e.message.orEmpty()),
                        )
                    }
                } else {
                    AutoAcceptGate.disarmInstall()
                    UpdateChecker.pendingInstallApkUrl = null
                    UpdateChecker.clearActiveInstallState()
                    UpdateChecker.clearRelaunchAfterUpdate(context)
                    broadcastResult(context, success = false, message = context.getString(R.string.update_install_confirm_missing))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                AutoAcceptGate.disarmInstall()
                UpdateChecker.pendingInstallApkUrl = null
                UpdateChecker.clearActiveInstallState()
                UpdateChecker.pendingInstallResult =
                    InstallResult.Done(true, context.getString(R.string.update_install_success))
                broadcastResult(context, success = true, message = context.getString(R.string.update_install_success))
                UpdateRelaunchReceiver.launchPortal(context)
            }
            PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                handleSignatureConflict(context)
            }
            else -> {
                if (message.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE", ignoreCase = true)) {
                    handleSignatureConflict(context)
                } else {
                    AutoAcceptGate.disarmInstall()
                    UpdateChecker.pendingInstallApkUrl = null
                    UpdateChecker.clearActiveInstallState()
                    UpdateChecker.clearRelaunchAfterUpdate(context)
                    val resultMessage = context.getString(R.string.update_failed_reason, message)
                    UpdateChecker.pendingInstallResult =
                        InstallResult.Done(false, resultMessage)
                    broadcastResult(context, success = false, message = resultMessage)
                }
            }
        }
    }

    private fun handleSignatureConflict(context: Context) {
        AutoAcceptGate.disarmInstall()
        val apkUrl = UpdateChecker.pendingInstallApkUrl
        val apkSavedToDownloads = UpdateChecker.saveCachedApkToDownloads(context) != null
        UpdateChecker.pendingInstallResult =
            InstallResult.SignatureConflict(apkSavedToDownloads, apkUrl)
        UpdateChecker.clearActiveInstallState()
        UpdateChecker.clearRelaunchAfterUpdate(context)
        context.sendBroadcast(
            Intent(ACTION_SIGNATURE_CONFLICT)
                .setPackage(context.packageName)
                .putExtra(EXTRA_APK_SAVED_TO_DOWNLOADS, apkSavedToDownloads)
                .putExtra(EXTRA_APK_URL, apkUrl),
        )
        UpdateChecker.pendingInstallApkUrl = null
    }

    private fun broadcastResult(context: Context, success: Boolean, message: String) {
        context.sendBroadcast(
            Intent(ApiHandler.ACTION_INSTALL_RESULT)
                .setPackage(context.packageName)
                .putExtra(EXTRA_IS_PORTAL_UPDATE, true)
                .putExtra(ApiHandler.EXTRA_INSTALL_SUCCESS, success)
                .putExtra(ApiHandler.EXTRA_INSTALL_MESSAGE, message),
        )
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "com.mobilerun.portal.action.UPDATE_INSTALL_STATUS"
        const val ACTION_SIGNATURE_CONFLICT = "com.mobilerun.portal.action.UPDATE_SIGNATURE_CONFLICT"
        const val EXTRA_APK_SAVED_TO_DOWNLOADS = "extra_apk_saved_to_downloads"
        const val EXTRA_APK_URL = "extra_apk_url"
        const val EXTRA_IS_PORTAL_UPDATE = "extra_is_portal_update"
        private const val TAG = "UpdateInstallReceiver"
    }
}
