package com.mobilerun.portal.service

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecordAudioAutoAcceptTest {
    private companion object {
        const val CONTROLLER = "com.google.android.permissioncontroller"
        const val LABEL = "Mobilerun Portal"
        const val PROMPT = "Allow Mobilerun Portal to record audio?"
    }

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        AutoAcceptGate.disarmRecordAudio()
        unmockkAll()
    }

    private fun target(
        sdk: Int = 34,
        packageName: String = CONTROLLER,
        message: String? = PROMPT,
        appLabel: String = LABEL,
        hasForegroundOnlyButton: Boolean = true,
        hasAllowButton: Boolean = false,
    ) = RecordAudioAutoAccept.chooseTarget(sdk, packageName, message, appLabel, hasForegroundOnlyButton, hasAllowButton)

    private fun node(
        viewId: String? = null,
        text: String? = null,
        contentDescription: String? = null,
        packageName: String = CONTROLLER,
        clickable: Boolean = true,
        enabled: Boolean = true,
        visible: Boolean = true,
        children: List<AccessibilityNodeInfo> = emptyList(),
    ): AccessibilityNodeInfo {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.viewIdResourceName } returns viewId
        every { node.text } returns text
        every { node.contentDescription } returns contentDescription
        every { node.packageName } returns packageName
        every { node.isClickable } returns clickable
        every { node.isEnabled } returns enabled
        every { node.isVisibleToUser } returns visible
        every { node.childCount } returns children.size
        children.forEachIndexed { i, child -> every { node.getChild(i) } returns child }
        every { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) } returns true
        return node
    }

    private fun button(
        id: String,
        clickable: Boolean = true,
        enabled: Boolean = true,
        visible: Boolean = true,
    ) = node(viewId = "$CONTROLLER:id/$id", clickable = clickable, enabled = enabled, visible = visible)

    private fun dialog(
        vararg buttons: AccessibilityNodeInfo,
        message: AccessibilityNodeInfo = node(viewId = "$CONTROLLER:id/permission_message", text = PROMPT),
        packageName: String = CONTROLLER,
    ) = node(packageName = packageName, children = listOf(message) + buttons)

    private fun assertNotClicked(vararg nodes: AccessibilityNodeInfo) {
        nodes.forEach { verify(exactly = 0) { it.performAction(any()) } }
    }

    @Test
    fun chooseTarget_prefersWhileUsingTheApp() {
        assertEquals(RecordAudioAutoAccept.Target.FOREGROUND_ONLY, target())
        assertEquals(RecordAudioAutoAccept.Target.FOREGROUND_ONLY, target(hasAllowButton = true))
        assertEquals(RecordAudioAutoAccept.Target.FOREGROUND_ONLY, target(sdk = 29, hasAllowButton = true))
        assertEquals(
            RecordAudioAutoAccept.Target.FOREGROUND_ONLY,
            target(packageName = "com.android.permissioncontroller"),
        )
    }

    @Test
    fun chooseTarget_fallsBackToAllowOnlyOnAndroid10() {
        assertEquals(RecordAudioAutoAccept.Target.ALLOW, target(sdk = 29, hasForegroundOnlyButton = false, hasAllowButton = true))
        assertEquals(RecordAudioAutoAccept.Target.NONE, target(sdk = 30, hasForegroundOnlyButton = false, hasAllowButton = true))
        assertEquals(RecordAudioAutoAccept.Target.NONE, target(sdk = 34, hasForegroundOnlyButton = false, hasAllowButton = true))
    }

    @Test
    fun chooseTarget_waitsWhenNoAllowButtonIsShown() {
        // Only "Only this time" / "Don't allow" visible: never click them.
        assertEquals(RecordAudioAutoAccept.Target.NONE, target(hasForegroundOnlyButton = false))
        assertEquals(RecordAudioAutoAccept.Target.NONE, target(sdk = 29, hasForegroundOnlyButton = false))
    }

    @Test
    fun chooseTarget_ignoresOtherAppsPrompts() {
        assertEquals(RecordAudioAutoAccept.Target.NONE, target(message = "Allow Recorder to record audio?"))
        assertEquals(RecordAudioAutoAccept.Target.NONE, target(message = null))
        assertEquals(RecordAudioAutoAccept.Target.NONE, target(appLabel = ""))
        assertEquals(RecordAudioAutoAccept.Target.NONE, target(appLabel = "  "))
    }

    @Test
    fun chooseTarget_ignoresDialogsOutsideThePermissionController() {
        assertEquals(RecordAudioAutoAccept.Target.NONE, target(packageName = "com.android.systemui"))
        assertEquals(RecordAudioAutoAccept.Target.NONE, target(packageName = "com.example.fake"))
    }

    @Test
    fun chooseTarget_matchesTheLabelExactly() {
        assertEquals(RecordAudioAutoAccept.Target.NONE, target(message = "Allow MOBILERUN PORTAL to record audio?"))
    }

    @Test
    fun tryAutoAccept_clicksOnlyWhileUsingTheApp() {
        val whileUsing = button("permission_allow_foreground_only_button")
        val oneTime = button("permission_allow_one_time_button")
        val deny = button("permission_deny_button")
        val allow = button("permission_allow_button")
        AutoAcceptGate.armRecordAudio()

        val result = RecordAudioAutoAccept.tryAutoAccept(dialog(whileUsing, oneTime, deny, allow), LABEL, sdk = 34)

        assertEquals(RecordAudioAutoAccept.AutoAcceptResult.ActionPerformed, result)
        verify(exactly = 1) { whileUsing.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        assertNotClicked(oneTime, deny, allow)
        assertFalse(AutoAcceptGate.isRecordAudioArmed())
    }

    @Test
    fun tryAutoAccept_neverClicksOnlyThisTimeOrDontAllow() {
        for (sdk in listOf(29, 34)) {
            val oneTime = button("permission_allow_one_time_button")
            val deny = button("permission_deny_button")

            val result = RecordAudioAutoAccept.tryAutoAccept(dialog(oneTime, deny), LABEL, sdk = sdk)

            assertEquals(RecordAudioAutoAccept.AutoAcceptResult.NoAction, result)
            assertNotClicked(oneTime, deny)
        }
    }

    @Test
    fun tryAutoAccept_clicksAllowOnlyOnAndroid10() {
        val allow10 = button("permission_allow_button")
        assertEquals(
            RecordAudioAutoAccept.AutoAcceptResult.ActionPerformed,
            RecordAudioAutoAccept.tryAutoAccept(dialog(allow10, button("permission_deny_button")), LABEL, sdk = 29),
        )
        verify(exactly = 1) { allow10.performAction(AccessibilityNodeInfo.ACTION_CLICK) }

        for (sdk in listOf(30, 34)) {
            val allow = button("permission_allow_button")
            assertEquals(
                RecordAudioAutoAccept.AutoAcceptResult.NoAction,
                RecordAudioAutoAccept.tryAutoAccept(dialog(allow, button("permission_deny_button")), LABEL, sdk = sdk),
            )
            assertNotClicked(allow)
        }
    }

    @Test
    fun tryAutoAccept_buttonStillAnimatingIn_isNotReadyThenClicked() {
        val whileUsing = button("permission_allow_foreground_only_button")
        every { whileUsing.isVisibleToUser } returnsMany listOf(false, true)
        val dialog = dialog(whileUsing)
        AutoAcceptGate.armRecordAudio()

        assertEquals(
            RecordAudioAutoAccept.AutoAcceptResult.NotReady,
            RecordAudioAutoAccept.tryAutoAccept(dialog, LABEL, sdk = 34),
        )
        assertNotClicked(whileUsing)
        assertEquals(
            RecordAudioAutoAccept.AutoAcceptResult.ActionPerformed,
            RecordAudioAutoAccept.tryAutoAccept(dialog, LABEL, sdk = 34),
        )
        verify(exactly = 1) { whileUsing.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    @Test
    fun tryAutoAccept_unusableWhileUsingTheAppNeverFallsBackToAllow() {
        val variants = listOf(
            button("permission_allow_foreground_only_button", visible = false),
            button("permission_allow_foreground_only_button", enabled = false),
            button("permission_allow_foreground_only_button", clickable = false),
        )
        for (whileUsing in variants) {
            for (sdk in listOf(29, 34)) {
                val allow = button("permission_allow_button")

                val result = RecordAudioAutoAccept.tryAutoAccept(dialog(whileUsing, allow), LABEL, sdk = sdk)

                assertEquals(RecordAudioAutoAccept.AutoAcceptResult.NotReady, result)
                assertNotClicked(whileUsing, allow)
            }
        }
    }

    @Test
    fun tryAutoAccept_ignoresAnotherAppsPrompt() {
        for (prompt in listOf("Allow Recorder to record audio?", "Allow MOBILERUN PORTAL to record audio?")) {
            val whileUsing = button("permission_allow_foreground_only_button")
            val message = node(viewId = "$CONTROLLER:id/permission_message", text = prompt)

            val result = RecordAudioAutoAccept.tryAutoAccept(dialog(whileUsing, message = message), LABEL, sdk = 34)

            assertEquals(RecordAudioAutoAccept.AutoAcceptResult.NoAction, result)
            assertNotClicked(whileUsing)
        }
        val whileUsing = button("permission_allow_foreground_only_button")
        assertEquals(
            RecordAudioAutoAccept.AutoAcceptResult.NoAction,
            RecordAudioAutoAccept.tryAutoAccept(dialog(whileUsing), " ", sdk = 34),
        )
        assertNotClicked(whileUsing)
    }

    @Test
    fun tryAutoAccept_readsTheMessageFromContentDescriptionWhenTextIsEmpty() {
        val whileUsing = button("permission_allow_foreground_only_button")
        val message = node(viewId = "$CONTROLLER:id/permission_message", text = "", contentDescription = PROMPT)

        val result = RecordAudioAutoAccept.tryAutoAccept(dialog(whileUsing, message = message), LABEL, sdk = 34)

        assertEquals(RecordAudioAutoAccept.AutoAcceptResult.ActionPerformed, result)
    }

    @Test
    fun tryAutoAccept_ignoresWindowsOutsideThePermissionController() {
        val whileUsing = button("permission_allow_foreground_only_button")

        val result = RecordAudioAutoAccept.tryAutoAccept(
            dialog(whileUsing, packageName = "com.example.fake"),
            LABEL,
            sdk = 34,
        )

        assertEquals(RecordAudioAutoAccept.AutoAcceptResult.NoAction, result)
        assertNotClicked(whileUsing)
    }

    @Test
    fun tryAutoAccept_reportsAThrowingClickAsFailed() {
        val whileUsing = button("permission_allow_foreground_only_button")
        every { whileUsing.performAction(any()) } throws IllegalStateException("recycled")
        AutoAcceptGate.armRecordAudio()

        val result = RecordAudioAutoAccept.tryAutoAccept(dialog(whileUsing), LABEL, sdk = 34)

        assertTrue(result is RecordAudioAutoAccept.AutoAcceptResult.Failed)
        assertTrue(AutoAcceptGate.isRecordAudioArmed())
    }
}
