package com.mobilerun.portal.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestEventCoalescerTest {
    private var now = 0L
    private val tasks = mutableListOf<Pair<Long, () -> Unit>>()
    private val consumed = mutableListOf<Int>()
    private val discarded = mutableListOf<Int>()
    private fun queue(consume: (Int) -> Unit = { consumed += it }) = LatestEventCoalescer(
        nowMs = { now },
        schedule = { delay, task -> tasks += (now + delay) to task },
        consume = consume,
        discard = { value: Int -> discarded += value },
    )
    private fun runNext() {
        val (at, task) = tasks.removeAt(0)
        now = maxOf(now, at)
        task()
    }

    @Test fun burstKeepsOnlyLatestAndSpacesResolutionStarts() {
        val queue = queue()
        queue.offer(1)
        queue.offer(2)
        assertEquals(1, tasks.size)
        runNext()
        now = 100
        queue.offer(3)
        now = 200
        queue.offer(4)
        assertEquals(300L, tasks.single().first)
        runNext()
        assertEquals(listOf(2, 4), consumed)
        assertEquals(listOf(1, 2, 3, 4), discarded)
    }

    @Test fun slowResolutionBoundsQueueAndNeverRunsConsumersConcurrently() {
        lateinit var queue: LatestEventCoalescer<Int>
        queue = queue { value ->
            consumed += value
            if (value == 1) {
                queue.offer(2)
                queue.offer(3)
                assertTrue(tasks.isEmpty())
                now += 1000 // Simulate a blocked binder read; newest pending event survives.
            }
        }
        queue.offer(1)
        runNext()
        assertEquals(1000L, tasks.single().first)
        runNext()
        assertEquals(listOf(1, 3), consumed)
        assertEquals(listOf(2, 1, 3), discarded)
    }

    @Test fun closeDiscardsPendingAndRejectsLateEvents() {
        val queue = queue()
        queue.offer(1)
        queue.close()
        queue.offer(2)
        runNext()
        assertTrue(consumed.isEmpty())
        assertEquals(listOf(1, 2), discarded)
    }

    @Test fun exceptionReleasesValueAndAllowsTheNextEvent() {
        val queue = queue { value -> if (value == 1) error("binder failed") else consumed.add(value).let {} }
        queue.offer(1)
        try { runNext() } catch (_: IllegalStateException) { }
        queue.offer(2)
        runNext()
        assertEquals(listOf(2), consumed)
        assertEquals(listOf(1, 2), discarded)
    }
}
