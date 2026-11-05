package com.mobilerun.portal.sms

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SmsBridgeParamsTest {
    @Test
    fun list_defaults() {
        assertEquals(SmsBridgeListParams(), listOk(JSONObject()))
        assertEquals(SmsBridgeListParams(), listOk(JSONObject().put("limit", 0).put("direction", "").put("order", "")))
        val nulls = JSONObject()
        listOf("direction", "limit", "since", "until", "storedFrom", "order", "cursor", "address", "threadId", "services", "kinds", "junk")
            .forEach { nulls.put(it, JSONObject.NULL) }
        assertEquals(SmsBridgeListParams(), listOk(nulls))
    }

    @Test
    fun list_acceptsEveryBoundInRange() {
        val params = listOk(
            JSONObject()
                .put("direction", "in")
                .put("limit", 200)
                .put("since", 0)
                .put("until", 1)
                .put("storedFrom", 5_000_000_000_000L)
                .put("order", "asc")
                .put("cursor", "c".repeat(1024))
                .put("address", "a".repeat(256))
                .put("threadId", "t".repeat(256))
                .put("junk", ""),
        )
        assertEquals("in", params.direction)
        assertEquals(200, params.limit)
        assertEquals(0L, params.since)
        assertEquals(1L, params.until)
        assertEquals(5_000_000_000_000L, params.storedFrom)
        assertEquals("asc", params.order)
    }

    @Test
    fun list_rejectsOutOfBoundValues() {
        mapOf(
            JSONObject().put("direction", "both") to "sms: invalid_params: direction \"both\"",
            JSONObject().put("limit", -1) to "sms: invalid_params: limit must be 1-200",
            JSONObject().put("limit", 201) to "sms: invalid_params: limit must be 1-200",
            JSONObject().put("limit", 1.5) to "sms: invalid_params: limit must be an integer",
            JSONObject().put("limit", "5") to "sms: invalid_params: limit must be an integer",
            JSONObject().put("since", -1) to "sms: invalid_params: since, until and storedFrom must not be negative",
            JSONObject().put("until", -1) to "sms: invalid_params: since, until and storedFrom must not be negative",
            JSONObject().put("storedFrom", -1) to "sms: invalid_params: since, until and storedFrom must not be negative",
            JSONObject().put("since", 5).put("until", 5) to "sms: invalid_params: since must be before until",
            JSONObject().put("order", "newest") to "sms: invalid_params: order \"newest\"",
            JSONObject().put("cursor", "c".repeat(1025)) to "sms: invalid_params: cursor over 1024 bytes",
            JSONObject().put("address", "é".repeat(129)) to "sms: invalid_params: address over 256 bytes",
            JSONObject().put("threadId", "t".repeat(257)) to "sms: invalid_params: threadId over 256 bytes",
            JSONObject().put("direction", 1) to "sms: invalid_params: direction must be a string",
            JSONObject().put("services", "SMS") to "sms: invalid_params: services must be an array",
        ).forEach { (params, expected) -> assertEquals(params.toString(), expected, listError(params)) }
    }

    @Test
    fun list_unknownKeysAndUnannouncedTokensAreUnsupported() {
        mapOf(
            JSONObject().put("box", "inbox") to "sms: unsupported_param: \"box\"",
            JSONObject().put("services", JSONArray().put("SMS")) to "sms: unsupported_param: \"services\"",
            JSONObject().put("services", JSONArray()) to "sms: unsupported_param: \"services\"",
            JSONObject().put("kinds", JSONArray().put("message")) to "sms: unsupported_param: \"kinds\"",
            JSONObject().put("junk", "exclude") to "sms: unsupported_param: \"junk\"",
        ).forEach { (params, expected) -> assertEquals(params.toString(), expected, listError(params)) }
    }

    @Test
    fun send_normalizesDefaults() {
        val params = sendOk(JSONObject().put("to", "+15550001111").put("body", "hi"))
        assertEquals(SmsBridgeSendParams("", "+15550001111", "hi", null, null, deliveryReport = true), params)
        val explicit = sendOk(
            JSONObject().put("id", "x".repeat(256)).put("to", "1".repeat(256)).put("body", "b".repeat(16 * 1024))
                .put("simSlot", 0).put("deliveryReport", false),
        )
        assertEquals(0L, explicit.simSlot)
        assertEquals(false, explicit.deliveryReport)
    }

    @Test
    fun send_rejectsOutOfBoundValues() {
        val base = { JSONObject().put("to", "+15550001111").put("body", "hi") }
        mapOf(
            base().put("id", "x".repeat(257)) to "sms: invalid_params: id over 256 bytes",
            base().put("to", "") to "sms: invalid_params: to is required",
            JSONObject().put("body", "hi") to "sms: invalid_params: to is required",
            base().put("to", "1".repeat(257)) to "sms: invalid_params: to over 256 bytes",
            base().put("body", "") to "sms: invalid_params: body is required",
            base().put("body", "b".repeat(16 * 1024 + 1)) to "sms: invalid_params: body over 16384 bytes",
            base().put("subscriptionId", 1).put("simSlot", 0) to
                "sms: invalid_params: subscriptionId and simSlot are mutually exclusive",
            base().put("simSlot", -1) to "sms: invalid_params: simSlot must not be negative",
            base().put("deliveryReport", "yes") to "sms: invalid_params: deliveryReport must be a boolean",
            base().put("subscriptionId", "1") to "sms: invalid_params: subscriptionId must be an integer",
            base().put("phone_number", "+1") to "sms: unsupported_param: \"phone_number\"",
            base().put("message", "hi") to "sms: unsupported_param: \"message\"",
        ).forEach { (params, expected) -> assertEquals(params.toString(), expected, sendError(params)) }
    }

    @Test
    fun id_requiredBoundedAndStrict() {
        assertEquals("abc", (SmsBridgeParams.parseId(JSONObject().put("id", "abc")) as SmsBridgeParse.Ok).value)
        assertEquals("sms: invalid_params: id is required", idError(JSONObject()))
        assertEquals("sms: invalid_params: id is required", idError(JSONObject().put("id", "")))
        assertEquals("sms: invalid_params: id over 256 bytes", idError(JSONObject().put("id", "x".repeat(257))))
        assertEquals("sms: unsupported_param: \"sms_id\"", idError(JSONObject().put("id", "a").put("sms_id", "1")))
    }

    private fun listOk(params: JSONObject): SmsBridgeListParams =
        (SmsBridgeParams.parseList(params) as SmsBridgeParse.Ok).value

    private fun listError(params: JSONObject): String =
        (SmsBridgeParams.parseList(params) as SmsBridgeParse.Err).error.wire

    private fun sendOk(params: JSONObject): SmsBridgeSendParams =
        (SmsBridgeParams.parseSend(params) as SmsBridgeParse.Ok).value

    private fun sendError(params: JSONObject): String =
        (SmsBridgeParams.parseSend(params) as SmsBridgeParse.Err).error.wire

    private fun idError(params: JSONObject): String =
        (SmsBridgeParams.parseId(params) as SmsBridgeParse.Err).error.wire
}
