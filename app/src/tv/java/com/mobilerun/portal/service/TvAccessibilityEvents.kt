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
) : MobilerunAccessibilityService.TvEventHandler {
    companion object {
        private val bound = MutableLiveData(false)
        val connected: LiveData<Boolean> = bound
    }

    private data class Pending(val event: AccessibilityEvent, val windowClass: String)
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
    private var stateWindowId = -1
    private var stateWindowClass = ""
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
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            stateWindowId = event.windowId
            stateWindowClass = className
        }
        val windowClass = if (stateWindowId == event.windowId) stateWindowClass else className
        // Only window events can replace foreground evidence; content storms cannot starve it.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) coalescer.offer(Pending(AccessibilityEvent.obtain(event), windowClass))
        if ((pkg == TvMediaProjectionAutoAccept.SYSTEM_UI && AutoAcceptGate.isMediaProjectionArmed()) ||
            (AutoAcceptGate.isInstallArmed() && PackageInstallerAutoAccept.isInstallDialog(event, className))
        ) dialogs.offer(Pending(AccessibilityEvent.obtain(event), windowClass))
    }

    private fun process(pending: Pending) {
        if (closed) return
        val event = pending.event
        val foreground = ForegroundApplicationWindowResolver.resolve(event) { service.windows }
        if (closed) return
        transitions.advance(foreground?.packageName)
        val activity = pending.windowClass.takeIf {
            foreground != null && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                it.isNotEmpty() && !it.startsWith("android.")
        }
        val pkg = event.packageName?.toString().orEmpty()
        if (activity != null) main.post {
            if (!closed) updateUi.accept(pkg, activity)
        }
    }

    private fun processDialog(pending: Pending) {
        if (closed) return
        val event = pending.event
        val projection = MediaProjectionAutoAccept.isMediaProjectionDialog(event, pending.windowClass) &&
            ReverseConnectionService.getInstance() != null && config.screenShareAutoAcceptEnabled &&
            AutoAcceptGate.isMediaProjectionArmed()
        val install = PackageInstallerAutoAccept.isInstallDialog(event, pending.windowClass) &&
            config.installAutoAcceptEnabled && AutoAcceptGate.isInstallArmed()
        if (!projection && !install) return
        val root = service.rootInActiveWindow ?: return
        try {
            if (closed || root.windowId != event.windowId) return
            if (projection) {
                if (TvMediaProjectionAutoAccept.isPermissionWindow(event.packageName?.toString(), pending.windowClass)) {
                    if (TvMediaProjectionAutoAccept.tryAccept(root, pending.windowClass, appLabel)) {
                        AutoAcceptGate.disarmMediaProjection()
                    }
                } else {
                    MediaProjectionAutoAccept.tryAutoAccept(root, pending.windowClass)
                }
            }
            if (install) PackageInstallerAutoAccept.tryAutoAccept(root, pending.windowClass)
        } finally {
            root.recycle()
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
