package com.mobilerun.portal.sms

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mobilerun.portal.config.ConfigManager

/** Turns Mobilerun cloud SMS on and off. Enabling never backfills: watermarks start at the newest row. */
class SmsCloudSwitch(
    private val setEnabled: (Boolean) -> Unit,
    private val setCloudSubscriptionId: (Int?) -> Unit,
    private val latestRow: () -> SmsProviderRow?,
    private val seed: (latest: SmsProviderRow?, coveredFrom: Long) -> Unit,
    private val clear: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Watermarks are written before the switch flips, so the reporter never runs without them. */
    fun enable(cloudSubscriptionId: Int?) {
        setCloudSubscriptionId(cloudSubscriptionId)
        seed(latestRow(), clock())
        setEnabled(true)
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
            setCloudSubscriptionId = { config.cloudSubscriptionId = it },
            latestRow = { AndroidSmsProviderSource(appContext).latest() },
            seed = store::seedReporterState,
            clear = { store.async("clear") { store.clearReporterState() } },
        )
    }

    /** Runs on the store thread and reports success on the main thread. */
    fun enableAsync(context: Context, cloudSubscriptionId: Int?, onDone: (Boolean) -> Unit) {
        runAsync(context, "enable", onDone) { switchFor(context).enable(cloudSubscriptionId) }
    }

    /** The switch turns off at once; the reporter state is cleared on the store thread. */
    fun disable(context: Context) {
        switchFor(context).disable()
    }

    private fun runAsync(context: Context, name: String, onDone: (Boolean) -> Unit, block: () -> Unit) {
        val main = Handler(Looper.getMainLooper())
        SmsStore.getInstance(context).executor.execute {
            val ok = try {
                block()
                true
            } catch (e: Exception) {
                Log.w(TAG, "$name failed (${e.javaClass.simpleName})")
                false
            }
            main.post { onDone(ok) }
        }
    }
}

/** The relay SMS bridge handlers wired to the device. */
fun smsBridgeApiFor(context: Context): SmsBridgeApi {
    val appContext = context.applicationContext
    val config = ConfigManager.getInstance(appContext)
    val resolver = SmsSubscriptionResolver(appContext)
    val granted = { permission: String ->
        androidx.core.content.ContextCompat.checkSelfPermission(appContext, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    return SmsBridgeApi(
        cloudEnabled = { config.smsCloudEnabled },
        cloudSubscriptionId = { config.cloudSubscriptionId },
        hasTelephony = resolver::hasTelephony,
        hasReadSms = { granted(android.Manifest.permission.READ_SMS) },
        hasSendSms = { granted(android.Manifest.permission.SEND_SMS) },
        subscriptions = resolver::bridgeSubscriptions,
        provider = AndroidSmsProviderSource(appContext),
        records = SmsStore.getInstance(appContext),
        sender = AndroidSmsSender(appContext),
    )
}
