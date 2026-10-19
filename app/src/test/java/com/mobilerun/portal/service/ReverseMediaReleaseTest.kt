package com.mobilerun.portal.service

import com.mobilerun.portal.streaming.WebRtcManager.CaptureSessionMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ReverseMediaReleaseTest {
    @Test
    fun streamCaptureIsStoppedImmediately() {
        assertEquals(
            ReverseMediaRelease.STOP_CAPTURE,
            reverseMediaReleaseFor(
                captureServiceRunning = true,
                captureSessionMode = CaptureSessionMode.STREAM,
                streamActive = true,
            ),
        )
        assertEquals(
            ReverseMediaRelease.STOP_CAPTURE,
            reverseMediaReleaseFor(
                captureServiceRunning = true,
                captureSessionMode = CaptureSessionMode.STREAM,
                streamActive = false,
            ),
        )
    }

    @Test
    fun sharedScreenshotCaptureIsLeftToItsIdleStop() {
        assertEquals(
            ReverseMediaRelease.NONE,
            reverseMediaReleaseFor(
                captureServiceRunning = true,
                captureSessionMode = CaptureSessionMode.CAPTURE_ONLY,
                streamActive = false,
            ),
        )
    }

    @Test
    fun sessionsWithoutStreamCaptureAreStopped() {
        assertEquals(
            ReverseMediaRelease.STOP_SESSIONS,
            reverseMediaReleaseFor(
                captureServiceRunning = false,
                captureSessionMode = CaptureSessionMode.NONE,
                streamActive = true,
            ),
        )
        assertEquals(
            ReverseMediaRelease.STOP_SESSIONS,
            reverseMediaReleaseFor(
                captureServiceRunning = true,
                captureSessionMode = CaptureSessionMode.CAPTURE_ONLY,
                streamActive = true,
            ),
        )
    }

    @Test
    fun nothingToReleaseWhenIdle() {
        assertEquals(
            ReverseMediaRelease.NONE,
            reverseMediaReleaseFor(
                captureServiceRunning = false,
                captureSessionMode = CaptureSessionMode.NONE,
                streamActive = false,
            ),
        )
    }
}
