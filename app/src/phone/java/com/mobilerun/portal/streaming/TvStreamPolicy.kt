package com.mobilerun.portal.streaming

/** Flavor counterpart: keeps the phone stream policy unchanged. */
internal object TvStreamPolicy {
    const val enabled = false
    val autoCaptureBox: Pair<Int, Int>? = null
    fun log(sessionId: String?, event: String) {}
    fun codecs(sdp: String): String = ""
}
