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
    fun releaseNow_rearmsPlaybackCaptureAfterAFailure() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        val feeder = PlaybackAudioFeeder()
        val buffer = ByteBuffer.allocateDirect(960)
        fun feed() = feeder.onBuffer(buffer, 2, 1, 48_000, 960, 0L)

        // The JVM android.jar stubs make every AudioRecord setup fail.
        feeder.attach(mockk<MediaProjection>(relaxed = true))
        feed()
        feed()
        verify(exactly = 1) { Log.w("PlaybackAudioFeeder", any<String>()) }

        feeder.releaseNow()
        feed()
        verify(exactly = 2) { Log.w("PlaybackAudioFeeder", any<String>()) }
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
