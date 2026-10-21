package com.mobilerun.portal.streaming

/** Flavor counterpart: keeps the phone stream policy unchanged. */
internal object TvStreamPolicy {
    const val enabled = false
    const val h264Only = false
    val allowedVideoCodecs = setOf("H264", "VP8")
    val autoCaptureBox: Pair<Int, Int>? = null
    fun log(sessionId: String?, event: String) {}
    fun codecs(sdp: String): String = ""
}
