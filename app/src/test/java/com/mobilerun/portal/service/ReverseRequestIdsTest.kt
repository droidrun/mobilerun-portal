package com.mobilerun.portal.service

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReverseRequestIdsTest {
    private class RecordingTransport : ReverseRpcTransport {
        override val generation = 1L
        val ids: MutableList<Long> = Collections.synchronizedList(mutableListOf())

        override fun send(text: String): Boolean {
            ids.add(JSONObject(text).getLong("id"))
            return true
        }
    }

    // Same id source as WebRtcManager's answer, offer and ice frames.
    private fun webRtcIce(): JSONObject =
        JSONObject()
            .put("id", ReverseRequestIds.next())
            .put("method", "webrtc/ice")
            .put("params", JSONObject().put("sessionId", "s"))

    @Test
    fun concurrentWebRtcAndSmsIdsNeverCollide() {
        val transport = RecordingTransport()
        val calls = ReverseRpcCalls(transport = { transport }, schedule = { _, _ -> })
        val webRtcIds: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val perThread = 500
        repeat(4) {
            pool.submit {
                start.await()
                repeat(perThread) { webRtcIds.add(webRtcIce().getLong("id")) }
            }
            pool.submit {
                start.await()
                repeat(perThread) { calls.call("sms/received", JSONObject()) {} }
            }
        }

        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))

        val all = webRtcIds + transport.ids
        assertEquals(8 * perThread, all.size)
        assertEquals(all.size, all.toSet().size)
        assertTrue(all.all { it >= 1L })
    }

    @Test
    fun webRtcReplyNeverCompletesSmsCall() {
        val calls = ReverseRpcCalls(transport = { RecordingTransport() }, schedule = { _, _ -> })
        val before = webRtcIce().getLong("id")
        val results = mutableListOf<ReverseRpcResult>()
        val smsId = calls.call("sms/received", JSONObject()) { results.add(it) }!!
        val after = webRtcIce().getLong("id")

        for (webRtcId in listOf(before, after)) {
            assertFalse(calls.onResponse(JSONObject().put("id", webRtcId).put("result", JSONObject()), 1))
        }
        assertTrue(results.isEmpty())
        assertEquals(1, calls.pendingCount())

        assertTrue(calls.onResponse(JSONObject().put("id", smsId).put("result", JSONObject()), 1))
        assertTrue(results.single() is ReverseRpcResult.Ok)
    }

    @Test
    fun idsStartPositiveAndIncrease() {
        val first = ReverseRequestIds.next()
        val second = ReverseRequestIds.next()

        assertTrue(first >= 1L)
        assertTrue(second > first)
    }
}
