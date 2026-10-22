package com.mobilerun.portal.service

import com.mobilerun.portal.api.ApiHandler
import com.mobilerun.portal.api.ApiResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeepLinkDispatchTest {
    @Test
    fun routesReadOnlyMethodsConsistentlyAcrossTransportsAndAliases() {
        val handler = mockk<ApiHandler>()
        val result = ApiResponse.RawObject(JSONObject().put("scope", "manifest"))
        every { handler.getDeepLinks("example.app") } returns result
        every { handler.resolveDeepLink("example.app", "example://orders/42", null) } returns result
        val dispatcher = ActionDispatcher(handler)
        for (origin in ActionDispatcher.Origin.values()) {
            assertSame(result, dispatcher.dispatch("/app/deep-links", JSONObject().put("package", "example.app"), origin))
            assertSame(result, dispatcher.dispatch("app/deep-link/resolve", JSONObject().put("packageName", "example.app").put("deepLink", "example://orders/42"), origin))
        }
        verify(exactly = 0) { handler.openDeepLink(any(), any(), any(), any()) }
    }

    @Test
    fun rejectsMissingInputsBeforeCallingPackageInspection() {
        val handler = mockk<ApiHandler>()
        val dispatcher = ActionDispatcher(handler)
        assertEquals(ApiResponse.Error("Missing required param: 'package'"), dispatcher.dispatch("app/deep-links", JSONObject()))
        assertEquals(ApiResponse.Error("Missing required param: 'package'"), dispatcher.dispatch("app/deep-link/resolve", JSONObject().put("deepLink", "example://one")))
        assertEquals(ApiResponse.Error("Missing required param: 'deepLink'"), dispatcher.dispatch("app/deep-link/resolve", JSONObject().put("package", "example.app")))
        verify(exactly = 0) { handler.getDeepLinks(any()) }
        verify(exactly = 0) { handler.resolveDeepLink(any(), any(), any()) }
    }

    @Test
    fun resolutionKeepsExplicitActionAndIsHeadlessSafeWithoutAllowingLaunch() {
        val handler = mockk<ApiHandler>()
        every { handler.resolveDeepLink("example.app", "example://one", "example.OPEN") } returns ApiResponse.Success("ok")
        val result = ActionDispatcher(handler).dispatch("app/deep-link/resolve", JSONObject()
            .put("package", "example.app").put("deepLink", "example://one").put("action", "example.OPEN"))
        assertEquals(ApiResponse.Success("ok"), result)
        assertTrue(HeadlessActionSupport.isAllowed("app/deep-links"))
        assertTrue(HeadlessActionSupport.isAllowed("app/deep-link/resolve"))
        assertFalse(HeadlessActionSupport.isAllowed("app/deep-link"))
        assertTrue(shouldRedactReverseRequestPayload("app/deep-link/resolve"))
        assertTrue(shouldRedactReverseResponsePayload("app/deep-link/resolve"))
    }
}
