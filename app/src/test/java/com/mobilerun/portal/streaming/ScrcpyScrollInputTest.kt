package com.mobilerun.portal.streaming

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrcpyScrollInputTest {
    @Test
    fun decoder_readsWebClientFixedPointAsTicks() {
        // Web clients encode round(ticks / 16 * 0x8000).
        val packet =
            ScrcpyScrollPacketDecoder.decode(
                scrollPacket(x = 360, y = 640, videoWidth = 720, videoHeight = 1_280, hScroll = 2_048, vScroll = -82),
            )

        assertNotNull(packet)
        assertEquals(360, packet?.x)
        assertEquals(640, packet?.y)
        assertEquals(720, packet?.videoWidth)
        assertEquals(1_280, packet?.videoHeight)
        assertEquals(1f, packet!!.hScrollTicks, 1e-4f)
        assertEquals(-0.04f, packet.vScrollTicks, 1e-3f)
    }

    @Test
    fun decoder_coversFullTickRangeInBothDirections() {
        val down = ScrcpyScrollPacketDecoder.decode(scrollPacket(vScroll = Short.MIN_VALUE.toInt()))
        val up = ScrcpyScrollPacketDecoder.decode(scrollPacket(vScroll = Short.MAX_VALUE.toInt()))

        assertEquals(-16f, down!!.vScrollTicks, 1e-3f)
        assertEquals(16f, up!!.vScrollTicks, 1e-3f)
    }

    @Test
    fun decoder_rejectsWrongMessageTypeAndTruncatedPackets() {
        val wrongType = scrollPacket()
        wrongType[0] = ScrcpyTouchPacketDecoder.MESSAGE_TYPE.toByte()

        assertNull(ScrcpyScrollPacketDecoder.decode(wrongType))
        assertNull(ScrcpyScrollPacketDecoder.decode(scrollPacket().copyOf(20)))
    }

    @Test
    fun planner_scrollDownSwipesUpwardInsideScreen() {
        // Pointer near the top: the swipe moves down so it does not leave the screen.
        val swipe = plan(anchor = ScreenPoint(540f, 50f), vTicks = -3f)

        assertNotNull(swipe)
        assertTrue(swipe!!.end.y < swipe.start.y)
        assertEquals(300f, swipe.start.y - swipe.end.y, 1e-3f)
        assertInsideBand(swipe)
    }

    @Test
    fun planner_scrollUpSwipesDownwardInsideScreen() {
        val swipe = plan(anchor = ScreenPoint(540f, 2_350f), vTicks = 3f)

        assertNotNull(swipe)
        assertTrue(swipe!!.end.y > swipe.start.y)
        assertEquals(300f, swipe.end.y - swipe.start.y, 1e-3f)
        assertInsideBand(swipe)
    }

    @Test
    fun planner_capsLongScrollsToTheSafeBand() {
        val down = plan(anchor = ScreenPoint(540f, 1_200f), vTicks = -30f)
        val right = plan(anchor = ScreenPoint(540f, 1_200f), hTicks = -16f)

        assertEquals(1_920f, down!!.start.y - down.end.y, 1e-3f)
        assertEquals(864f, right!!.start.x - right.end.x, 1e-3f)
        assertInsideBand(down)
        assertInsideBand(right)
    }

    @Test
    fun planner_verticalScrollKeepsPointerColumnNearSideEdge() {
        val swipe = plan(anchor = ScreenPoint(50f, 1_200f), vTicks = -3f)

        assertEquals(50f, swipe!!.start.x, 0f)
        assertEquals(50f, swipe.end.x, 0f)
    }

    @Test
    fun planner_verticalScrollIgnoresHorizontalJitterNearSideEdge() {
        // 0.3 ticks = 30 px, under the 40 px minimum swipe distance.
        val swipe = plan(anchor = ScreenPoint(50f, 1_200f), hTicks = 0.3f, vTicks = -3f)

        assertEquals(50f, swipe!!.start.x, 0f)
        assertEquals(50f, swipe.end.x, 0f)
    }

    @Test
    fun planner_horizontalScrollMovesOffBottomGestureBar() {
        val swipe = plan(anchor = ScreenPoint(540f, 2_390f), hTicks = 3f)

        assertEquals(2_160f, swipe!!.start.y, 1e-3f)
        assertEquals(swipe.start.y, swipe.end.y, 0f)
        assertInsideBand(swipe)
    }

    @Test
    fun planner_keepsTinyScrollsPendingInsteadOfTapping() {
        assertNull(plan(anchor = ScreenPoint(540f, 1_200f), vTicks = 0.3f))
        assertNull(plan(anchor = ScreenPoint(540f, 1_200f), vTicks = 0f))
    }

    @Test
    fun accumulator_sumsTicksAndFollowsLatestPointer() {
        val ticks = ScrollTickAccumulator(idleResetMs = 500L)

        ticks.add(ScreenPoint(10f, 20f), 0f, -0.25f, nowMs = 0L)
        ticks.add(ScreenPoint(30f, 40f), 0.5f, -0.25f, nowMs = 100L)
        ticks.add(ScreenPoint(50f, 60f), 0f, 0f, nowMs = 150L)

        assertEquals(ScreenPoint(30f, 40f), ticks.anchor)
        assertEquals(0.5f, ticks.hScrollTicks, 1e-6f)
        assertEquals(-0.5f, ticks.vScrollTicks, 1e-6f)
    }

    @Test
    fun accumulator_dropsStaleRemainderAfterIdle() {
        val ticks = ScrollTickAccumulator(idleResetMs = 500L)

        ticks.add(ScreenPoint(10f, 20f), 0f, -0.1f, nowMs = 0L)
        ticks.add(ScreenPoint(10f, 20f), 0f, 1f, nowMs = 600L)

        assertEquals(1f, ticks.vScrollTicks, 1e-6f)

        ticks.clear()
        assertNull(ticks.anchor)
        assertEquals(0f, ticks.vScrollTicks, 0f)
    }

    private fun plan(
        anchor: ScreenPoint,
        hTicks: Float = 0f,
        vTicks: Float = 0f,
    ): ScrollSwipe? =
        ScrollSwipePlanner.plan(
            anchor,
            hTicks,
            vTicks,
            screenWidth = SCREEN_WIDTH,
            screenHeight = SCREEN_HEIGHT,
            pixelsPerTick = 100f,
            minDistancePx = 40f,
        )

    private fun assertInsideBand(swipe: ScrollSwipe) {
        val inset = ScrollSwipePlanner.EDGE_INSET_FRACTION
        for (point in listOf(swipe.start, swipe.end)) {
            assertTrue("x=${point.x}", point.x >= SCREEN_WIDTH * inset && point.x <= SCREEN_WIDTH * (1 - inset))
            assertTrue("y=${point.y}", point.y >= SCREEN_HEIGHT * inset && point.y <= SCREEN_HEIGHT * (1 - inset))
        }
    }

    private fun scrollPacket(
        x: Int = 0,
        y: Int = 0,
        videoWidth: Int = 720,
        videoHeight: Int = 1_280,
        hScroll: Int = 0,
        vScroll: Int = 0,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(21).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(ScrcpyScrollPacketDecoder.MESSAGE_TYPE.toByte())
        buffer.putInt(x)
        buffer.putInt(y)
        buffer.putShort(videoWidth.toShort())
        buffer.putShort(videoHeight.toShort())
        buffer.putShort(hScroll.toShort())
        buffer.putShort(vScroll.toShort())
        buffer.putInt(0)
        return buffer.array()
    }

    private companion object {
        const val SCREEN_WIDTH = 1_080
        const val SCREEN_HEIGHT = 2_400
    }
}
