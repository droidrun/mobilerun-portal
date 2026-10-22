package com.mobilerun.portal.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.mobilerun.portal.core.GenerationGate
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicBoolean

/** One shared limit covers concurrent tree/key readers and background health probes. */
internal class TvWindowRefreshPolicy {
    companion object {
        const val SELF_HEAL_INTERVAL_MS = 5_000L
    }

    private var lastRefresh: Long? = null

    @Synchronized
    fun shouldRefresh(windowsEmpty: Boolean, rootMissing: Boolean, interactive: Boolean, now: Long): Boolean {
        if (!windowsEmpty || !rootMissing || !interactive) return false
        val previous = lastRefresh
        if (previous != null && now - previous in 0 until SELF_HEAL_INTERVAL_MS) return false
        lastRefresh = now
        return true
    }

    @Synchronized
    fun consentRefreshed(now: Long) { lastRefresh = now }
}

/** Repairs Fire OS window tracking without unbinding or changing enabled accessibility services. */
internal class TvWindowStateRecovery(
    private val service: MobilerunAccessibilityService,
    private val worker: ScheduledExecutorService,
) : AutoCloseable {
    private companion object {
        const val TAG = "TvWindowStateRecovery"
        const val CONSENT_SETTLE_MS = 400L
    }

    private data class Probe(val windowsEmpty: Boolean?, val hasRoot: Boolean) {
        val usable get() = windowsEmpty == false || hasRoot
    }

    private val main = Handler(Looper.getMainLooper())
    private val refreshPolicy = TvWindowRefreshPolicy()
    private val healthPolicy = TvAccessibilityHealthPolicy() // Main-confined.
    private val probeInFlight = AtomicBoolean(false)
    private val probeGeneration = GenerationGate()
    private val power = service.getSystemService(Context.POWER_SERVICE) as PowerManager
    @Volatile private var closed = false
    @Volatile private var lastWindowsEmpty: Boolean? = null
    private val healthTimeout = Runnable { if (!closed) publishHealth(usable = false) }
    private val probeDeadline = Runnable {
        if (!closed && probeInFlight.get()) publishHealth(usable = false)
    }
    private val projectionProbe = Runnable { requestProbe() }
    private val consentRefresh = Runnable {
        if (!closed) {
            try {
                worker.execute {
                    if (reapplyServiceInfo("consent")) refreshPolicy.consentRefreshed(SystemClock.uptimeMillis())
                    requestProbe()
                }
            } catch (_: RejectedExecutionException) { /* Service closed while refresh was queued. */ }
        }
    }

    init {
        main.post {
            if (!closed) {
                publishHealth(usable = false) // Initial bound state; readiness needs the first probe.
                requestProbe()
            }
        }
    }

    fun afterProjectionConsent() {
        main.post {
            if (closed) return@post
            probeGeneration.advance() // A pre-consent probe cannot claim the post-consent service is healthy.
            main.removeCallbacks(consentRefresh)
            main.postDelayed(consentRefresh, CONSENT_SETTLE_MS)
        }
    }

    fun afterProjectionStarted() {
        main.post {
            if (closed) return@post
            probeGeneration.advance() // Ensure an older in-flight probe is followed by a fresh capture probe.
            main.removeCallbacks(projectionProbe)
            main.postDelayed(projectionProbe, CONSENT_SETTLE_MS)
        }
    }

    fun requestProbe() {
        main.post {
            if (closed) return@post
            if (!probeInFlight.compareAndSet(false, true)) return@post
            // A routine probe must not flicker a healthy row to Reconnecting.
            if (TvAccessibilityHealth.state.value == TvAccessibilityHealthState.RECONNECTING ||
                TvAccessibilityHealth.state.value == TvAccessibilityHealthState.NOT_RESPONDING
            ) publishHealth(usable = false)
            main.postDelayed(probeDeadline, TvAccessibilityHealthPolicy.RECOVERY_TIMEOUT_MS)
            val generation = probeGeneration.current()
            try {
                worker.execute {
                    if (closed) return@execute
                    var probe = readProbe()
                    if (!probe.usable && trySelfHeal(probe.windowsEmpty)) probe = readProbe() // One retry.
                    main.post result@{
                        probeInFlight.set(false)
                        main.removeCallbacks(probeDeadline)
                        if (closed) return@result
                        if (probeGeneration.isCurrent(generation)) publishHealth(probe.usable)
                        else requestProbe()
                    }
                }
            } catch (_: RejectedExecutionException) {
                probeInFlight.set(false) // Service was closed while the probe was queued.
                main.removeCallbacks(probeDeadline)
            }
        }
    }

    /** Called only after the caller's root read returned null; true permits exactly one raw retry. */
    fun recoverEmptyRoot(): Boolean {
        if (closed) return false
        // A UI caller must not synchronously probe windows or wait on the worker. A previous background
        // empty-window snapshot can authorize an immediate main-thread repair; otherwise queue a probe.
        val windowsEmpty = if (Looper.myLooper() == main.looper) lastWindowsEmpty else readWindowsEmpty()
        val refreshed = trySelfHeal(windowsEmpty)
        requestProbe()
        return refreshed
    }

    private fun readProbe(): Probe {
        val windowsEmpty = readWindowsEmpty()
        if (windowsEmpty == false) return Probe(windowsEmpty, false)
        val root = try { service.readRootWithoutTvRecovery() } catch (_: RuntimeException) { null }
        return try { Probe(windowsEmpty, root != null) } finally {
            try { root?.recycle() } catch (_: RuntimeException) { /* Stale binder handle. */ }
        }
    }

    private fun readWindowsEmpty(): Boolean? {
        val windows = try { service.windows } catch (_: RuntimeException) { null }
        return try {
            windows?.isEmpty().also { lastWindowsEmpty = it }
        } finally {
            windows?.forEach { window ->
                try { window.recycle() } catch (_: RuntimeException) { /* Stale binder handle. */ }
            }
        }
    }

    private fun trySelfHeal(windowsEmpty: Boolean?): Boolean {
        if (closed || !refreshPolicy.shouldRefresh(
                windowsEmpty = windowsEmpty == true,
                rootMissing = true,
                interactive = try { power.isInteractive } catch (_: RuntimeException) { false },
                now = SystemClock.uptimeMillis(),
            )) return false
        return reapplyServiceInfo("self-heal")
    }

    @Synchronized // Consent and concurrent root readers must not interleave flag changes.
    private fun reapplyServiceInfo(reason: String): Boolean {
        if (closed) return false
        return try {
            val info = service.serviceInfo ?: return false
            val flags = info.flags
            try {
                info.flags = flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS.inv()
                service.serviceInfo = info
            } finally {
                info.flags = flags
                service.serviceInfo = info
            }
            Log.i(TAG, "tv accessibility window state refreshed reason=$reason")
            true
        } catch (error: RuntimeException) {
            Log.w(TAG, "TV accessibility window refresh failed", error)
            false
        }
    }

    private fun publishHealth(usable: Boolean) {
        main.removeCallbacks(healthTimeout)
        val now = SystemClock.uptimeMillis()
        val health = healthPolicy.update(bound = !closed, usable = usable, now = now)
        TvAccessibilityHealth.publish(health)
        if (health == TvAccessibilityHealthState.RECONNECTING) {
            main.postDelayed(healthTimeout, healthPolicy.remainingMs(now))
        }
    }

    override fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        TvAccessibilityHealth.publish(TvAccessibilityHealthState.NEEDED)
    }
}
