package com.mobilerun.portal.service

import android.view.accessibility.AccessibilityEvent
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvForegroundActivityTrackerTest {
    private fun event(type: Int, pkg: String, cls: String) = mockk<AccessibilityEvent> {
        every { eventType } returns type
        every { packageName } returns pkg
        every { className } returns cls
    }

    @Test fun windowsChangedWithinCoalescingWindowKeepsTheStateEventActivity() {
        val tracker = TvForegroundActivityTracker()
        val tasks = mutableListOf<() -> Unit>()
        val applied = mutableListOf<TvForegroundActivityTracker.Candidate>()
        val queue = LatestEventCoalescer<AccessibilityEvent>(
            nowMs = { 0 },
            schedule = { _, task -> tasks.add(task); Unit },
            consume = { tracker.matching("tv.app")?.let(applied::add); Unit },
        )
        val state = event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "tv.app", "tv.app.Player")
        val windows = event(AccessibilityEvent.TYPE_WINDOWS_CHANGED, "com.android.systemui", "android.view.View")
        tracker.onEvent(state)
        queue.offer(state)
        tracker.onEvent(windows)
        queue.offer(windows) // The worker resolves only this latest event, not the state event.
        tasks.single().invoke()
        assertEquals(listOf(TvForegroundActivityTracker.Candidate("tv.app", "tv.app.Player")), applied)
    }

    @Test fun androidClassDoesNotEraseTheLastApplicationActivity() {
        val tracker = TvForegroundActivityTracker()
        tracker.onEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "tv.app", "tv.app.Player"))
        tracker.onEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.android.systemui", "android.app.AlertDialog"))
        assertEquals("tv.app.Player", tracker.matching("tv.app")?.className)
        assertNull(tracker.matching("com.android.systemui"))
    }

    @Test fun onlyTheLatestCandidateMatchingConfirmedForegroundCanBeApplied() {
        val tracker = TvForegroundActivityTracker()
        tracker.onEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "tv.first", "tv.first.Player"))
        tracker.onEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "tv.second", "tv.second.Home"))
        assertNull(tracker.matching("tv.first"))
        assertNull(tracker.matching(null))
        assertEquals("tv.second.Home", tracker.matching("tv.second")?.className)
    }
}
