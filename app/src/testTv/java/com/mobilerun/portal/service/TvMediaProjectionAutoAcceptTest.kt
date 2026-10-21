package com.mobilerun.portal.service

import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvMediaProjectionAutoAcceptTest {
    private val label = "Mobilerun Portal TV"
    private val positive = node("Jetzt starten").also {
        every { it.isEnabled } returns true
        every { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) } returns true
    }
    private val title = node("Aufnahme oder Streaming mit „$label“?")
    private val message = node("$label kann während der Aufnahme auf alle Informationen zugreifen.")
    private val negative = node("Abbrechen")
    private val root = mockk<AccessibilityNodeInfo> {
        every { packageName } returns "com.android.systemui"
        every { findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        every { findAccessibilityNodeInfosByViewId("com.android.systemui:id/dialog_title") } returns listOf(title)
        every { findAccessibilityNodeInfosByViewId("android:id/message") } returns listOf(message)
        every { findAccessibilityNodeInfosByViewId("android:id/button1") } returns listOf(positive)
        every { findAccessibilityNodeInfosByViewId("android:id/button2") } returns listOf(negative)
    }

    private fun node(textValue: String) = mockk<AccessibilityNodeInfo> {
        every { text } returns textValue
        every { recycle() } just runs
    }

    @Test fun alertDialogRootAcceptsWithoutActivityClassOrMatchingEventWindow() {
        every { root.className } returns "android.app.AlertDialog"
        // No event class or window ID is an input to recognition.
        assertTrue(TvMediaProjectionAutoAccept.tryAccept(root, label, armed = true))
        verify(exactly = 1) { positive.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        verify(exactly = 0) { negative.performAction(any()) }
        verify(exactly = 0) { root.findAccessibilityNodeInfosByViewId("android:id/alertTitle") }
        listOf(title, message, positive).forEach { verify(exactly = 1) { it.recycle() } }
    }

    @Test fun messageAloneCanIdentifyTheActualAppLabel() {
        every { title.text } returns "Bildschirm teilen"
        assertTrue(TvMediaProjectionAutoAccept.tryAccept(root, label, armed = true))
    }

    @Test fun titleAloneCanIdentifyTheActualAppLabel() {
        every { root.findAccessibilityNodeInfosByViewId("android:id/message") } returns emptyList()
        assertTrue(TvMediaProjectionAutoAccept.tryAccept(root, label, armed = true))
    }

    @Test fun otherAppLabelOrMissingPositiveNeverClicks() {
        assertFalse(TvMediaProjectionAutoAccept.tryAccept(root, "Another app", armed = true))
        every { root.findAccessibilityNodeInfosByViewId("android:id/button1") } returns emptyList()
        assertFalse(TvMediaProjectionAutoAccept.tryAccept(root, label, armed = true))
        verify(exactly = 0) { positive.performAction(any()) }
    }

    @Test fun wrongPackageNeverClicks() {
        every { root.packageName } returns "other.app"
        assertFalse(TvMediaProjectionAutoAccept.tryAccept(root, label, armed = true))
        verify(exactly = 0) { positive.performAction(any()) }
    }

    @Test fun emptyLabelAndDisabledButtonNeverClick() {
        assertFalse(TvMediaProjectionAutoAccept.tryAccept(root, "", armed = true))
        every { positive.isEnabled } returns false
        assertFalse(TvMediaProjectionAutoAccept.tryAccept(root, label, armed = true))
        verify(exactly = 0) { positive.performAction(any()) }
    }

    @Test fun disarmedGateNeverClicksOrTraversesTheRoot() {
        assertFalse(TvMediaProjectionAutoAccept.tryAccept(root, label, armed = false))
        verify(exactly = 0) { root.findAccessibilityNodeInfosByViewId(any()) }
        verify(exactly = 0) { positive.performAction(any()) }
    }

    @Test fun laterSystemUiEventCanRetryAfterADeclinedRoot() {
        every { root.findAccessibilityNodeInfosByViewId("android:id/button1") } returns emptyList()
        assertFalse(TvMediaProjectionAutoAccept.tryAccept(root, label, armed = true))
        every { root.findAccessibilityNodeInfosByViewId("android:id/button1") } returns listOf(positive)
        assertTrue(TvMediaProjectionAutoAccept.tryAccept(root, label, armed = true))
        verify(exactly = 1) { positive.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    @Test fun staticFireOsDialogCannotEnterTheLegacyFallback() {
        assertFalse(TvMediaProjectionAutoAccept.hasShareModeUi(root))
    }

    @Test fun sharedHookConstructorIsAvailableInTvOnly() {
        TvAccessibilityEvents::class.java.getConstructor(
            MobilerunAccessibilityService::class.java, java.util.function.BiConsumer::class.java, Runnable::class.java,
        )
    }
}
