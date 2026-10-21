package com.mobilerun.portal.service

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

internal enum class TvAccessibilityHealthState { NEEDED, READY, RECONNECTING, NOT_RESPONDING }

/** Bound is necessary, but only a successful window/root probe establishes readiness. */
internal class TvAccessibilityHealthPolicy {
    companion object {
        const val RECOVERY_TIMEOUT_MS = 10_000L
    }

    private var blindSince: Long? = null

    fun update(bound: Boolean, usable: Boolean, now: Long): TvAccessibilityHealthState {
        if (!bound || usable) {
            blindSince = null
            return if (bound) TvAccessibilityHealthState.READY else TvAccessibilityHealthState.NEEDED
        }
        val since = blindSince ?: now.also { blindSince = it }
        return if (now - since >= RECOVERY_TIMEOUT_MS) TvAccessibilityHealthState.NOT_RESPONDING
        else TvAccessibilityHealthState.RECONNECTING
    }

    fun remainingMs(now: Long): Long =
        blindSince?.let { (RECOVERY_TIMEOUT_MS - (now - it)).coerceAtLeast(0) } ?: RECOVERY_TIMEOUT_MS
}

/** Updated on main by the current TV service; views never perform accessibility binder probes. */
internal object TvAccessibilityHealth {
    private val mutableState = MutableLiveData(TvAccessibilityHealthState.NEEDED)
    val state: LiveData<TvAccessibilityHealthState> = mutableState

    fun publish(state: TvAccessibilityHealthState) {
        if (mutableState.value != state) mutableState.value = state
    }
}
