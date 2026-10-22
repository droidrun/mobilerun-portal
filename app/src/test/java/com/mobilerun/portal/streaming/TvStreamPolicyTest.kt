package com.mobilerun.portal.streaming

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class TvStreamPolicyTest {
    @Test
    fun diagnosticsSelectOnlyVideoCodecsAndProfiles() {
        assumeTrue(TvStreamPolicy.enabled)
        val sdp = """
            v=0
            o=user-secret 0 0 IN IP4 192.0.2.1
            m=audio 9 UDP/TLS/RTP/SAVPF 111
            a=rtpmap:111 opus/48000
            m=video 9 UDP/TLS/RTP/SAVPF 96 97 98
            a=rtpmap:96 VP8/90000
            a=rtpmap:97 H264/90000
            a=fmtp:97 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f
            a=rtpmap:98 rtx/90000
            a=ice-pwd:secret
        """.trimIndent()
        assertEquals("96:VP8,97:H264/42e01f,98:RTX", TvStreamPolicy.codecs(sdp))
    }

    @Test
    fun rejectedVideoSectionCannotCountAsCompatibleH264() {
        assumeTrue(TvStreamPolicy.enabled)
        assertEquals("none", TvStreamPolicy.codecs("m=video 0 UDP/TLS/RTP/SAVPF 97\na=rtpmap:97 H264/90000"))
        assertEquals("96:VP8", TvStreamPolicy.codecs("m=video 9 UDP/TLS/RTP/SAVPF 96\na=rtpmap:96 VP8/90000\na=rtpmap:97 H264/90000"))
    }
}
