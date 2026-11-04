package com.mobilerun.portal.sms

import android.Manifest
import android.content.Context
import android.database.ContentObserver
import android.provider.Telephony
import android.util.Log
import com.mobilerun.portal.config.ConfigManager
import com.mobilerun.portal.service.ReverseConnectionService
import com.mobilerun.portal.service.ReverseRpcResult
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** The device -> cloud SMS reporter of this process; see [SmsReporterCore]. */
object SmsBridgeReporter {
    private const val TAG = "SmsBridgeReporter"

    @Volatile
    private var core: SmsReporterCore? = null

    @Volatile
    private var observer: SmsObserverRegistration? = null

    fun install(context: Context) {
        val app = context.applicationContext
        val created = synchronized(this) {
            if (core != null) return
            val config = ConfigManager.getInstance(app)
            val provider = AndroidSmsProviderSource(app)
            val resolver = SmsSubscriptionResolver(app)
            // Without telephony the SMS tokens are not announced, so nothing is reported.
            val telephony = resolver.hasTelephony()
            val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "SmsReporter").also { it.isDaemon = true }
            }
            val scheduler = ExecutorReporterScheduler(executor)
            SmsReporterCore(
                scheduler = scheduler,
                store = LazyReporterStore(app),
                rows = provider,
                calls = ReverseSmsReportCalls,
                packageName = app.packageName,
                cloudEnabled = { telephony && config.smsCloudEnabled },
                hasReadSms = { hasReadSms(app) },
                sims = { SmsReporterSims.from(resolver.bridgeSubscriptions(), config.cloudSubscriptionId) },
                log = { Log.i(TAG, it) },
            ).also { created ->
                core = created
                observer = SmsObserverRegistration(
                    register = { registerObserver(app) },
                    hasReadSms = { hasReadSms(app) },
                    scheduler = scheduler,
                    onReadSms = created::scanSoon,
                    log = { Log.w(TAG, it) },
                )
                SmsBridgeSendStates.listener = created::onSendStateChanged
                config.addListener(SwitchListener())
            }
        }
        observer?.update(ConfigManager.getInstance(app).smsCloudEnabled)
        created.start()
    }

    fun scanSoon() {
        core?.scanSoon()
    }

    /** SMS_RECEIVED: scan now and again at +1, +5 and +15 s. */
    fun scanBurst() {
        retryObserver()
        core?.scanBurst()
    }

    fun onCloudSimChanged() {
        core?.onCloudSimChanged()
    }

    fun onSocketOpen() {
        retryObserver()
        core?.onSocketOpen()
    }

    fun onSocketClosed() {
        core?.onSocketClosed()
    }

    /** The Portal's own permission prompt returned; a grant made in system Settings waits for the recheck. */
    fun onPermissionsChanged() {
        retryObserver()
        core?.scanSoon()
    }

    private fun retryObserver() {
        observer?.retry()
    }

    private fun registerObserver(context: Context): SmsCancellable {
        val resolver = context.contentResolver
        val created = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                retryObserver()
                core?.scanSoon()
            }
        }
        resolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, created)
        return SmsCancellable { resolver.unregisterContentObserver(created) }
    }

    private fun hasReadSms(context: Context): Boolean = isGranted(context, Manifest.permission.READ_SMS)

    private class SwitchListener : ConfigManager.ConfigChangeListener {
        override fun onSmsCloudEnabledChanged(enabled: Boolean) {
            observer?.update(enabled)
            core?.onCloudSwitchChanged(enabled)
        }

        override fun onOverlayVisibilityChanged(visible: Boolean) = Unit
        override fun onOverlayOffsetChanged(offset: Int) = Unit
        override fun onSocketServerEnabledChanged(enabled: Boolean) = Unit
        override fun onSocketServerPortChanged(port: Int) = Unit
    }
}

/**
 * The content://sms observer while cloud SMS is on. Registering throws without READ_SMS, and a
 * grant made in system Settings reaches neither the process nor the Portal, so while the observer
 * or READ_SMS is missing a single recheck runs every [SmsReporterCore.BLOCKED_RETRY_MS].
 */
