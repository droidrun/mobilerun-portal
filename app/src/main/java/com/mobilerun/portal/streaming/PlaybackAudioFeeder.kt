package com.mobilerun.portal.streaming

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.LockSupport
import org.webrtc.audio.JavaAudioDeviceModule

// Overwrites WebRTC's 10 ms record buffers with playback capture from the stream's MediaProjection.
// Without a projection or on any failure the buffers stay silent; the microphone is never used.
@RequiresApi(29)
class PlaybackAudioFeeder(
    private val isEnabled: () -> Boolean = { true },
) : JavaAudioDeviceModule.AudioBufferCallback {

    companion object {
        private const val TAG = "PlaybackAudioFeeder"
        private const val BUFFER_INTERVAL_NS = 10_000_000L
        private const val PACE_RESYNC_NS = 50_000_000L

        // Next 10 ms tick for silent buffers; resyncs to now after a stall instead of bursting.
        internal fun nextPaceDeadlineNs(nowNs: Long, previousDeadlineNs: Long): Long {
            val base =
                if (previousDeadlineNs == 0L || nowNs - previousDeadlineNs > PACE_RESYNC_NS) nowNs
                else previousDeadlineNs
            return base + BUFFER_INTERVAL_NS
        }

        internal fun formatKey(encoding: Int, channelCount: Int, sampleRate: Int): Long =
            (encoding.toLong() shl 40) or (channelCount.toLong() shl 32) or sampleRate.toLong()
    }

    private val lock = Any()

    // Guarded by lock.
    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var recordProjection: MediaProjection? = null
    private var recordFormat = 0L
    private var readBuffer: ByteBuffer? = null
    private var failed = false

    // Audio thread only.
    private var zeros = ByteArray(0)
    private var nextTickNs = 0L

    fun attach(mediaProjection: MediaProjection) {
        synchronized(lock) {
            if (projection === mediaProjection) return
            projection = mediaProjection
            failed = false
            stopRecordLocked()
        }
    }

    fun detach(mediaProjection: MediaProjection) {
        synchronized(lock) {
            if (projection !== mediaProjection) return
            projection = null
            stopRecordLocked()
        }
    }

    // Also clears the failure latch, so the next recording start retries playback capture once.
    fun releaseNow() {
        synchronized(lock) {
            failed = false
            closeLocked()
        }
    }

    override fun onBuffer(
        buffer: ByteBuffer,
        audioFormat: Int,
        channelCount: Int,
        sampleRate: Int,
        bytesRead: Int,
        captureTimeNs: Long,
    ): Long {
        val capacity = buffer.capacity()
        val enabled = isEnabled()
        val rec: AudioRecord?
        val src: ByteBuffer?
        synchronized(lock) {
            rec = ensureRecordLocked(audioFormat, channelCount, sampleRate, capacity, enabled)
            src = readBuffer
        }
        var read = 0
        if (rec != null && src != null) {
            src.clear()
            read = rec.read(src, capacity, AudioRecord.READ_BLOCKING)
            // A record stopped by attach/detach is rebuilt or closed on the next buffer instead.
            if (read < 0) synchronized(lock) {
                if (record === rec && recordProjection === projection) failLocked("read error $read")
            }
        }
        buffer.clear()
        if (read > 0 && src != null) {
            src.limit(read)
            buffer.put(src)
        }
        if (zeros.size < buffer.remaining()) zeros = ByteArray(capacity)
        buffer.put(zeros, 0, buffer.remaining())
        if (read > 0) nextTickNs = 0L else pace()
        return 0L
    }

    // Without an AudioRecord the WebRTC record loop does not sleep, so silent buffers are paced here.
    private fun pace() {
        nextTickNs = nextPaceDeadlineNs(System.nanoTime(), nextTickNs)
        val waitNs = nextTickNs - System.nanoTime()
        if (waitNs > 0) LockSupport.parkNanos(waitNs)
    }

    @SuppressLint("MissingPermission")
    private fun ensureRecordLocked(
        encoding: Int,
        channelCount: Int,
        sampleRate: Int,
        capacity: Int,
        enabled: Boolean,
    ): AudioRecord? {
        val current = projection
        val format = formatKey(encoding, channelCount, sampleRate)
        // Turning Device Audio off mid-stream closes the capture; the session keeps sending silence.
        record?.let {
            if (enabled && current != null && current === recordProjection && format == recordFormat) return it
            closeLocked()
        }
        if (!enabled || current == null || failed) return null
        return try {
            val mask = if (channelCount == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val config =
                AudioPlaybackCaptureConfiguration.Builder(current)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
            val created =
                AudioRecord.Builder()
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(sampleRate)
                            .setChannelMask(mask)
                            .build(),
                    )
                    .setAudioPlaybackCaptureConfig(config)
                    .setBufferSizeInBytes(maxOf(2 * AudioRecord.getMinBufferSize(sampleRate, mask, encoding), 4 * capacity))
                    .build()
            created.startRecording()
            if (created.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                created.release()
                failLocked("AudioRecord did not start")
                return null
            }
            record = created
            recordProjection = current
            recordFormat = format
            readBuffer = ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder())
            Log.i(TAG, "Playback capture started: ${sampleRate}Hz channels=$channelCount")
            created
        } catch (t: Throwable) {
            failLocked(t.message ?: t.javaClass.simpleName)
            null
        }
    }

    private fun failLocked(reason: String) {
        if (!failed) Log.w(TAG, "Playback capture unavailable, sending silence: $reason")
        failed = true
        closeLocked()
    }

    // stop() unblocks a pending read; the audio thread releases the record on its next buffer.
    private fun stopRecordLocked() {
        record?.let { runCatching { it.stop() } }
    }

    private fun closeLocked() {
        record?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        record = null
        recordProjection = null
        readBuffer = null
    }
}
