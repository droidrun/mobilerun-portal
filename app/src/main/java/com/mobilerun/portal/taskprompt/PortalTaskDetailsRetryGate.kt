package com.mobilerun.portal.taskprompt

/** Delays automatic terminal-task detail reloads after a failed fetch, within one connection. */
internal class PortalTaskDetailsRetryGate(
    private val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
) {
    private var failedKey: Triple<String, String, Long>? = null
    private var failedAtMs = 0L

    fun canLoad(taskId: String, fingerprint: String, connectionEpoch: Long, nowMs: Long): Boolean {
        return remainingDelayMs(taskId, fingerprint, connectionEpoch, nowMs) == 0L
    }

    fun remainingDelayMs(taskId: String, fingerprint: String, connectionEpoch: Long, nowMs: Long): Long {
        if (failedKey != Triple(taskId, fingerprint, connectionEpoch)) return 0L
        return (retryDelayMs - (nowMs - failedAtMs)).coerceAtLeast(0L)
    }

    /** [connectionEpoch] is the one the request started in, so any reconnect since then lifts the delay. */
    fun onFailure(taskId: String, fingerprint: String, connectionEpoch: Long, nowMs: Long) {
        failedKey = Triple(taskId, fingerprint, connectionEpoch)
        failedAtMs = nowMs
    }

    fun reset() {
        failedKey = null
    }

    companion object {
        const val DEFAULT_RETRY_DELAY_MS = 60_000L
    }
}
