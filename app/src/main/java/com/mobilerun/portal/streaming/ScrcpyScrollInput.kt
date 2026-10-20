package com.mobilerun.portal.streaming

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Scroll amounts are wheel ticks. The web clients follow scrcpy and send
 * ticks / 16 as i16 fixed point; positive v scrolls up, positive h scrolls left.
 */
internal data class ScrcpyScrollPacket(
    val x: Int,
    val y: Int,
    val videoWidth: Int,
    val videoHeight: Int,
    val hScrollTicks: Float,
    val vScrollTicks: Float,
)

internal object ScrcpyScrollPacketDecoder {
    const val MESSAGE_TYPE = 3
    private const val PACKET_SIZE = 21
    private const val TICKS_PER_UNIT = 16f

    fun decode(data: ByteArray): ScrcpyScrollPacket? {
        if (data.size < PACKET_SIZE || (data[0].toInt() and 0xFF) != MESSAGE_TYPE) {
            return null
        }

        val buffer = ByteBuffer.wrap(data)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.get()
        val x = buffer.int
        val y = buffer.int
        val videoWidth = buffer.short.toInt() and 0xFFFF
        val videoHeight = buffer.short.toInt() and 0xFFFF
        val hScroll = buffer.short
        val vScroll = buffer.short

        return ScrcpyScrollPacket(
            x = x,
            y = y,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            hScrollTicks = i16FixedPointToFloat(hScroll) * TICKS_PER_UNIT,
            vScrollTicks = i16FixedPointToFloat(vScroll) * TICKS_PER_UNIT,
        )
    }

    private fun i16FixedPointToFloat(value: Short): Float = value / 32768f
}

internal data class ScrollSwipe(
    val start: ScreenPoint,
    val end: ScreenPoint,
)

/**
 * Turns wheel ticks into a swipe that stays inside the screen and away from
 * the edges, where swipes open system gestures (shade, home, back).
 */
internal object ScrollSwipePlanner {
    const val EDGE_INSET_FRACTION = 0.1f

    fun plan(
        anchor: ScreenPoint,
        hScrollTicks: Float,
        vScrollTicks: Float,
        screenWidth: Int,
        screenHeight: Int,
        pixelsPerTick: Float,
        minDistancePx: Float,
    ): ScrollSwipe? {
        if (screenWidth <= 0 || screenHeight <= 0) return null

        val (startX, endX) = planAxis(anchor.x, hScrollTicks * pixelsPerTick, screenWidth)
        val (startY, endY) = planAxis(anchor.y, vScrollTicks * pixelsPerTick, screenHeight)
        if (max(abs(endX - startX), abs(endY - startY)) < minDistancePx) return null

        return ScrollSwipe(ScreenPoint(startX, startY), ScreenPoint(endX, endY))
    }

    private fun planAxis(anchor: Float, requestedDelta: Float, size: Int): Pair<Float, Float> {
        val low = size * EDGE_INSET_FRACTION
        val high = size * (1f - EDGE_INSET_FRACTION)
        val delta = requestedDelta.coerceIn(low - high, high - low)
        val start = anchor.coerceIn(low - min(delta, 0f), high - max(delta, 0f))
        return Pair(start, start + delta)
    }
}

/**
 * Collects wheel ticks between swipes. One wheel event per swipe is far too
 * many for a trackpad, and tiny swipes would land as taps.
 */
internal class ScrollTickAccumulator(
    private val idleResetMs: Long,
) {
    var anchor: ScreenPoint? = null
        private set
    var hScrollTicks = 0f
        private set
    var vScrollTicks = 0f
        private set

    private var lastAddMs = 0L

    fun add(point: ScreenPoint, hTicks: Float, vTicks: Float, nowMs: Long) {
        if (hTicks == 0f && vTicks == 0f) return
        if (anchor != null && nowMs - lastAddMs > idleResetMs) clear()

        anchor = point
        hScrollTicks += hTicks
        vScrollTicks += vTicks
        lastAddMs = nowMs
    }

    fun clear() {
        anchor = null
        hScrollTicks = 0f
        vScrollTicks = 0f
    }
}
