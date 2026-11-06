package com.mobilerun.portal.location

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class LocationReaderTest {
    private class FakeSource(
        var foreground: Boolean = true,
        var background: Boolean = true,
        var enabled: Boolean = true,
        var last: List<LocationFix> = emptyList(),
        var currentFix: LocationFix? = null,
        var now: Long = TimeUnit.MINUTES.toNanos(60),
        var throwOnRead: Boolean = false,
    ) : LocationSource {
        var currentCalls = 0
        var onCurrent: () -> Unit = {}
        override fun hasForegroundPermission() = foreground
        override fun hasBackgroundPermission() = background
        override fun isLocationEnabled() = enabled
        override fun lastKnown(): List<LocationFix> {
            if (throwOnRead) throw SecurityException("revoked")
            return last
        }
        override fun current(timeoutMs: Long): LocationFix? {
            currentCalls++
            onCurrent()
            return currentFix
        }
        override fun elapsedRealtimeNanos() = now
    }

    private fun minutesAgo(source: FakeSource, minutes: Long) =
        source.now - TimeUnit.MINUTES.toNanos(minutes)

    @Test
    fun read_withoutPermission_isDenied() {
        val reading = LocationReader(FakeSource(foreground = false)) { true }.read()
        assertEquals(LocationReading.Unavailable(LocationReader.PERMISSION_DENIED), reading)
    }

    @Test
    fun read_withoutAllTheTime_asksForIt() {
        val reading = LocationReader(FakeSource(background = false)) { true }.read()
        assertEquals(LocationReading.Unavailable(LocationReader.BACKGROUND_DENIED), reading)
    }

    @Test
    fun read_withLocationOff_saysSo() {
        val reading = LocationReader(FakeSource(enabled = false)) { true }.read()
        assertEquals(LocationReading.Unavailable(LocationReader.LOCATION_OFF), reading)
    }

    @Test
    fun read_freshLastKnown_isUsedWithoutWaitingForAFix() {
        val source = FakeSource()
        source.last = listOf(
            LocationFix(1.0, 2.0, minutesAgo(source, 30)),
            LocationFix(40.4, 49.8, minutesAgo(source, 1)),
        )

        val reading = LocationReader(source) { true }.read()

        assertEquals(LocationReading.Fix(40.4, 49.8), reading)
        assertEquals(0, source.currentCalls)
    }

    @Test
    fun read_staleLastKnown_asksForACurrentFix() {
        val source = FakeSource(currentFix = LocationFix(40.4, 49.8, 0))
        source.last = listOf(LocationFix(1.0, 2.0, minutesAgo(source, 30)))

        assertEquals(LocationReading.Fix(40.4, 49.8), LocationReader(source) { true }.read())
        assertEquals(1, source.currentCalls)
    }

    @Test
    fun read_noCurrentFix_fallsBackToARecentOne() {
        val source = FakeSource()
        source.last = listOf(LocationFix(1.0, 2.0, minutesAgo(source, 10)))

        assertEquals(LocationReading.Fix(1.0, 2.0), LocationReader(source) { true }.read())
    }

    @Test
    fun read_noCurrentFix_neverReturnsAnOldOne() {
        val source = FakeSource()
        source.last = listOf(LocationFix(1.0, 2.0, minutesAgo(source, 90)))

        assertEquals(LocationReading.Unavailable(LocationReader.NO_FIX), LocationReader(source) { true }.read())
    }

    @Test
    fun read_sharingOff_isRefusedEvenWithPermissions() {
        val source = FakeSource(last = listOf(LocationFix(1.0, 2.0, TimeUnit.MINUTES.toNanos(60))))

        assertEquals(LocationReading.Unavailable(LocationReader.SHARING_OFF), LocationReader(source) { false }.read())
        assertEquals(0, source.currentCalls)
    }

    @Test
    fun read_sharingTurnedOffWhileWaiting_withholdsTheFix() {
        var sharing = true
        val source = FakeSource(currentFix = LocationFix(40.4, 49.8, 0))
        source.onCurrent = { sharing = false }

        assertEquals(LocationReading.Unavailable(LocationReader.SHARING_OFF), LocationReader(source) { sharing }.read())
    }

    @Test
    fun read_noFixAtAll_isUnavailable() {
        val reading = LocationReader(FakeSource()) { true }.read()
        assertEquals(LocationReading.Unavailable(LocationReader.NO_FIX), reading)
    }

    @Test
    fun read_permissionRevokedMidRead_isDenied() {
        val reading = LocationReader(FakeSource(throwOnRead = true)) { true }.read()
        assertEquals(LocationReading.Unavailable(LocationReader.PERMISSION_DENIED), reading)
    }
}
