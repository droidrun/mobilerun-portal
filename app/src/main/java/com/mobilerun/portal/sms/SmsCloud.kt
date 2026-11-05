package com.mobilerun.portal.sms

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.PermissionChecker
import com.mobilerun.portal.config.ConfigManager

/** Turns Mobilerun cloud SMS on and off. Enabling never backfills: watermarks start at the newest row. */
class SmsCloudSwitch(
    private val setEnabled: (Boolean) -> Unit,
    private val cloudSubscriptionId: () -> Int?,
    private val setCloudSubscriptionId: (Int?) -> Unit,
    private val latestRow: () -> SmsProviderRow?,
    private val seed: (latest: SmsProviderRow?, coveredFrom: Long) -> Unit,
    private val clear: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Store thread. Seeds the watermarks; [turnOn] follows, so the reporter never runs without them. */
    fun prepare(subscriptionId: Int?) {
        setCloudSubscriptionId(subscriptionId)
        seed(latestRow(), clock())
    }

    /** Main thread: config listeners are not thread-safe. */
    fun turnOn() = setEnabled(true)

    /** Store thread. A new cloud SIM also starts at the newest row: nothing from before, or from a pause, is uploaded. */
    fun chooseSim(subscriptionId: Int) {
        if (cloudSubscriptionId() == subscriptionId) return
        seed(latestRow(), clock())
        setCloudSubscriptionId(subscriptionId)
    }

    fun disable() {
        setEnabled(false)
        setCloudSubscriptionId(null)
        clear()
    }
}

object SmsCloud {
    private const val TAG = "SmsCloud"

    fun switchFor(context: Context): SmsCloudSwitch {
        val appContext = context.applicationContext
        val config = ConfigManager.getInstance(appContext)
        val store = SmsStore.getInstance(appContext)
        return SmsCloudSwitch(
            setEnabled = { config.smsCloudEnabled = it },
            cloudSubscriptionId = { config.cloudSubscriptionId },
            setCloudSubscriptionId = { config.cloudSubscriptionId = it },
            latestRow = { AndroidSmsProviderSource(appContext).latest() },
            seed = store::seedReporterState,
            clear = { store.async("clear") { store.clearReporterState() } },
        )
    }

    /** Seeds on the store thread, then turns on and reports success on the main thread. */
    fun enableAsync(context: Context, cloudSubscriptionId: Int?, onDone: (Boolean) -> Unit) {
        val switch = switchFor(context)
        runAsync(context, "enable", onDone, onMain = switch::turnOn) { switch.prepare(cloudSubscriptionId) }
    }

    fun chooseSimAsync(context: Context, subscriptionId: Int, onDone: (Boolean) -> Unit) {
        val switch = switchFor(context)
        runAsync(context, "choose SIM", onDone, onMain = SmsBridgeReporter::onCloudSimChanged) {
            switch.chooseSim(subscriptionId)
        }
    }

    /** The switch turns off at once; the reporter state is cleared on the store thread. */
    fun disable(context: Context) {
        switchFor(context).disable()
    }

    private fun runAsync(
        context: Context,
        name: String,
        onDone: (Boolean) -> Unit,
        onMain: () -> Unit,
        block: () -> Unit,
    ) {
        val main = Handler(Looper.getMainLooper())
        SmsStore.getInstance(context).executor.execute {
            val ok = attempt(name, block)
            main.post { onDone(ok && attempt(name, onMain)) }
        }
    }

    private inline fun attempt(name: String, block: () -> Unit): Boolean =
        try {
            block()
            true
        } catch (e: Exception) {
            Log.w(TAG, "$name failed (${e.javaClass.simpleName})")
            false
        }
}

/** The permission and its app-op: a soft-denied op leaves it granted, but the SMS provider then reads empty. */
internal fun isGranted(context: Context, permission: String): Boolean =
    PermissionChecker.checkSelfPermission(context, permission) == PermissionChecker.PERMISSION_GRANTED

/** The relay SMS bridge handlers wired to the device. */
fun smsBridgeApiFor(context: Context): SmsBridgeApi {
    val appContext = context.applicationContext
    val config = ConfigManager.getInstance(appContext)
    val resolver = SmsSubscriptionResolver(appContext)
    val granted = { permission: String -> isGranted(appContext, permission) }
    return SmsBridgeApi(
        cloudEnabled = { config.smsCloudEnabled },
        cloudSubscriptionId = { config.cloudSubscriptionId },
        hasTelephony = resolver::hasTelephony,
        hasReadSms = { granted(android.Manifest.permission.READ_SMS) },
        hasSendSms = { granted(android.Manifest.permission.SEND_SMS) },
        subscriptions = resolver::bridgeSubscriptions,
        provider = AndroidSmsProviderSource(appContext),
        records = LazySendRecordStore(appContext),
        sender = AndroidSmsSender(appContext),
    )
}

/** Opens mobilerun_sms.db on first use, so bridge calls refused while cloud SMS is off create none. */
private class LazySendRecordStore(private val context: Context) : SmsSendRecordStore {
    private val store: SmsStore get() = SmsStore.getInstance(context)

    override fun find(id: String): SmsSendRecord? = store.find(id)
    override fun insert(record: SmsSendRecord): Boolean = store.insert(record)
    override fun discardPrepared(id: String, callbackToken: String) = store.discardPrepared(id, callbackToken)
    override fun markDispatched(id: String, now: Long): SmsSendRecord? = store.markDispatched(id, now)
    override fun markFailed(id: String, code: String, message: String, now: Long): SmsSendRecord? =
        store.markFailed(id, code, message, now)
}
