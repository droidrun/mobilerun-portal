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
        every { handler.resolveDeepLink("example.app", "example://orders/42", "example.OPEN") } returns result
        val dispatcher = ActionDispatcher(handler)
        for (origin in ActionDispatcher.Origin.values()) {
            assertSame(result, dispatcher.dispatch("/app/deep-links", JSONObject().put("package", "example.app"), origin))
            assertSame(result, dispatcher.dispatch("app/deep-link/resolve", JSONObject().put("packageName", "example.app").put("deepLink", "example://orders/42").put("action", "example.OPEN"), origin))
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
    fun resolveReceivesTheTrimmedDeepLink() {
        val handler = mockk<ApiHandler>()
        every { handler.resolveDeepLink("example.app", "example://one", null) } returns ApiResponse.Success("resolved")
        val params = JSONObject().put("package", "example.app").put("deepLink", " example://one\n")
        assertEquals(ApiResponse.Success("resolved"), ActionDispatcher(handler).dispatch("app/deep-link/resolve", params))
    }
}
