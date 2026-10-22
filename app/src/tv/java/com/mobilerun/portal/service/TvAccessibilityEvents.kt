package com.mobilerun.portal.service

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.mobilerun.portal.config.ConfigManager
import com.mobilerun.portal.events.EventHub
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One bounded queue and one worker keep synchronous accessibility binder reads off the UI thread. */
class TvAccessibilityEvents(
    private val service: MobilerunAccessibilityService,
    private val updateUi: (String, String?) -> Unit,
    private val onAutoAcceptFailed: () -> Unit,
) : MobilerunAccessibilityService.TvEventHandler {
    companion object {
        private const val TAG = "TvAccessibilityEvents"
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
    private val windowRecovery = TvWindowStateRecovery(service, worker)
    private var projectionDeclinedLogged = false
    private val events = coalescer("Accessibility event", ::process)
    private val dialogs = coalescer("Permission dialog", ::processDialog)

    private fun coalescer(label: String, handle: (Pending) -> Unit) = LatestEventCoalescer<Pending>(
        nowMs = SystemClock::uptimeMillis,
        schedule = { delay, task -> worker.schedule({ task() }, delay, TimeUnit.MILLISECONDS) },
        consume = { pending ->
            try {
                handle(pending)
            } catch (error: RuntimeException) {
                Log.e(TAG, "$label processing failed", error)
            }
        },
        discard = { it.event.recycle() },
    )

    override fun onEvent(event: AccessibilityEvent?) {
        if (closed || event == null) return
        val pkg = event.packageName?.toString().orEmpty()
        val className = event.className?.toString().orEmpty()
        updateUi(pkg, null)
        activityTracker.onEvent(event)
        // Only window events can replace foreground evidence; content storms cannot starve it.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) events.offer(Pending(AccessibilityEvent.obtain(event), className))
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
                updateUi(candidate.packageName, candidate.className)
            }
        }
    }

    private fun processDialog(pending: Pending) {
        if (!AutoAcceptGate.isMediaProjectionArmed()) projectionDeclinedLogged = false
        if (closed) return
        val root = service.rootInActiveWindow
        try {
            if (closed) return
            acceptProjection(pending, root)
            acceptInstall(pending, root)
        } finally {
            root?.recycle()
        }
    }

    private fun acceptProjection(pending: Pending, root: AccessibilityNodeInfo?) {
        if (!AutoAcceptGate.isMediaProjectionArmed() || !config.screenShareAutoAcceptEnabled ||
            ReverseConnectionService.getInstance() == null ||
            !MediaProjectionAutoAccept.isMediaProjectionDialog(pending.event, pending.eventClass)
        ) return
        if (root != null && TvMediaProjectionAutoAccept.tryAccept(root, appLabel)) {
            AutoAcceptGate.disarmMediaProjection()
            projectionDeclinedLogged = false
            Log.i(TAG, "tv projection consent accepted")
            windowRecovery.afterProjectionConsent()
        } else {
            if (!projectionDeclinedLogged) {
                projectionDeclinedLogged = true
                Log.w(TAG, "TV projection consent declined: eventClass=${pending.eventClass}, rootPackage=${root?.packageName}")
            }
            if (root != null && AutoAcceptGate.isMediaProjectionArmed() &&
                root.packageName?.toString() == MediaProjectionAutoAccept.SYSTEM_UI_PACKAGE &&
                TvMediaProjectionAutoAccept.hasShareModeUi(root)
            ) {
                val result = MediaProjectionAutoAccept.tryAutoAccept(root, pending.eventClass)
                if (result == MediaProjectionAutoAccept.AutoAcceptResult.ActionPerformed) {
                    // This may be an intermediate share-mode choice; actual capture start schedules the refresh.
                    windowRecovery.requestProbe()
                }
                if (result is MediaProjectionAutoAccept.AutoAcceptResult.Failed) {
                    main.post { if (!closed) onAutoAcceptFailed() }
                }
            }
        }
    }

    private fun acceptInstall(pending: Pending, root: AccessibilityNodeInfo?) {
        if (root != null && config.installAutoAcceptEnabled && AutoAcceptGate.isInstallArmed() &&
            PackageInstallerAutoAccept.isInstallDialog(pending.event, pending.eventClass)
        ) PackageInstallerAutoAccept.tryAutoAccept(root, pending.eventClass)
    }

    override fun onProjectionStarted() = windowRecovery.afterProjectionStarted()

    override fun recoverEmptyRoot() = windowRecovery.recoverEmptyRoot()

    override fun probeWindowHealth(focusedApp: Boolean) = windowRecovery.requestProbe()

    override fun close() {
        closed = true
        windowRecovery.close()
        events.close()
        dialogs.close()
        worker.shutdownNow() // Never wait for a blocked binder call on main.
        main.removeCallbacksAndMessages(null)
    }
}
