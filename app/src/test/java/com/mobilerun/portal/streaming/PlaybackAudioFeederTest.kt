package com.mobilerun.portal.streaming

import android.media.projection.MediaProjection
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.nio.ByteBuffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PlaybackAudioFeederTest {
    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun nextPaceDeadlineNs_startsOneBufferFromNow() {
        assertEquals(1_010_000_000L, PlaybackAudioFeeder.nextPaceDeadlineNs(1_000_000_000L, 0L))
    }

    @Test
    fun nextPaceDeadlineNs_advancesFromThePreviousDeadline() {
        // Keeps a steady 10 ms cadence even when a wake-up was a few ms late.
        assertEquals(1_020_000_000L, PlaybackAudioFeeder.nextPaceDeadlineNs(1_013_000_000L, 1_010_000_000L))
    }

    @Test
    fun nextPaceDeadlineNs_resyncsAfterAStall() {
        assertEquals(2_010_000_000L, PlaybackAudioFeeder.nextPaceDeadlineNs(2_000_000_000L, 1_010_000_000L))
    }

    @Test
    fun formatKey_distinguishesEncodingChannelsAndRate() {
        val base = PlaybackAudioFeeder.formatKey(2, 1, 48_000)
        assertEquals(base, PlaybackAudioFeeder.formatKey(2, 1, 48_000))
        assertNotEquals(base, PlaybackAudioFeeder.formatKey(2, 2, 48_000))
        assertNotEquals(base, PlaybackAudioFeeder.formatKey(4, 1, 48_000))
        assertNotEquals(base, PlaybackAudioFeeder.formatKey(2, 1, 44_100))
    }

    @Test
    fun failedSetupSendsSilenceAndRetriesOnlyAfterTheInterval() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        var now = 1_000_000_000L
        var attempts = 0
        val feeder = PlaybackAudioFeeder(hasPermission = { attempts++; false }, nowNs = { now })
        val buffer = ByteBuffer.allocateDirect(960)
        fun feed() = feeder.onBuffer(buffer, 2, 1, 48_000, 960, 0L)

        feeder.attach(mockk<MediaProjection>(relaxed = true))
        buffer.put(0, 7)
        feed()
        assertEquals(1, attempts)
        assertEquals(0, buffer.get(0).toInt())

        now += PlaybackAudioFeeder.RETRY_INTERVAL_NS - 1
        feed()
        assertEquals(1, attempts)

        now += 1
        feed()
        assertEquals(2, attempts)
        // One warning per failure streak.
        verify(exactly = 1) { Log.w("PlaybackAudioFeeder", any<String>()) }
    }

    @Test
    fun lateGrantStartsSetupAfterTheIntervalWithoutARelease() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        var now = 1_000_000_000L
        var granted = false
        var grantedChecks = 0
        val feeder = PlaybackAudioFeeder(
            hasPermission = { if (granted) grantedChecks++; granted },
            nowNs = { now },
        )
        val buffer = ByteBuffer.allocateDirect(960)
        fun feed() = feeder.onBuffer(buffer, 2, 1, 48_000, 960, 0L)

        feeder.attach(mockk<MediaProjection>(relaxed = true))
        feed()
        granted = true
        now += PlaybackAudioFeeder.RETRY_INTERVAL_NS - 1
        feed()
        assertEquals(0, grantedChecks)

        // Past the interval the granted check passes and setup goes on to AudioRecord.
        now += 1
        feed()
        assertEquals(1, grantedChecks)
    }

    @Test
    fun releaseNow_clearsTheBackoffAndStartsANewLogStreak() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        var attempts = 0
        val feeder = PlaybackAudioFeeder(hasPermission = { attempts++; true }, nowNs = { 1_000_000_000L })
        val buffer = ByteBuffer.allocateDirect(960)
        fun feed() = feeder.onBuffer(buffer, 2, 1, 48_000, 960, 0L)

        // The JVM android.jar stubs make every AudioRecord setup fail.
        feeder.attach(mockk<MediaProjection>(relaxed = true))
        feed()
        feed()
        assertEquals(1, attempts)
        verify(exactly = 1) { Log.w("PlaybackAudioFeeder", any<String>()) }

        feeder.releaseNow()
        feed()
        assertEquals(2, attempts)
        verify(exactly = 2) { Log.w("PlaybackAudioFeeder", any<String>()) }
    }

    @Test
    fun attachingANewProjectionRetriesAtOnce() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        var attempts = 0
        val feeder = PlaybackAudioFeeder(hasPermission = { attempts++; false }, nowNs = { 1_000_000_000L })
        val buffer = ByteBuffer.allocateDirect(960)
        fun feed() = feeder.onBuffer(buffer, 2, 1, 48_000, 960, 0L)

        feeder.attach(mockk<MediaProjection>(relaxed = true))
        feed()
        feeder.attach(mockk<MediaProjection>(relaxed = true))
        feed()
        assertEquals(2, attempts)
    }

    @Test
    fun disabledFeederSendsSilenceWithoutStartingCapture() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        val feeder = PlaybackAudioFeeder(isEnabled = { false })
        val buffer = ByteBuffer.allocateDirect(960)
        buffer.put(0, 7)

        feeder.attach(mockk<MediaProjection>(relaxed = true))
        feeder.onBuffer(buffer, 2, 1, 48_000, 960, 0L)

        // No AudioRecord was attempted (that would log a failure) and the buffer is zeroed.
        verify(exactly = 0) { Log.w("PlaybackAudioFeeder", any<String>()) }
        assertEquals(0, buffer.get(0).toInt())
    }
}
