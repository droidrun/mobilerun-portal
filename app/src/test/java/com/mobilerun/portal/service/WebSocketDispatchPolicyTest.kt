package com.mobilerun.portal.service

import org.junit.Assert.assertEquals
import org.junit.Test

class WebSocketDispatchPolicyTest {
    @Test
    fun bucketForNormalizedMethod_routesInstallCommandsToInstallExecutor() {
        assertEquals(
            WebSocketDispatchBucket.INSTALL,
            WebSocketDispatchPolicy.bucketForNormalizedMethod("install"),
        )
    }

    @Test
    fun bucketForNormalizedMethod_routesOrderedStreamAndWebRtcMethodsToSignalingExecutor() {
        assertEquals(
            WebSocketDispatchBucket.SIGNALING,
            WebSocketDispatchPolicy.bucketForNormalizedMethod("stream/start"),
        )
        assertEquals(
            WebSocketDispatchBucket.SIGNALING,
            WebSocketDispatchPolicy.bucketForNormalizedMethod("stream/stop"),
        )
        assertEquals(
            WebSocketDispatchBucket.SIGNALING,
            WebSocketDispatchPolicy.bucketForNormalizedMethod("webrtc/connect"),
        )
        assertEquals(
            WebSocketDispatchBucket.SIGNALING,
            WebSocketDispatchPolicy.bucketForNormalizedMethod("webrtc/rtcConfiguration"),
        )
        assertEquals(
            WebSocketDispatchBucket.SIGNALING,
            WebSocketDispatchPolicy.bucketForNormalizedMethod("webrtc/offer"),
        )
        assertEquals(
            WebSocketDispatchBucket.SIGNALING,
            WebSocketDispatchPolicy.bucketForNormalizedMethod("webrtc/answer"),
        )
        assertEquals(
            WebSocketDispatchBucket.SIGNALING,
            WebSocketDispatchPolicy.bucketForNormalizedMethod("webrtc/ice"),
        )
    }

    @Test
    fun bucketForNormalizedMethod_routesLivenessMethodsToLightweightExecutor() {
        assertEquals(
            WebSocketDispatchBucket.LIGHTWEIGHT,
            WebSocketDispatchPolicy.bucketForNormalizedMethod("webrtc/requestFrame"),
        )
        assertEquals(
            WebSocketDispatchBucket.LIGHTWEIGHT,
            WebSocketDispatchPolicy.bucketForNormalizedMethod("webrtc/keepAlive"),
        )
    }

    @Test
    fun bucketForNormalizedMethod_keepsHeavyCommandsOnCommandExecutor() {
        assertEquals(
            WebSocketDispatchBucket.COMMAND,
            WebSocketDispatchPolicy.bucketForNormalizedMethod("screenshot"),
        )
        assertEquals(
            WebSocketDispatchBucket.COMMAND,
            WebSocketDispatchPolicy.bucketForNormalizedMethod("task/start"),
        )
        assertEquals(
            WebSocketDispatchBucket.COMMAND,
            WebSocketDispatchPolicy.bucketForNormalizedMethod("files/list"),
        )
    }

    @Test
    fun bucketForNormalizedMethod_routesEverySmsMethodToSmsExecutor() {
        for (method in listOf(
            "sms/send",
            "sms/sendStatus",
            "sms/list",
            "sms/get",
            "sms/subscriptions",
            "sms/capabilities",
            "sms/status",
            "sms/messages/list",
            "sms/messages/get",
        )) {
            assertEquals(
                method,
                WebSocketDispatchBucket.SMS,
                WebSocketDispatchPolicy.bucketForNormalizedMethod(method),
            )
        }
        assertEquals(
            WebSocketDispatchBucket.COMMAND,
            WebSocketDispatchPolicy.bucketForNormalizedMethod("smsx/send"),
        )
    }

    @Test
    fun locationGet_runsOnItsOwnBucket() {
        assertEquals(
            WebSocketDispatchBucket.LOCATION,
            WebSocketDispatchPolicy.bucketForNormalizedMethod("location/get"),
        )
        assertEquals(true, WebSocketDispatchPolicy.requiresOriginSocket(WebSocketDispatchBucket.LOCATION))
    }

    @Test
    fun shouldTraceExecutionTiming_marksStateAndWebRtcSetupMethods() {
        assertEquals(true, WebSocketDispatchPolicy.shouldTraceExecutionTiming("state"))
        assertEquals(true, WebSocketDispatchPolicy.shouldTraceExecutionTiming("packages"))
        assertEquals(true, WebSocketDispatchPolicy.shouldTraceExecutionTiming("screenshot"))
        assertEquals(
            true,
            WebSocketDispatchPolicy.shouldTraceExecutionTiming("webrtc/rtcConfiguration"),
        )
        assertEquals(true, WebSocketDispatchPolicy.shouldTraceExecutionTiming("webrtc/requestFrame"))
        assertEquals(false, WebSocketDispatchPolicy.shouldTraceExecutionTiming("task/start"))
        assertEquals(false, WebSocketDispatchPolicy.shouldTraceExecutionTiming("webrtc/offer"))
    }

    @Test
    fun requiresOriginSocket_onlyForCommandsInstallsAndSms() {
        assertEquals(true, WebSocketDispatchPolicy.requiresOriginSocket(WebSocketDispatchBucket.COMMAND))
        assertEquals(true, WebSocketDispatchPolicy.requiresOriginSocket(WebSocketDispatchBucket.INSTALL))
        assertEquals(true, WebSocketDispatchPolicy.requiresOriginSocket(WebSocketDispatchBucket.SMS))
        assertEquals(false, WebSocketDispatchPolicy.requiresOriginSocket(WebSocketDispatchBucket.SIGNALING))
        assertEquals(false, WebSocketDispatchPolicy.requiresOriginSocket(WebSocketDispatchBucket.LIGHTWEIGHT))
    }
}
