package com.mobilerun.portal.taskprompt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mobilerun.portal.config.ConfigManager

class TaskPromptNotificationActionReceiver : BroadcastReceiver() {
    // Record writes stay on the main thread, where PortalTaskStateMonitor applies poll results.
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "TaskPromptNotifAction"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != TaskPromptNotificationManager.ACTION_CANCEL_TASK) {
            return
        }

        Log.d(TAG, "Received task notification action=${intent.action} data=${intent.data}")
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        PortalTaskStateMonitor.initialize(appContext)
        val configManager = ConfigManager.getInstance(appContext)
        val activeTask = configManager.activePortalTask
        val taskId = intent.getStringExtra(TaskPromptNotificationManager.EXTRA_TASK_ID)
            ?.takeIf { it.isNotBlank() }
            ?: activeTask?.taskId

        if (activeTask == null || taskId.isNullOrBlank()) {
            Log.w(TAG, "No active task found for cancel action. Clearing notification.")
            TaskPromptNotificationManager.cancel(appContext)
            TaskPromptNotificationManager.broadcastTaskStateChanged(appContext)
            pendingResult.finish()
            return
        }
        if (activeTask.taskId != taskId) {
            Log.w(TAG, "Ignoring stale cancel action for taskId=$taskId while activeTask=${activeTask.taskId}")
            pendingResult.finish()
            return
        }

        val authToken = configManager.reverseConnectionToken.trim()
        val restBaseUrl = PortalCloudClient.deriveRestBaseUrl(configManager.reverseConnectionUrlOrDefault)
        if (authToken.isBlank() || restBaseUrl == null) {
            Log.w(TAG, "Cannot cancel task $taskId because task API is unavailable.")
            pendingResult.finish()
            return
        }

        val previousRecord = activeTask
        val cancellingRecord =
            PortalTaskTracking.withUpdatedStatus(activeTask, PortalTaskTracking.STATUS_CANCELLING)
        Log.i(TAG, "Starting notification cancel flow for taskId=$taskId")
        configManager.saveActivePortalTask(cancellingRecord)
        PortalTaskStateMonitor.onCancelRequested(taskId)
        TaskPromptNotificationManager.showActiveTask(appContext, cancellingRecord)
        TaskPromptNotificationManager.broadcastTaskStateChanged(appContext)
        PortalTaskStateMonitor.reconcileActiveTask(immediate = true)

        val client = PortalCloudClient()
        client.cancelTask(restBaseUrl, authToken, taskId) { result ->
            mainHandler.post {
                when (result) {
                    PortalTaskCancelResult.Success -> {
                        Log.i(TAG, "Cancel request accepted for taskId=$taskId")
                        PortalTaskStateMonitor.reconcileActiveTask(immediate = true)
                        TaskPromptNotificationManager.broadcastTaskStateChanged(appContext)
                        pendingResult.finish()
                    }

                    PortalTaskCancelResult.AlreadyFinished -> {
                        Log.i(TAG, "Cancel action reached already-finished taskId=$taskId. Refreshing status.")
                        client.getTaskStatus(restBaseUrl, authToken, taskId) { statusResult ->
                            mainHandler.post {
                                when (statusResult) {
                                    is PortalTaskStatusResult.Success -> {
                                        Log.i(TAG, "Recovered finished status=${statusResult.value.status} for taskId=$taskId")
                                        // Apply it to the current record so a terminal state a poll already handled is kept.
                                        val currentRecord = configManager.activePortalTask
                                        if (currentRecord?.taskId == taskId) {
                                            val updatedRecord = PortalTaskTracking.withUpdatedStatus(
                                                currentRecord,
                                                statusResult.value.status,
                                            )
                                            if (updatedRecord != currentRecord) {
                                                configManager.saveActivePortalTask(updatedRecord)
                                            }
                                        }
                                        PortalTaskStateMonitor.reconcileActiveTask(immediate = true)
                                    }

                                    is PortalTaskStatusResult.Error -> {
                                        Log.w(TAG, "Could not refresh final status for taskId=$taskId. Restoring previous state.")
                                        restoreAfterFailedCancel(configManager, appContext, previousRecord)
                                    }
                                }
                                TaskPromptNotificationManager.broadcastTaskStateChanged(appContext)
                                pendingResult.finish()
                            }
                        }
                    }

                    is PortalTaskCancelResult.Error -> {
                        Log.w(TAG, "Cancel request failed for taskId=$taskId: ${result.message}")
                        restoreAfterFailedCancel(configManager, appContext, previousRecord)
                        TaskPromptNotificationManager.broadcastTaskStateChanged(appContext)
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    private fun restoreAfterFailedCancel(
        configManager: ConfigManager,
        appContext: Context,
        previousRecord: PortalActiveTaskRecord,
    ) {
        if (PortalTaskTracking.shouldRestoreAfterFailedCancel(configManager.activePortalTask, previousRecord.taskId)) {
            configManager.saveActivePortalTask(previousRecord)
            TaskPromptNotificationManager.showActiveTask(appContext, previousRecord)
        }
        PortalTaskStateMonitor.reconcileActiveTask(immediate = true)
    }
}
