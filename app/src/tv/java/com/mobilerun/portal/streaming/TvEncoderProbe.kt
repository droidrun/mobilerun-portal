package com.mobilerun.portal.streaming

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.SystemClock
import android.view.Surface
import java.util.concurrent.Executors

/** One native probe at a time; concurrent negotiations share its bounded result. */
internal object TvEncoderProbe {
    const val TIMEOUT_MS = 1500L
    enum class Result { OK, FAILED, TIMEOUT, STUCK, COOLDOWN }
    private class Waiter(val handler: Handler, val done: (Result) -> Unit)
    private class Flight(val startedAtMs: Long) {
        val waiters = mutableListOf<Waiter>()
    }
    private val lock = Any()
    private var inFlight: Flight? = null
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "TvEncoderProbe").apply { isDaemon = true }
    }

    fun request(width: Int, height: Int, fps: Int, callbackHandler: Handler, done: (Result) -> Unit) {
        val flight: Flight
        synchronized(lock) {
            val current = inFlight
            when (WebRtcManager.tvProbeAction(current?.startedAtMs, SystemClock.elapsedRealtime())) {
                WebRtcManager.Companion.TvProbeAction.JOIN -> {
                    current!!.waiters.add(Waiter(callbackHandler, done))
                    return
                }
                WebRtcManager.Companion.TvProbeAction.STUCK -> {
                    callbackHandler.post { done(Result.STUCK) }
                    return
                }
                WebRtcManager.Companion.TvProbeAction.START -> {
                    flight = Flight(SystemClock.elapsedRealtime())
                    flight.waiters.add(Waiter(callbackHandler, done))
                    inFlight = flight
                }
            }
        }
        val timeout = Runnable {
            val waiters = synchronized(lock) {
                if (inFlight !== flight) return@Runnable
                flight.waiters.toList().also { flight.waiters.clear() }
            }
            waiters.forEach { waiter -> waiter.handler.post { waiter.done(Result.TIMEOUT) } }
        }
        callbackHandler.postDelayed(timeout, TIMEOUT_MS)
        executor.execute {
            var codec: MediaCodec? = null
            var surface: Surface? = null
            var started = false
            var result = Result.FAILED
            try {
                val name = TvStreamPolicy.selectedEncoderName
                if (name != null) {
                    codec = MediaCodec.createByCodecName(name)
                    val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                        setInteger(MediaFormat.KEY_BIT_RATE, 1_000_000)
                        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                    }
                    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    surface = codec.createInputSurface()
                    codec.start()
                    started = true
                    result = Result.OK
                }
            } catch (_: Exception) {
                // Vendor error text may include addresses; only the outcome is logged by the caller.
            } finally {
                try {
                    if (started) codec?.stop()
                } catch (_: Exception) {
                    result = Result.FAILED
                }
                try {
                    codec?.release()
                } catch (_: Exception) {
                    result = Result.FAILED
                }
                try {
                    surface?.release()
                } catch (_: Exception) {
                    result = Result.FAILED
                }
            }
            val waiters: List<Waiter>
            val deliveredResult: Result
            synchronized(lock) {
                deliveredResult = if (WebRtcManager.tvProbeAction(flight.startedAtMs, SystemClock.elapsedRealtime()) ==
                    WebRtcManager.Companion.TvProbeAction.STUCK) Result.TIMEOUT else result
                waiters = flight.waiters.toList()
                flight.waiters.clear()
                // Keep the flight through stop/release, even after a timeout emptied its waiters.
                if (inFlight === flight) inFlight = null
            }
            callbackHandler.removeCallbacks(timeout)
            waiters.forEach { waiter -> waiter.handler.post { waiter.done(deliveredResult) } }
        }
    }
}
