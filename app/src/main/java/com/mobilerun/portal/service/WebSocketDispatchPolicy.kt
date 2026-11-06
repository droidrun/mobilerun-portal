package com.mobilerun.portal.service

internal enum class WebSocketDispatchBucket {
    SIGNALING,
    LIGHTWEIGHT,
    COMMAND,
    INSTALL,
    SMS,
    LOCATION,
}

internal object WebSocketDispatchPolicy {
    fun bucketForNormalizedMethod(normalizedMethod: String): WebSocketDispatchBucket {
        return when {
            normalizedMethod == "install" -> WebSocketDispatchBucket.INSTALL
            normalizedMethod.startsWith("sms/") -> WebSocketDispatchBucket.SMS
            // Waits up to 10 s for a fix, so it must not hold up commands.
            normalizedMethod == "location/get" -> WebSocketDispatchBucket.LOCATION
            isLightweightSignalingMethod(normalizedMethod) -> WebSocketDispatchBucket.LIGHTWEIGHT
            isOrderedSignalingMethod(normalizedMethod) -> WebSocketDispatchBucket.SIGNALING
            else -> WebSocketDispatchBucket.COMMAND
        }
    }

    /**
     * Commands, installs, SMS calls and location reads act for a caller whose call failed with its
     * socket and may be retried, so they run only while that socket is current. Stream and WebRTC calls act on
     * sessions the Portal reconciles itself after a reconnect.
     */
    fun requiresOriginSocket(bucket: WebSocketDispatchBucket): Boolean =
        bucket == WebSocketDispatchBucket.COMMAND ||
            bucket == WebSocketDispatchBucket.INSTALL ||
            bucket == WebSocketDispatchBucket.SMS ||
            bucket == WebSocketDispatchBucket.LOCATION

    internal fun shouldTraceExecutionTiming(normalizedMethod: String): Boolean =
        normalizedMethod == "state" ||
            normalizedMethod == "packages" ||
            normalizedMethod == "screenshot" ||
            normalizedMethod == "webrtc/rtcConfiguration" ||
            normalizedMethod == "webrtc/requestFrame"

    internal fun isOrderedSignalingMethod(normalizedMethod: String): Boolean =
        normalizedMethod.startsWith("stream/") || normalizedMethod.startsWith("webrtc/")

    private fun isLightweightSignalingMethod(normalizedMethod: String): Boolean =
        normalizedMethod == "webrtc/requestFrame" ||
                normalizedMethod == "webrtc/keepAlive"
}
