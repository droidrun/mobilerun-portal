package com.mobilerun.portal.auth

import java.util.concurrent.Executor

sealed interface DeviceSignInState {
    data object Idle : DeviceSignInState
    data object Requesting : DeviceSignInState
    class Waiting(val authorization: DeviceAuthorization) : DeviceSignInState
    data object Expired : DeviceSignInState
    data object Denied : DeviceSignInState
    data object Offline : DeviceSignInState
    data class Failed(val code: String) : DeviceSignInState
}

interface MainScheduler {
    fun postDelayed(task: Runnable, delayMs: Long)
    fun remove(task: Runnable)
}

/**
 * One device code per [start]; no new code is requested on expiry. Polling pauses while the
 * screen is stopped and resumes with the same code, which stays approved until it expires.
 */
class DeviceSignInController(
    private val client: DeviceCodeAuthClient,
    private val executor: Executor,
    private val main: MainScheduler,
    private val elapsedRealtime: () -> Long,
    private val onState: (DeviceSignInState) -> Unit,
    private val onAuthorized: (String) -> Unit,
) {
    var state: DeviceSignInState = DeviceSignInState.Idle
        private set

    private var generation = 0L
    private var paused = false
    private var pollDue = false
    private var pollInFlight = false
    private var pollState: DeviceCodePollState? = null
    private var pendingToken: String? = null
    private val pollTask = Runnable { pollNow() }

    fun start(deviceId: String) {
        reset()
        val gen = generation
        setState(DeviceSignInState.Requesting)
        val requestedAt = elapsedRealtime()
        executor.execute {
            val result = client.requestCode(deviceId)
            main.postDelayed({ onCode(gen, requestedAt, result) }, 0)
        }
    }

    fun pause() {
        paused = true
        main.remove(pollTask)
        if (state is DeviceSignInState.Waiting) pollDue = true
    }

    fun resume() {
        paused = false
        pendingToken?.let {
            pendingToken = null
            authorize(it)
            return
        }
        if (state !is DeviceSignInState.Waiting) return
        if (pollState?.expired(elapsedRealtime()) == true) {
            finish(DeviceSignInState.Expired)
        } else if (pollDue) {
            pollNow()
        }
    }

    fun cancel() {
        reset()
        setState(DeviceSignInState.Idle)
    }

    /** An approved token that never reached [onAuthorized]; the caller must revoke it. */
    fun takePendingToken(): String? = pendingToken.also { pendingToken = null }

    private fun reset() {
        generation++
        main.remove(pollTask)
        pollState = null
        pollDue = false
        pollInFlight = false
        pendingToken = null
    }

    private fun onCode(gen: Long, requestedAt: Long, result: DeviceCodeResult) {
        if (gen != generation) return
        when (result) {
            is DeviceCodeResult.Issued -> {
                val authorization = result.authorization
                pollState = DeviceCodePollState(
                    intervalSeconds = authorization.intervalSeconds,
                    expiresAtMs = requestedAt + authorization.expiresInSeconds * 1000,
                )
                setState(DeviceSignInState.Waiting(authorization))
                schedulePoll(pollState!!.intervalMs)
            }

            DeviceCodeResult.NetworkError -> setState(DeviceSignInState.Offline)
            is DeviceCodeResult.Rejected -> setState(DeviceSignInState.Failed(result.code))
        }
    }

    private fun schedulePoll(delayMs: Long) {
        main.remove(pollTask)
        if (paused) {
            pollDue = true
        } else {
            main.postDelayed(pollTask, delayMs)
        }
    }

    private fun pollNow() {
        val waiting = state as? DeviceSignInState.Waiting ?: return
        if (paused) {
            pollDue = true
            return
        }
        pollDue = false
        if (pollInFlight) return
        pollInFlight = true
        val gen = generation
        val deviceCode = waiting.authorization.deviceCode
        executor.execute {
            val result = client.poll(deviceCode)
            main.postDelayed({ onPoll(gen, result) }, 0)
        }
    }

    private fun onPoll(gen: Long, result: DeviceTokenResult) {
        if (gen != generation) return
        pollInFlight = false
        if (result is DeviceTokenResult.Authorized) {
            if (paused) pendingToken = result.token else authorize(result.token)
            return
        }
        if (result is DeviceTokenResult.Error) {
            when (result.code) {
                "access_denied" -> return finish(DeviceSignInState.Denied)
                "expired_token" -> return finish(DeviceSignInState.Expired)
            }
        }
        val poll = pollState ?: return
        val delay = poll.nextDelay(result, elapsedRealtime())
        when {
            delay != null -> schedulePoll(delay)
            poll.expired(elapsedRealtime()) -> finish(DeviceSignInState.Expired)
            result is DeviceTokenResult.Error -> finish(DeviceSignInState.Failed(result.code))
            else -> finish(DeviceSignInState.Expired)
        }
    }

    private fun authorize(token: String) {
        reset()
        setState(DeviceSignInState.Idle)
        onAuthorized(token)
    }

    private fun finish(next: DeviceSignInState) {
        reset()
        setState(next)
    }

    private fun setState(next: DeviceSignInState) {
        state = next
        onState(next)
    }
}
