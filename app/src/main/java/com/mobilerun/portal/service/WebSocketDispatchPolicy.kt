package com.mobilerun.portal.service

internal enum class WebSocketDispatchBucket {
    SIGNALING,
    LIGHTWEIGHT,
    COMMAND,
    INSTALL,
    SMS,
}

internal object WebSocketDispatchPolicy {
    fun bucketForNormalizedMethod(normalizedMethod: String): WebSocketDispatchBucket {
        return when {
            normalizedMethod == "install" -> WebSocketDispatchBucket.INSTALL
            normalizedMethod.startsWith("sms/") -> WebSocketDispatchBucket.SMS
            isLightweightSignalingMethod(normalizedMethod) -> WebSocketDispatchBucket.LIGHTWEIGHT
            isOrderedSignalingMethod(normalizedMethod) -> WebSocketDispatchBucket.SIGNALING
            else -> WebSocketDispatchBucket.COMMAND
        }
    }

    /**
     * Commands, installs and SMS calls act for a caller whose call failed with its socket and may be
     * retried, so they run only while that socket is current. Stream and WebRTC calls act on
     * sessions the Portal reconciles itself after a reconnect.
     */
    fun requiresOriginSocket(bucket: WebSocketDispatchBucket): Boolean =
        bucket == WebSocketDispatchBucket.COMMAND ||
            bucket == WebSocketDispatchBucket.INSTALL ||
            bucket == WebSocketDispatchBucket.SMS

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