internal class SmsObserverRegistration(
    private val register: () -> SmsCancellable,
    private val hasReadSms: () -> Boolean,
    private val scheduler: SmsReporterScheduler,
    /** A recheck found READ_SMS granted: scan for what went unreported meanwhile. */
    private val onReadSms: () -> Unit,
    private val log: (String) -> Unit = {},
) {
    private var enabled = false
    private var registration: SmsCancellable? = null
    private var recheck: SmsCancellable? = null

    @Synchronized
    fun update(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) {
            registration?.let { runCatching { it.cancel() } }
            registration = null
        } else if (registration == null) {
            registration = try {
                register()
            } catch (e: Exception) {
                log("SMS observer not registered (${e.javaClass.simpleName})")
                null
            }
        }
        armRecheck()
    }

    /** On scan triggers; acts on the last [update], not on a switch value read before this lock. */
    @Synchronized
    fun retry() {
        if (enabled && hasReadSms()) update(true) else armRecheck()
    }

    private fun armRecheck() {
        if (!enabled || (registration != null && hasReadSms())) {
            recheck?.cancel()
            recheck = null
        } else if (recheck == null) {
            recheck = scheduler.schedule(SmsReporterCore.BLOCKED_RETRY_MS) { onRecheck() }
        }
    }

    @Synchronized
    private fun onRecheck() {
        recheck = null
        if (enabled && hasReadSms()) {
            update(true)
            onReadSms()
        } else {
            armRecheck()
        }
    }
}

internal class ExecutorReporterScheduler(
    private val executor: ScheduledExecutorService,
) : SmsReporterScheduler {
    override fun execute(task: () -> Unit) {
        try {
            executor.execute(Runnable { task() })
        } catch (_: RejectedExecutionException) {
        }
    }

    override fun schedule(delayMs: Long, task: () -> Unit): SmsCancellable =
        try {
            val future = executor.schedule(Runnable { task() }, delayMs, TimeUnit.MILLISECONDS)
            SmsCancellable { future.cancel(false) }
        } catch (_: RejectedExecutionException) {
            SmsCancellable {}
        }
}

/** Calls over the relay socket; Closed when the reverse connection is not open. */
internal object ReverseSmsReportCalls : SmsReportCalls {
    override fun isOpen(): Boolean = ReverseConnectionService.getInstance()?.rpcCalls?.isOpen() == true

    override fun call(method: String, params: JSONObject, callback: (SmsReportOutcome) -> Unit) {
        val calls = ReverseConnectionService.getInstance()?.rpcCalls
        if (calls == null) {
            callback(SmsReportOutcome.Closed)
            return
        }
        calls.call(method, params, SmsReporterCore.CALL_TIMEOUT_MS) { callback(it.toReportOutcome()) }
    }
}

internal fun ReverseRpcResult.toReportOutcome(): SmsReportOutcome = when (this) {
    is ReverseRpcResult.Ok -> SmsReportOutcome.Ok
    is ReverseRpcResult.Error -> SmsReportOutcome.Error(message)
    ReverseRpcResult.Timeout -> SmsReportOutcome.Timeout
    ReverseRpcResult.Closed -> SmsReportOutcome.Closed
}

/** Opens mobilerun_sms.db only when needed; a Portal that never used cloud SMS keeps none. */
private class LazyReporterStore(private val context: Context) : SmsReporterStore {
    private val store: SmsStore get() = SmsStore.getInstance(context)

    override fun <T> atomically(block: () -> T): T = store.atomically(block)
    override fun scanState(): SmsScanState? = store.scanState()
    override fun seedScanState(latest: SmsProviderRow?, coveredFrom: Long) = store.seedScanState(latest, coveredFrom)
    override fun isReported(msgId: String): Boolean = store.isReported(msgId)
    override fun ownSends(): SmsOwnSends = store.ownSends()
    override fun pendingCount(): Int = store.pendingCount()
    override fun commitScan(state: SmsScanState, reports: List<SmsPendingReport>, now: Long) =
        store.commitScan(state, reports, now)

    override fun duePending(now: Long, limit: Int, exclude: Set<String>): List<SmsPendingReport> =
        store.duePending(now, limit, exclude)

    override fun nextPendingAt(exclude: Set<String>): Long? = store.nextPendingAt(exclude)
    override fun updatePending(report: SmsPendingReport) = store.updatePending(report)
    override fun deletePending(msgId: String) = store.deletePending(msgId)
    override fun unreportedRelayRecords(): List<SmsUnreportedSend> =
        SmsStore.existing(context)?.unreportedRelayRecords().orEmpty()

    override fun saveSendStateRetry(id: String, retry: SmsSendStateRetry) = store.saveSendStateRetry(id, retry)

    override fun markReported(id: String, state: SmsSendState) = store.markReported(id, state)
}
