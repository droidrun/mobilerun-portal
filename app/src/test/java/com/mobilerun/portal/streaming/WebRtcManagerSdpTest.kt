package com.mobilerun.portal.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebRtcManagerSdpTest {
    private val session = listOf("v=0", "o=- 1 2 IN IP4 127.0.0.1", "s=-", "t=0 0", "a=group:BUNDLE 0 1")

    private fun audio(direction: String, port: Int = 9, mid: String? = "0") =
        listOfNotNull(
            "m=audio $port UDP/TLS/RTP/SAVPF 111 63",
            "c=IN IP4 0.0.0.0",
            mid?.let { "a=mid:$it" },
            "a=$direction",
            "a=rtpmap:111 opus/48000/2",
            "a=rtcp-fb:111 transport-cc",
            "a=fmtp:111 minptime=10;useinbandfec=1",
            "a=rtpmap:63 red/48000/2",
            "a=fmtp:63 111/111",
        )

    private val video =
        listOf(
            "m=video 9 UDP/TLS/RTP/SAVPF 96 97 102 103 98",
            "c=IN IP4 0.0.0.0",
            "a=mid:1",
            "a=sendonly",
            "a=rtpmap:96 VP8/90000",
            "a=rtcp-fb:96 nack",
            "a=rtpmap:97 rtx/90000",
            "a=fmtp:97 apt=96",
            "a=rtpmap:102 H264/90000",
            "a=rtcp-fb:102 nack",
            "a=fmtp:102 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f",
            "a=rtpmap:103 rtx/90000",
            "a=fmtp:103 apt=102",
            "a=rtpmap:98 VP9/90000",
            "a=fmtp:98 profile-id=0",
        )

    private val forcedVideo =
        listOf(
            "m=video 9 UDP/TLS/RTP/SAVPF 102 103",
            "c=IN IP4 0.0.0.0",
            "a=mid:1",
            "a=sendonly",
            "a=rtpmap:102 H264/90000",
            "a=rtcp-fb:102 nack",
            "a=fmtp:102 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f",
            "a=rtpmap:103 rtx/90000",
            "a=fmtp:103 apt=102",
        )

    private fun sdp(vararg sections: List<String>) = (session + sections.flatMap { it }).joinToString("\r\n") + "\r\n"

    @Test
    fun offerSendableAudioMid_acceptsRecvonlyAndSendrecv() {
        assertEquals("0", WebRtcManager.offerSendableAudioMid(sdp(audio("recvonly"), video)))
        assertEquals("0", WebRtcManager.offerSendableAudioMid(sdp(audio("sendrecv"), video)))
    }

    @Test
    fun offerSendableAudioMid_rejectsInactiveSendonlyAndPortZero() {
        assertNull(WebRtcManager.offerSendableAudioMid(sdp(audio("inactive"), video)))
        assertNull(WebRtcManager.offerSendableAudioMid(sdp(audio("sendonly"), video)))
        assertNull(WebRtcManager.offerSendableAudioMid(sdp(audio("recvonly", port = 0), video)))
    }

    @Test
    fun offerSendableAudioMid_ignoresOffersWithoutAudio() {
        assertNull(WebRtcManager.offerSendableAudioMid(sdp(video)))
    }

    @Test
    fun offerSendableAudioMid_readsTheAudioSectionOnly() {
        // Video's a=sendonly must not leak into the following audio section.
        assertEquals("2", WebRtcManager.offerSendableAudioMid(sdp(video, audio("recvonly", mid = "2"))))
        assertEquals("", WebRtcManager.offerSendableAudioMid(sdp(audio("recvonly", mid = null), video)))
    }

    @Test
    fun offerSendableAudioMid_acceptsBundleOnlyPortZero() {
        val bundleOnly = audio("recvonly", port = 0) + "a=bundle-only"
        assertEquals("0", WebRtcManager.offerSendableAudioMid(sdp(video, bundleOnly)))
    }

    @Test
    fun forceH264_filtersVideoAsBefore() {
        assertEquals(sdp(forcedVideo), WebRtcManager.forceH264(sdp(video)))
    }

    @Test
    fun forceH264_keepsOpusInTheAudioSection() {
        assertEquals(
            sdp(audio("sendonly"), forcedVideo),
            WebRtcManager.forceH264(sdp(audio("sendonly"), video)),
        )
        assertEquals(
            sdp(forcedVideo, audio("sendonly")),
            WebRtcManager.forceH264(sdp(video, audio("sendonly"))),
        )
    }

    @Test
    fun forceH264_returnsNullWithoutVideoH264() {
        assertNull(WebRtcManager.forceH264(sdp(audio("sendonly"))))
        val noH264 = video.filterNot { it.startsWith("a=") && (it.contains(":102 ") || it.contains(":103 ")) }
        assertNull(WebRtcManager.forceH264(sdp(audio("sendonly"), noH264)))
    }
}
