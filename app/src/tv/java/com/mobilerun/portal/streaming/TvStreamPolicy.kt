package com.mobilerun.portal.streaming

import android.util.Log
import com.mobilerun.portal.BuildConfig

internal object TvStreamPolicy {
    const val enabled = true
    private val decision by lazy {
        val upperBound = if (BuildConfig.TV_CAPTURE_BOX_WIDTH > 0 && BuildConfig.TV_CAPTURE_BOX_HEIGHT > 0) {
            BuildConfig.TV_CAPTURE_BOX_WIDTH to BuildConfig.TV_CAPTURE_BOX_HEIGHT
        } else null
        TvEncoderCapabilities.select(TvEncoderCapabilities.probe(), upperBound).also {
            log(null, "encoder=${it.encoder?.name ?: "none"} hw=${it.encoder != null} box=${it.box.first}x${it.box.second} reason=${it.reason}")
            if (it.encoder == null) Log.w("TvStreamDiag", "no hw avc encoder — VP8 allowed")
        }
    }
    val autoCaptureBox: Pair<Int, Int> get() = decision.box
    val selectedEncoderName: String? get() = decision.encoder?.name

    fun log(sessionId: String?, event: String) {
        // Only opaque short IDs and selected numeric/codec fields: never SDP, ICE or request JSON.
        val shortId = sessionId.orEmpty().take(8).replace(Regex("[^a-zA-Z0-9_-]"), "_")
        Log.i("TvStreamDiag", "session=$shortId $event")
    }

    fun codecs(sdp: String): String {
        var video = false
        var active = false
        val payloads = mutableSetOf<String>()
        val codecs = mutableMapOf<String, String>()
        val profiles = mutableMapOf<String, String>()
        for (line in sdp.lineSequence().map { it.trim() }) {
            if (line.startsWith("m=")) {
                video = line.startsWith("m=video ")
                if (video) {
                    val fields = line.split(Regex("\\s+"))
                    active = fields.getOrNull(1) != "0"
                    if (active) payloads.addAll(fields.drop(3))
                }
            } else if (video && active) {
                Regex("a=rtpmap:([0-9]+) ([A-Za-z0-9-]+)/[0-9]+").matchEntire(line)?.let {
                    codecs[it.groupValues[1]] = it.groupValues[2].uppercase(java.util.Locale.US)
                }
                Regex("a=fmtp:([0-9]+) (.*)").matchEntire(line)?.let {
                    val profile = Regex("(?:^|[; ]+)profile-level-id=([0-9a-fA-F]{6})(?:;|$)")
                        .find(it.groupValues[2])?.groupValues?.get(1)
                    if (profile != null) profiles[it.groupValues[1]] = profile
                }
            }
        }
        return payloads.mapNotNull { pt ->
            codecs[pt]?.let { "$pt:$it${profiles[pt]?.let { p -> "/$p" }.orEmpty()}" }
        }.joinToString(",").ifEmpty { "none" }
    }
}
