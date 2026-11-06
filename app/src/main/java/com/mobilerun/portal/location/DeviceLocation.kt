package com.mobilerun.portal.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** A location fix and when it was taken, on the elapsed-realtime clock. */
data class LocationFix(val latitude: Double, val longitude: Double, val elapsedRealtimeNanos: Long)

sealed interface LocationReading {
    data class Fix(val latitude: Double, val longitude: Double) : LocationReading
    data class Unavailable(val message: String) : LocationReading
}

interface LocationSource {
    fun hasForegroundPermission(): Boolean
    fun hasBackgroundPermission(): Boolean
    fun isLocationEnabled(): Boolean
    fun lastKnown(): List<LocationFix>
    fun current(timeoutMs: Long): LocationFix?
    fun elapsedRealtimeNanos(): Long
}

/** Reads the device location for `location/get` while the Settings sharing switch is on. */
class LocationReader(
    private val source: LocationSource,
    private val freshMs: Long = FRESH_MS,
    private val fallbackMs: Long = FALLBACK_MS,
    private val timeoutMs: Long = CURRENT_TIMEOUT_MS,
    private val sharingEnabled: () -> Boolean,
) {
    fun read(): LocationReading {
        if (!sharingEnabled()) return LocationReading.Unavailable(SHARING_OFF)
        if (!source.hasForegroundPermission()) return LocationReading.Unavailable(PERMISSION_DENIED)
        if (!source.hasBackgroundPermission()) return LocationReading.Unavailable(BACKGROUND_DENIED)
        if (!source.isLocationEnabled()) return LocationReading.Unavailable(LOCATION_OFF)
        return try {
            val last = source.lastKnown().maxByOrNull { it.elapsedRealtimeNanos }
            val fix = last?.takeIf { it.isYoungerThan(freshMs) }
                ?: source.current(timeoutMs)
                ?: last?.takeIf { it.isYoungerThan(fallbackMs) }
            when {
                // Sharing can be turned off while waiting for a current fix.
                !sharingEnabled() -> LocationReading.Unavailable(SHARING_OFF)
                fix == null -> LocationReading.Unavailable(NO_FIX)
                else -> LocationReading.Fix(fix.latitude, fix.longitude)
            }
        } catch (_: SecurityException) {
            LocationReading.Unavailable(PERMISSION_DENIED)
        }
    }

    private fun LocationFix.isYoungerThan(ms: Long) =
        source.elapsedRealtimeNanos() - elapsedRealtimeNanos <= TimeUnit.MILLISECONDS.toNanos(ms)

    companion object {
        const val FRESH_MS = 2 * 60_000L
        const val FALLBACK_MS = 30 * 60_000L
        const val CURRENT_TIMEOUT_MS = 10_000L
        const val SHARING_OFF = "Location sharing is off. Turn it on in Portal Settings."
        const val PERMISSION_DENIED = "Location permission not granted. Allow it in Portal Settings."
        const val BACKGROUND_DENIED =
            "Location is allowed only while Portal is open. Choose \"Allow all the time\" in Portal Settings."
        const val LOCATION_OFF = "Location is turned off on the device."
        const val NO_FIX = "No location fix available."
    }
}

class AndroidLocationSource(private val context: Context) : LocationSource {
    private val manager: LocationManager? = context.getSystemService(LocationManager::class.java)

    override fun hasForegroundPermission(): Boolean =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    override fun hasBackgroundPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    override fun isLocationEnabled(): Boolean =
        manager?.let(LocationManagerCompat::isLocationEnabled) == true

    @SuppressLint("MissingPermission")
    override fun lastKnown(): List<LocationFix> {
        val manager = manager ?: return emptyList()
        return manager.getProviders(true).mapNotNull { provider ->
            manager.getLastKnownLocation(provider)?.toFix()
        }
    }

    /** Asks every enabled provider at once; the first fix wins. Fused alone stays off GPS by default. */
    @SuppressLint("MissingPermission")
    override fun current(timeoutMs: Long): LocationFix? {
        val manager = manager ?: return null
        val enabled = manager.getProviders(true)
        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        }.filter { it in enabled }
        if (providers.isEmpty()) return null
        val result = AtomicReference<Location?>()
        val done = CountDownLatch(1)
        val cancels = mutableListOf<CancellationSignal>()
        try {
            providers.forEach { provider ->
                val cancel = CancellationSignal().also(cancels::add)
                LocationManagerCompat.getCurrentLocation(
                    manager, provider, cancel, ContextCompat.getMainExecutor(context),
                ) { location ->
                    if (location != null && result.compareAndSet(null, location)) done.countDown()
                }
            }
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            cancels.forEach { it.cancel() }
        }
        return result.get()?.toFix()
    }

    override fun elapsedRealtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun Location.toFix() = LocationFix(latitude, longitude, elapsedRealtimeNanos)
}
