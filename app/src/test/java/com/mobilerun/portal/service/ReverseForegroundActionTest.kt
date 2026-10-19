package com.mobilerun.portal.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ReverseForegroundActionTest {
    @Test
    fun startWhileStreamingAnswersThenReleasesForeground() {
        assertEquals(
            ReverseForegroundAction.START_THEN_RELEASE,
            reverseForegroundActionFor(isForeground = false, suppressedForStreaming = true),
        )
    }

    @Test
    fun startWithoutStreamingGoesForeground() {
        assertEquals(
            ReverseForegroundAction.START,
            reverseForegroundActionFor(isForeground = false, suppressedForStreaming = false),
        )
    }

    @Test
    fun alreadyForegroundNeedsNothing() {
        assertEquals(
            ReverseForegroundAction.NONE,
            reverseForegroundActionFor(isForeground = true, suppressedForStreaming = false),
        )
        assertEquals(
            ReverseForegroundAction.NONE,
            reverseForegroundActionFor(isForeground = true, suppressedForStreaming = true),
        )
    }
}
