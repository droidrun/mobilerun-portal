package com.mobilerun.portal.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
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
    private var observer: ContentObserver? = null

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
            SmsReporterCore(
                scheduler = ExecutorReporterScheduler(executor),
                store = LazyReporterStore(app),
                rows = provider,
                calls = ReverseSmsReportCalls,
                packageName = app.packageName,
                cloudEnabled = { telephony && config.smsCloudEnabled },
                hasReadSms = {
                    ContextCompat.checkSelfPermission(app, Manifest.permission.READ_SMS) ==
                        PackageManager.PERMISSION_GRANTED
                },
                sims = { SmsReporterSims.from(resolver.bridgeSubscriptions(), config.cloudSubscriptionId) },
                log = { Log.i(TAG, it) },
            ).also { created ->
                core = created
                SmsBridgeSendStates.listener = created::onSendStateChanged
                config.addListener(SwitchListener(app))
            }
        }
        updateObserver(app, ConfigManager.getInstance(app).smsCloudEnabled)
        created.start()
    }

    fun scanSoon() {
        core?.scanSoon()
    }

    /** SMS_RECEIVED: scan now and again at +1, +5 and +15 s. */
    fun scanBurst() {
        core?.scanBurst()
    }

    fun onCloudSimChanged() {
        core?.onCloudSimChanged()
    }

    fun onSocketOpen() {
        core?.onSocketOpen()
    }

    fun onSocketClosed() {
        core?.onSocketClosed()
    }

    private fun updateObserver(context: Context, enabled: Boolean): Unit = synchronized(this) {
        val resolver = context.contentResolver
        if (enabled) {
            if (observer != null) return
            val created = object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    core?.scanSoon()
                }
            }
            try {
                resolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, created)
                observer = created
            } catch (e: Exception) {
                Log.w(TAG, "SMS observer not registered (${e.javaClass.simpleName})")
            }
        } else {
            observer?.let { runCatching { resolver.unregisterContentObserver(it) } }
            observer = null
        }
    }

    private class SwitchListener(private val context: Context) : ConfigManager.ConfigChangeListener {
        override fun onSmsCloudEnabledChanged(enabled: Boolean) {
            updateObserver(context, enabled)
            core?.onCloudSwitchChanged(enabled)
        }

        override fun onOverlayVisibilityChanged(visible: Boolean) = Unit
        override fun onOverlayOffsetChanged(offset: Int) = Unit
        override fun onSocketServerEnabledChanged(enabled: Boolean) = Unit
        override fun onSocketServerPortChanged(port: Int) = Unit
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
    override fun commitScan(state: SmsScanState, reports: List<SmsPendingReport>, now: Long) =
        store.commitScan(state, reports, now)

    override fun duePending(now: Long, limit: Int, exclude: Set<String>): List<SmsPendingReport> =
        store.duePending(now, limit, exclude)

    override fun nextPendingAt(exclude: Set<String>): Long? = store.nextPendingAt(exclude)
    override fun updatePending(report: SmsPendingReport) = store.updatePending(report)
    override fun deletePending(msgId: String) = store.deletePending(msgId)
    override fun unreportedRelayRecords(): List<SmsSendRecord> =
        SmsStore.existing(context)?.unreportedRelayRecords().orEmpty()

    override fun markReported(id: String, state: SmsSendState) = store.markReported(id, state)
}
