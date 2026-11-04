package com.mobilerun.portal.service

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class ReverseRpcCallsTest {
    private class FakeTransport(override val generation: Long, var accept: Boolean = true) :
        ReverseRpcTransport {
        val frames = mutableListOf<JSONObject>()

        override fun send(text: String): Boolean {
            if (!accept) return false
            frames.add(JSONObject(text))
            return true
        }
    }

    private val timeouts = mutableListOf<Pair<Long, Runnable>>()
    private var transport: FakeTransport? = FakeTransport(generation = 1)
    private val ids = AtomicLong(1)
    private val logs = mutableListOf<String>()
    private val calls = ReverseRpcCalls(
        transport = { transport },
        schedule = { delayMs, task -> timeouts.add(delayMs to task) },
        nextId = { ids.getAndIncrement() },
        log = { logs.add(it) },
    )

    private fun call(
        method: String = "sms/received",
        results: MutableList<ReverseRpcResult> = mutableListOf(),
    ): Pair<Long?, MutableList<ReverseRpcResult>> {
        val id = calls.call(method, JSONObject().put("message", JSONObject()), 20_000L) {
            results.add(it)
        }
        return id to results
    }

    @Test
    fun call_sendsRequestWithNumericIdMethodAndParams() {
        val (first, _) = call()
        val (second, _) = call(method = "sms/sendState")

        assertEquals(1L, first)
        assertEquals(2L, second)
        val frame = transport!!.frames[0]
        assertTrue(frame.get("id") is Number)
        assertEquals(1L, frame.getLong("id"))
        assertEquals("sms/received", frame.getString("method"))
        assertTrue(frame.getJSONObject("params").has("message"))
        assertEquals("sms/sendState", transport!!.frames[1].getString("method"))
        assertEquals(20_000L, timeouts[0].first)
        assertEquals(2, calls.pendingCount())
    }

    @Test
    fun onResponse_resultCompletesWithOk() {
        val (id, results) = call()

        val handled = calls.onResponse(
            JSONObject().put("id", id).put("result", JSONObject()).put("status", "success"),
            generation = 1,
        )

        assertTrue(handled)
        val ok = results.single() as ReverseRpcResult.Ok
        assertEquals("{}", ok.result.toString())
        assertEquals(0, calls.pendingCount())
    }

    @Test
    fun onResponse_nullErrorWithResultIsOk() {
        val (id, results) = call()

        calls.onResponse(JSONObject("""{"id":$id,"result":{},"error":null}"""), generation = 1)

        assertTrue(results.single() is ReverseRpcResult.Ok)
    }

    @Test
    fun onResponse_missingResultIsOkWithNull() {
        val (id, results) = call()

        calls.onResponse(JSONObject().put("id", id), generation = 1)

        assertNull((results.single() as ReverseRpcResult.Ok).result)
    }

    @Test
    fun onResponse_stringErrorCompletesWithError() {
        val (id, results) = call()

        calls.onResponse(
            JSONObject().put("id", id).put("error", "sms: invalid_params: id"),
            generation = 1,
        )

        assertEquals(ReverseRpcResult.Error("sms: invalid_params: id"), results.single())
    }

    @Test
    fun onResponse_structuredErrorIsSerialized() {
        val (id, results) = call()

        calls.onResponse(
            JSONObject().put("id", id).put("error", JSONObject().put("code", 1)),
            generation = 1,
        )

        assertEquals(ReverseRpcResult.Error("""{"code":1}"""), results.single())
    }

    @Test
    fun onResponse_acceptsNumericStringId() {
        val (id, results) = call()

        assertTrue(calls.onResponse(JSONObject().put("id", id.toString()).put("result", 1), 1))
        assertEquals(ReverseRpcResult.Ok(1), results.single())
    }

    @Test
    fun onResponse_ignoresUnknownMissingAndNonNumericIds() {
        val (_, results) = call()

        assertFalse(calls.onResponse(JSONObject().put("id", 999).put("result", 1), 1))
        assertFalse(calls.onResponse(JSONObject().put("result", 1), 1))
        assertFalse(calls.onResponse(JSONObject().put("id", "abc").put("result", 1), 1))
        assertFalse(calls.onResponse(JSONObject().put("id", 1.5).put("result", 1), 1))
        assertTrue(results.isEmpty())
        assertEquals(1, calls.pendingCount())
    }

    @Test
    fun timeout_completesWithTimeoutAndLateReplyIsIgnored() {
        val (id, results) = call()

        timeouts.single().second.run()

        assertEquals(listOf<ReverseRpcResult>(ReverseRpcResult.Timeout), results)
        assertFalse(calls.onResponse(JSONObject().put("id", id).put("result", 1), 1))
        assertEquals(1, results.size)
    }

    @Test
    fun timeout_afterReplyDoesNothing() {
        val (id, results) = call()
        calls.onResponse(JSONObject().put("id", id).put("result", 1), 1)

        timeouts.single().second.run()

        assertEquals(listOf<ReverseRpcResult>(ReverseRpcResult.Ok(1)), results)
    }

    @Test
    fun onResponse_fromAnotherGenerationIsIgnored() {
        val (id, results) = call()

        assertFalse(calls.onResponse(JSONObject().put("id", id).put("result", 1), generation = 2))

        assertTrue(results.isEmpty())
        assertEquals(1, calls.pendingCount())
    }

    @Test
    fun failAll_closesOnlyThatGenerationAndOlder() {
        val (_, oldResults) = call()
        transport = FakeTransport(generation = 2)
        val (newId, newResults) = call()

        calls.failAll(0)
        assertTrue(oldResults.isEmpty())

        calls.failAll(1)
        assertEquals(listOf<ReverseRpcResult>(ReverseRpcResult.Closed), oldResults)
        assertTrue(newResults.isEmpty())

        assertTrue(calls.onResponse(JSONObject().put("id", newId).put("result", 1), 2))
        assertEquals(listOf<ReverseRpcResult>(ReverseRpcResult.Ok(1)), newResults)
    }

    @Test
    fun failAll_thenReplyIsIgnored() {
        val (id, results) = call()

        calls.failAll(1)

        assertFalse(calls.onResponse(JSONObject().put("id", id).put("result", 1), 1))
        assertEquals(listOf<ReverseRpcResult>(ReverseRpcResult.Closed), results)
    }

    @Test
    fun isOpen_followsTheCurrentTransport() {
        assertTrue(calls.isOpen())
        transport = null
        assertFalse(calls.isOpen())
    }

    @Test
    fun call_withoutSocketClosesAtOnceWithoutUsingAnId() {
        transport = null

        val (id, results) = call()

        assertNull(id)
        assertEquals(listOf<ReverseRpcResult>(ReverseRpcResult.Closed), results)
        assertEquals(1L, ids.get())
        assertTrue(timeouts.isEmpty())
    }

    @Test
    fun call_whenSendFailsClosesAndLeavesNothingPending() {
        transport = FakeTransport(generation = 1, accept = false)

        val (id, results) = call()

        assertNull(id)
        assertEquals(listOf<ReverseRpcResult>(ReverseRpcResult.Closed), results)
        assertEquals(0, calls.pendingCount())
    }

    @Test
    fun call_whenTimeoutCannotBeScheduledClosesWithoutSending() {
        val rejecting = ReverseRpcCalls(
            transport = { transport },
            schedule = { _, _ -> throw IllegalStateException("shut down") },
        )
        val results = mutableListOf<ReverseRpcResult>()

        val id = rejecting.call("sms/received", JSONObject()) { results.add(it) }

        assertNull(id)
        assertEquals(listOf<ReverseRpcResult>(ReverseRpcResult.Closed), results)
        assertTrue(transport!!.frames.isEmpty())
        assertEquals(0, rejecting.pendingCount())
    }

    @Test
    fun callbackFailureIsLoggedAndContained() {
        val id = calls.call("sms/received", JSONObject()) { throw IllegalStateException("boom") }

        assertTrue(calls.onResponse(JSONObject().put("id", id).put("result", 1), 1))

        assertEquals(listOf("Reverse call sms/received callback failed: IllegalStateException"), logs)
    }
}
