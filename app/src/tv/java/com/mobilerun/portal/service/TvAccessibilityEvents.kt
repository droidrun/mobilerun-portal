package com.mobilerun.portal.service

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.Keep
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.mobilerun.portal.config.ConfigManager
import com.mobilerun.portal.events.EventHub
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.BiConsumer

/** One bounded queue and one worker keep synchronous accessibility binder reads off the UI thread. */
@Keep
class TvAccessibilityEvents(
    private val service: MobilerunAccessibilityService,
    private val updateUi: BiConsumer<String, String?>,
    private val onAutoAcceptFailed: Runnable,
) : MobilerunAccessibilityService.TvEventHandler {
    companion object {
        private val bound = MutableLiveData(false)
        val connected: LiveData<Boolean> = bound
    }

    private data class Pending(val event: AccessibilityEvent, val eventClass: String)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "tv-accessibility-events")
    }
    @Volatile private var closed = false
    private val config = ConfigManager.getInstance(service)
    private val appLabel = service.applicationInfo.loadLabel(service.packageManager).toString()
    // Only main handles EventHub callbacks, preserving the relay/trigger consumers' existing threading.
    // The transition tracker and every access to windows remain confined to the worker.
    private val transitions = ForegroundApplicationTransitionTracker { event ->
        main.post { if (!closed) EventHub.emit(event) }
    }
    private val activityTracker = TvForegroundActivityTracker()
    private var projectionDeclinedLogged = false
    private val coalescer = LatestEventCoalescer<Pending>(
        nowMs = SystemClock::uptimeMillis,
        schedule = { delay, task -> worker.schedule({ task() }, delay, TimeUnit.MILLISECONDS) },
        consume = { pending ->
            try {
                process(pending)
            } catch (error: RuntimeException) {
                Log.e("TvAccessibilityEvents", "Accessibility event processing failed", error)
            }
        },
        discard = { it.event.recycle() },
    )

    private val dialogs = LatestEventCoalescer<Pending>(
        nowMs = SystemClock::uptimeMillis,
        schedule = { delay, task -> worker.schedule({ task() }, delay, TimeUnit.MILLISECONDS) },
        consume = { pending ->
            try {
                processDialog(pending)
            } catch (error: RuntimeException) {
                Log.e("TvAccessibilityEvents", "Permission dialog processing failed", error)
            }
        },
        discard = { it.event.recycle() },
    )

    init { bound.value = true }

    override fun onEvent(event: AccessibilityEvent?) {
        if (closed || event == null) return
        val pkg = event.packageName?.toString().orEmpty()
        val className = event.className?.toString().orEmpty()
        updateUi.accept(pkg, null)
        activityTracker.onEvent(event)
        // Only window events can replace foreground evidence; content storms cannot starve it.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) coalescer.offer(Pending(AccessibilityEvent.obtain(event), className))
        if ((pkg == MediaProjectionAutoAccept.SYSTEM_UI_PACKAGE && AutoAcceptGate.isMediaProjectionArmed()) ||
            (AutoAcceptGate.isInstallArmed() && PackageInstallerAutoAccept.isInstallDialog(event, className))
        ) dialogs.offer(Pending(AccessibilityEvent.obtain(event), className))
    }

    private fun process(pending: Pending) {
        if (closed) return
        val event = pending.event
        val foreground = ForegroundApplicationWindowResolver.resolve(event) { service.windows }
        if (closed) return
        transitions.advance(foreground?.packageName)
        val candidate = activityTracker.matching(foreground?.packageName)
        if (candidate != null) main.post {
            if (!closed && activityTracker.matching(candidate.packageName) == candidate) {
                updateUi.accept(candidate.packageName, candidate.className)
            }
        }
    }

    private fun processDialog(pending: Pending) {
        if (closed) return
        val event = pending.event
        val projection = MediaProjectionAutoAccept.isMediaProjectionDialog(event, pending.eventClass) &&
            ReverseConnectionService.getInstance() != null && config.screenShareAutoAcceptEnabled &&
            AutoAcceptGate.isMediaProjectionArmed()
        val install = PackageInstallerAutoAccept.isInstallDialog(event, pending.eventClass) &&
            config.installAutoAcceptEnabled && AutoAcceptGate.isInstallArmed()
        if (!projection && !install) return
        val root = service.rootInActiveWindow
        try {
            if (closed) return
            if (projection) {
                if (root != null && TvMediaProjectionAutoAccept.tryAccept(
                        root, appLabel, AutoAcceptGate.isMediaProjectionArmed())) {
                    AutoAcceptGate.disarmMediaProjection()
                    projectionDeclinedLogged = false
                    Log.i("TvAccessibilityEvents", "tv projection consent accepted")
                } else {
                    if (!projectionDeclinedLogged) {
                        projectionDeclinedLogged = true
                        Log.w("TvAccessibilityEvents", "TV projection consent declined: eventClass=${pending.eventClass}, rootPackage=${root?.packageName}")
                    }
                    if (root != null && AutoAcceptGate.isMediaProjectionArmed() &&
                        root.packageName?.toString() == MediaProjectionAutoAccept.SYSTEM_UI_PACKAGE &&
                        TvMediaProjectionAutoAccept.hasShareModeUi(root)) {
                        val result = MediaProjectionAutoAccept.tryAutoAccept(root, pending.eventClass)
                        if (result is MediaProjectionAutoAccept.AutoAcceptResult.Failed) {
                            main.post { if (!closed) onAutoAcceptFailed.run() }
                        }
                    }
                }
            } else if (!AutoAcceptGate.isMediaProjectionArmed()) {
                projectionDeclinedLogged = false
            }
            if (install && root != null) PackageInstallerAutoAccept.tryAutoAccept(root, pending.eventClass)
        } finally {
            root?.recycle()
        }
    }

    override fun close() {
        closed = true
        coalescer.close()
        dialogs.close()
        worker.shutdownNow() // Never wait for a blocked binder call on main.
        main.removeCallbacksAndMessages(null)
        bound.value = false
    }
}
