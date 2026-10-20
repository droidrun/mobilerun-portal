package com.mobilerun.portal.taskprompt

/** Delays automatic terminal-task detail reloads after a failed fetch. */
internal class PortalTaskDetailsRetryGate(
    private val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
) {
    private var failedKey: Pair<String, String>? = null
    private var failedAtMs = 0L

    fun canLoad(taskId: String, fingerprint: String, nowMs: Long): Boolean {
        return failedKey != (taskId to fingerprint) || nowMs - failedAtMs >= retryDelayMs
    }

    fun onFailure(taskId: String, fingerprint: String, nowMs: Long) {
        failedKey = taskId to fingerprint
        failedAtMs = nowMs
    }

    fun reset() {
        failedKey = null
    }

    companion object {
        const val DEFAULT_RETRY_DELAY_MS = 60_000L
    }
}
