package com.mobilerun.portal.service

object HeadlessActionSupport {
    fun isAllowed(normalizedMethod: String): Boolean {
        return normalizedMethod == "stream/start" ||
            normalizedMethod == "stream/stop" ||
            normalizedMethod == "global" ||
            normalizedMethod == "webrtc/answer" ||
            normalizedMethod == "webrtc/offer" ||
            normalizedMethod == "webrtc/connect" ||
            normalizedMethod == "webrtc/rtcConfiguration" ||
            normalizedMethod == "webrtc/requestFrame" ||
            normalizedMethod == "webrtc/keepAlive" ||
            normalizedMethod == "screen/keepAwake/set" ||
            normalizedMethod == "screen/keepAwake/status" ||
            normalizedMethod == "cloud/reconnectAfterRestart/status" ||
            normalizedMethod == "app/deep-links" ||
            normalizedMethod == "app/deep-link/resolve" ||
            normalizedMethod == "clipboard/get" ||
            normalizedMethod == "clipboard/set" ||
            normalizedMethod == "webrtc/ice" ||
            normalizedMethod.startsWith("sms/") ||
            normalizedMethod.startsWith("triggers/")
    }
}
