package com.mobilerun.portal.sms

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SmsBridgeApiTest {
    private var now = 1_000_000L
    private var cloudEnabled = true
    private var cloudSubscriptionId: Int? = null
    private var telephony = true
    private var readSms = true
    private var sendSms = true
    private var subscriptions: SmsSubscriptionsResult = SmsSubscriptionsResult.Available(listOf(SIM_A))
    private var limiterNow = 0L
    private var nextId = 0

    private lateinit var provider: FakeSmsProvider
    private lateinit var store: InMemorySendRecordStore
    private lateinit var sender: FakeBridgeSender
    private lateinit var limiter: SmsPartRateLimiter
    private lateinit var api: SmsBridgeApi

    @Before
    fun setUp() {
        provider = FakeSmsProvider()
        store = InMemorySendRecordStore()
        sender = FakeBridgeSender(store.events)
        limiter = SmsPartRateLimiter(clock = { limiterNow })
        api = SmsBridgeApi(
            cloudEnabled = { cloudEnabled },
            cloudSubscriptionId = { cloudSubscriptionId },
            hasTelephony = { telephony },
            hasReadSms = { readSms },
            hasSendSms = { sendSms },
            subscriptions = { subscriptions },
            provider = provider,
            records = store,
            sender = sender,
            limiter = limiter,
            clock = { now },
            newId = { "minted-${++nextId}" },
            newToken = { "token" },
        )
    }

    // --- gating ---

    @Test
    fun everyBridgeMethod_answersCloudSmsDisabledWhileSwitchIsOff() {
        cloudEnabled = false
        provider.add(SmsProviderRow.TYPE_INBOX, date = 10)

        listOf(
            "sms/list" to JSONObject(),
            "sms/get" to JSONObject().put("id", "1-10"),
            "sms/subscriptions" to JSONObject(),
            "sms/send" to sendParams(id = "a"),
            "sms/sendStatus" to JSONObject().put("id", "a"),
        ).forEach { (method, params) ->
            assertEquals(method, "sms: permission_denied: cloud_sms_disabled", error(api.handle(method, params)))
        }
        assertTrue(sender.sends.isEmpty())
        assertTrue(store.records.isEmpty())
        assertEquals(0, provider.queries)
    }

    @Test
    fun withoutTelephony_everyMethodIsUnknown_likeAnUnannouncedToken() {
        telephony = false
        provider.add(SmsProviderRow.TYPE_INBOX, date = 10)

        listOf(true, false).forEach { enabled ->
            cloudEnabled = enabled
            listOf(
                "sms/list" to JSONObject(),
                "sms/get" to JSONObject().put("id", "1-10"),
                "sms/subscriptions" to JSONObject(),
                "sms/send" to sendParams(id = "a"),
                "sms/sendStatus" to JSONObject().put("id", "a"),
            ).forEach { (method, params) ->
                assertEquals(SmsBridgeResult.UnknownMethod(method), api.handle(method, params))
            }
        }
        assertTrue(sender.sends.isEmpty())
        assertTrue(store.records.isEmpty())
        assertEquals(0, provider.queries)
    }

    @Test
    fun isBridgeCall_takesSendOnlyWithBody() {
        assertTrue(SmsBridgeApi.isBridgeCall("sms/send", JSONObject().put("body", "x")))
        assertFalse(SmsBridgeApi.isBridgeCall("sms/send", JSONObject().put("message", "x").put("to", "+1")))
        assertTrue(SmsBridgeApi.isBridgeCall("sms/list", JSONObject()))
        assertTrue(SmsBridgeApi.isBridgeCall("sms/sendStatus", JSONObject()))
        listOf("sms/capabilities", "sms/status", "sms/messages/list", "sms/messages/get").forEach {
            assertFalse(it, SmsBridgeApi.isBridgeCall(it, JSONObject().put("body", "x")))
        }
    }

    // --- list ---

    @Test
    fun list_mapsRowsToBridgeMessagesNewestFirst() {
        subscriptions = SmsSubscriptionsResult.Available(listOf(SIM_A.copy(phoneNumber = "+15555215554")))
        provider.add(SmsProviderRow.TYPE_INBOX, date = 100, dateSent = 90, subscriptionId = 1)
        provider.add(SmsProviderRow.TYPE_SENT, date = 200, dateSent = 190, subscriptionId = 1)
        provider.add(SmsProviderRow.TYPE_DRAFT, date = 300)
        provider.add(SmsProviderRow.TYPE_OUTBOX, date = 400)

        val result = ok(api.handle("sms/list", JSONObject()))

        val messages = result.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertFalse(result.has("nextCursor"))
        val sent = messages.getJSONObject(0)
        assertEquals("2-200", sent.getString("id"))
        assertEquals("out", sent.getString("direction"))
        assertEquals(200L, sent.getLong("storedAt"))
        assertFalse(sent.has("sentAt"))
        val received = messages.getJSONObject(1)
        assertEquals("1-100", received.getString("id"))
        assertEquals("in", received.getString("direction"))
        assertEquals("+15550001111", received.getString("address"))
        assertEquals("body-100", received.getString("body"))
        assertTrue(received.getBoolean("bodyAvailable"))
        assertEquals(90L, received.getLong("sentAt"))
        assertEquals("+15555215554", received.getString("phoneNumber"))
        assertEquals(1, received.getInt("subscriptionId"))
        assertEquals("7", received.getString("threadId"))
        assertTrue(received.getBoolean("read"))
    }

    @Test
    fun list_directionAndFiltersSelectRows() {
        provider.add(SmsProviderRow.TYPE_INBOX, date = 100, address = "+1555000A")
        provider.add(SmsProviderRow.TYPE_SENT, date = 200, address = "+1555000A")
        provider.add(SmsProviderRow.TYPE_INBOX, date = 300, address = "+1555000B")

        assertEquals(listOf("3-300", "1-100"), ids(api.handle("sms/list", JSONObject().put("direction", "in"))))
        assertEquals(listOf("2-200"), ids(api.handle("sms/list", JSONObject().put("direction", "out"))))
        assertEquals(
            listOf("2-200"),
            ids(api.handle("sms/list", JSONObject().put("since", 100).put("until", 200))),
        )
        assertEquals(
            listOf("3-300", "2-200"),
            ids(api.handle("sms/list", JSONObject().put("storedFrom", 200))),
        )
        assertEquals(
            listOf("2-200", "1-100"),
            ids(api.handle("sms/list", JSONObject().put("address", "+1555000A"))),
        )
    }

    @Test
    fun list_cursorChainIsStableWhileRowsArrive_desc() {
        (1..5).forEach { provider.add(SmsProviderRow.TYPE_INBOX, date = it * 100L) }

        val first = ok(api.handle("sms/list", JSONObject().put("limit", 2)))
        assertEquals(listOf("5-500", "4-400"), ids(first))
        provider.add(SmsProviderRow.TYPE_INBOX, date = 450)
        provider.add(SmsProviderRow.TYPE_INBOX, date = 50)

        val second = ok(api.handle("sms/list", JSONObject().put("limit", 2).put("cursor", first.getString("nextCursor"))))
        assertEquals(listOf("3-300", "2-200"), ids(second))
        val third = ok(api.handle("sms/list", JSONObject().put("limit", 2).put("cursor", second.getString("nextCursor"))))
        assertEquals(listOf("1-100"), ids(third))
        assertFalse(third.has("nextCursor"))
    }

    @Test
    fun list_cursorChainIsStableWhileRowsArrive_ascWithSameDates() {
        provider.add(SmsProviderRow.TYPE_INBOX, date = 100)
        provider.add(SmsProviderRow.TYPE_INBOX, date = 100)
        provider.add(SmsProviderRow.TYPE_INBOX, date = 100)
        provider.add(SmsProviderRow.TYPE_SENT, date = 200)

        val first = ok(api.handle("sms/list", JSONObject().put("limit", 2).put("order", "asc")))
        assertEquals(listOf("1-100", "2-100"), ids(first))
        provider.add(SmsProviderRow.TYPE_INBOX, date = 150)
        provider.add(SmsProviderRow.TYPE_INBOX, date = 900)

        val cursor = first.getString("nextCursor")
        val second = ok(api.handle("sms/list", JSONObject().put("limit", 2).put("order", "asc").put("cursor", cursor)))
        assertEquals(listOf("3-100", "4-200"), ids(second))
        assertFalse(second.has("nextCursor"))
    }

    @Test
    fun list_rejectsCursorOfAnotherOrderOrGarbage() {
        provider.add(SmsProviderRow.TYPE_INBOX, date = 100)
        provider.add(SmsProviderRow.TYPE_INBOX, date = 200)
        val cursor = ok(api.handle("sms/list", JSONObject().put("limit", 1))).getString("nextCursor")

        assertEquals(
            "sms: invalid_params: cursor is not a nextCursor of this order",
            error(api.handle("sms/list", JSONObject().put("order", "asc").put("cursor", cursor))),
        )
        assertEquals(
            "sms: invalid_params: cursor is not a nextCursor of this order",
            error(api.handle("sms/list", JSONObject().put("cursor", "not-base64!"))),
        )
    }

    @Test
    fun list_emptyProviderReturnsEmptyMessages() {
        val result = ok(api.handle("sms/list", JSONObject()))
        assertEquals(0, result.getJSONArray("messages").length())
        assertFalse(result.has("nextCursor"))
    }

    @Test
    fun list_requiresReadSmsAndReadPhoneState() {
        readSms = false
        assertEquals("sms: permission_denied: read_sms", error(api.handle("sms/list", JSONObject())))
        readSms = true
        subscriptions = SmsSubscriptionsResult.PermissionDenied
        assertEquals("sms: permission_denied: read_phone_state", error(api.handle("sms/list", JSONObject())))
    }

    @Test
    fun list_multiSimReturnsOnlyCloudSimRows() {
        subscriptions = SmsSubscriptionsResult.Available(listOf(SIM_A, SIM_B))
        provider.add(SmsProviderRow.TYPE_INBOX, date = 100, subscriptionId = 1)
        provider.add(SmsProviderRow.TYPE_INBOX, date = 200, subscriptionId = 2)

        assertEquals("sms: telephony_unavailable: no_cloud_sim", error(api.handle("sms/list", JSONObject())))
        cloudSubscriptionId = 2
        assertEquals(listOf("2-200"), ids(api.handle("sms/list", JSONObject())))
        cloudSubscriptionId = 9
        assertEquals("sms: telephony_unavailable: no_cloud_sim", error(api.handle("sms/list", JSONObject())))
    }

    @Test
    fun chosenCloudSimInactive_blocksEvenWithOneActiveSim() {
        // The owner picked SIM A; it was then removed, leaving only SIM B active.
        cloudSubscriptionId = 1
        subscriptions = SmsSubscriptionsResult.Available(listOf(SIM_B))
        provider.add(SmsProviderRow.TYPE_INBOX, date = 100, subscriptionId = 2)

        assertEquals("sms: telephony_unavailable: no_cloud_sim", error(api.handle("sms/list", JSONObject())))
        assertEquals("sms: telephony_unavailable: no_cloud_sim", error(api.handle("sms/get", JSONObject().put("id", "1-100"))))
        assertEquals("sms: telephony_unavailable: no_cloud_sim", error(api.handle("sms/subscriptions", JSONObject())))
        assertEquals("sms: telephony_unavailable: no_cloud_sim", error(api.handle("sms/send", sendParams(id = "a"))))
        assertEquals(
            "sms: permission_denied: not_cloud_sim",
            error(api.handle("sms/send", sendParams(id = "b").put("subscriptionId", 2))),
        )
        assertTrue(sender.sends.isEmpty())

        subscriptions = SmsSubscriptionsResult.Available(listOf(SIM_A))
        provider.add(SmsProviderRow.TYPE_INBOX, date = 200, subscriptionId = 1)
        assertEquals(listOf("2-200"), ids(api.handle("sms/list", JSONObject())))
    }

    @Test
    fun implicitSingleSim_hidesRowsOfOtherSubscriptions() {
        // No cloud SIM chosen; SIM A is the only active one and a removed SIM's rows remain.
        provider.add(SmsProviderRow.TYPE_INBOX, date = 100, subscriptionId = 2)
        provider.add(SmsProviderRow.TYPE_INBOX, date = 200, subscriptionId = 1)

        assertEquals(listOf("2-200"), ids(api.handle("sms/list", JSONObject())))
        assertEquals("sms: not_found", error(api.handle("sms/get", JSONObject().put("id", "1-100"))))
        assertEquals("2-200", ok(api.handle("sms/get", JSONObject().put("id", "2-200"))).getString("id"))
    }

    @Test
    fun noActiveSim_blocksListAndGet() {
        subscriptions = SmsSubscriptionsResult.Available(emptyList())
        provider.add(SmsProviderRow.TYPE_INBOX, date = 100, subscriptionId = 1)

        assertEquals("sms: telephony_unavailable: no_subscription", error(api.handle("sms/list", JSONObject())))
        assertEquals("sms: telephony_unavailable: no_subscription", error(api.handle("sms/get", JSONObject().put("id", "1-100"))))
        cloudSubscriptionId = 1
        assertEquals("sms: telephony_unavailable: no_cloud_sim", error(api.handle("sms/list", JSONObject())))
        assertEquals(0, provider.queries)
    }

    @Test
    fun list_invalidParamsNeverQueryTheProvider() {
        provider.add(SmsProviderRow.TYPE_INBOX, date = 100)
        assertEquals("sms: unsupported_param: \"services\"", error(api.handle("sms/list", JSONObject().put("services", JSONArray()))))
        assertEquals("sms: invalid_params: limit must be 1-200", error(api.handle("sms/list", JSONObject().put("limit", 201))))
        assertEquals(0, provider.queries)
    }

    // --- get ---

    @Test
    fun get_returnsMessageOrNotFound() {
        provider.add(SmsProviderRow.TYPE_INBOX, date = 100, subscriptionId = 1)
        provider.add(SmsProviderRow.TYPE_DRAFT, date = 200)

        assertEquals("1-100", ok(api.handle("sms/get", JSONObject().put("id", "1-100"))).getString("id"))
        listOf("1-101", "2-200", "9-100", "abc", "1").forEach {
            assertEquals(it, "sms: not_found", error(api.handle("sms/get", JSONObject().put("id", it))))
        }
        assertEquals("sms: invalid_params: id is required", error(api.handle("sms/get", JSONObject())))
    }

    @Test
    fun get_multiSimHidesOtherSimRows() {
        subscriptions = SmsSubscriptionsResult.Available(listOf(SIM_A, SIM_B))
        cloudSubscriptionId = 1
        provider.add(SmsProviderRow.TYPE_INBOX, date = 100, subscriptionId = 2)

        assertEquals("sms: not_found", error(api.handle("sms/get", JSONObject().put("id", "1-100"))))
    }

    // --- subscriptions ---

    @Test
    fun subscriptions_typedFailures() {
        subscriptions = SmsSubscriptionsResult.PermissionDenied
        assertEquals("sms: permission_denied: read_phone_state", error(api.handle("sms/subscriptions", JSONObject())))
        subscriptions = SmsSubscriptionsResult.Available(emptyList())
        assertEquals("sms: telephony_unavailable: no_subscription", error(api.handle("sms/subscriptions", JSONObject())))
    }

    @Test
    fun subscriptions_listsSingleSimAndOnlyTheCloudSimOnMultiSim() {
        subscriptions = SmsSubscriptionsResult.Available(listOf(SIM_A.copy(phoneNumber = "+15555215554")))
        val single = ok(api.handle("sms/subscriptions", JSONObject())).getJSONArray("subscriptions")
        assertEquals(1, single.length())
        assertEquals(1, single.getJSONObject(0).getInt("subscriptionId"))
        assertEquals(0, single.getJSONObject(0).getInt("simSlot"))
        assertEquals("+15555215554", single.getJSONObject(0).getString("phoneNumber"))
        assertEquals("Carrier A", single.getJSONObject(0).getString("carrier"))

        subscriptions = SmsSubscriptionsResult.Available(listOf(SIM_A, SIM_B))
        cloudSubscriptionId = 2
        val multi = ok(api.handle("sms/subscriptions", JSONObject())).getJSONArray("subscriptions")
        assertEquals(1, multi.length())
        assertEquals(2, multi.getJSONObject(0).getInt("subscriptionId"))
        assertFalse(multi.getJSONObject(0).has("phoneNumber"))
    }

    // --- send ---

    @Test
    fun send_persistsPreparedBeforeSendingAndDispatchedAfter() {
        val status = ok(api.handle("sms/send", sendParams(id = "s1")))

        assertEquals(listOf("insert:s1:prepared", "send:s1", "dispatched:s1"), store.events)
        assertEquals("s1", status.getString("id"))
        assertEquals("+15550002222", status.getString("to"))
        assertEquals("pending", status.getString("state"))
        assertEquals(1, status.getInt("parts"))
        assertEquals(0, status.getInt("sentParts"))
        assertEquals(now, status.getLong("createdAt"))
        assertEquals(1, status.getInt("subscriptionId"))
        assertEquals(0, status.getInt("simSlot"))
        assertFalse(status.has("error"))
        val record = store.records.getValue("s1")
        assertEquals(SmsSendPhase.DISPATCHED, record.phase)
        assertEquals(SmsHashes.fingerprint("+15550002222", "hello"), record.fingerprint)
        assertFalse(record.toString().contains("hello"))
    }

    @Test
    fun send_mintsIdWhenEmpty() {
        val status = ok(api.handle("sms/send", sendParams(id = "")))
        assertEquals("minted-1", status.getString("id"))
    }

    @Test
    fun send_knownIdReturnsStoredStatusAndNeverResendsEvenWhenFailed() {
        sender.sendException = IllegalStateException("radio off")
        val failed = ok(api.handle("sms/send", sendParams(id = "s1")))
        assertEquals("failed", failed.getString("state"))
        assertEquals("SEND_EXCEPTION", failed.getJSONObject("error").getString("code"))
        assertEquals("IllegalStateException", failed.getJSONObject("error").getString("message"))

        sender.sendException = null
        now += 5_000
        val retry = ok(api.handle("sms/send", sendParams(id = "s1")))

        assertEquals("failed", retry.getString("state"))
        assertEquals(failed.getLong("createdAt"), retry.getLong("createdAt"))
        assertEquals(1, store.events.count { it == "send:s1" })
    }

    @Test
    fun send_sameIdWithDifferentToOrBodyIsRejected_differentSenderReturnsStoredStatus() {
        subscriptions = SmsSubscriptionsResult.Available(listOf(SIM_A, SIM_B))
        cloudSubscriptionId = 1
        ok(api.handle("sms/send", sendParams(id = "s1")))

        assertEquals("sms: invalid_params: id reused", error(api.handle("sms/send", sendParams(id = "s1", body = "other"))))
        assertEquals("sms: invalid_params: id reused", error(api.handle("sms/send", sendParams(id = "s1", to = "+15550003333"))))
        val sameWithSender = ok(api.handle("sms/send", sendParams(id = "s1").put("subscriptionId", 2)))
        assertEquals("s1", sameWithSender.getString("id"))
        assertEquals(1, sender.sends.size)
    }

    @Test
    fun send_singleSimSenderRules() {
        ok(api.handle("sms/send", sendParams(id = "a")))
        ok(api.handle("sms/send", sendParams(id = "b").put("subscriptionId", 1)))
        ok(api.handle("sms/send", sendParams(id = "c").put("simSlot", 0)))
        assertEquals(
            "sms: telephony_unavailable: no_subscription",
            error(api.handle("sms/send", sendParams(id = "d").put("subscriptionId", 5))),
        )
        assertEquals(listOf(1, 1, 1), sender.sends.map { it.third })
    }

    @Test
    fun send_multiSimSenderRules() {
        subscriptions = SmsSubscriptionsResult.Available(listOf(SIM_A, SIM_B))

        assertEquals("sms: telephony_unavailable: no_cloud_sim", error(api.handle("sms/send", sendParams(id = "a"))))
        assertEquals(
            "sms: permission_denied: not_cloud_sim",
            error(api.handle("sms/send", sendParams(id = "b").put("subscriptionId", 2))),
        )
        cloudSubscriptionId = 2
        ok(api.handle("sms/send", sendParams(id = "c")))
        ok(api.handle("sms/send", sendParams(id = "d").put("simSlot", 1)))
        assertEquals(
            "sms: permission_denied: not_cloud_sim",
            error(api.handle("sms/send", sendParams(id = "e").put("subscriptionId", 1))),
        )
        assertEquals(
            "sms: permission_denied: not_cloud_sim",
            error(api.handle("sms/send", sendParams(id = "f").put("simSlot", 0))),
        )
        assertEquals(listOf(2, 2), sender.sends.map { it.third })
        assertEquals(setOf("c", "d"), store.records.keys)
    }

    @Test
    fun send_permissionAndTelephonyFailuresPersistNothing() {
        sendSms = false
        assertEquals("sms: permission_denied: send_sms", error(api.handle("sms/send", sendParams(id = "a"))))
        sendSms = true
        subscriptions = SmsSubscriptionsResult.PermissionDenied
        assertEquals("sms: permission_denied: read_phone_state", error(api.handle("sms/send", sendParams(id = "a"))))
        subscriptions = SmsSubscriptionsResult.Available(emptyList())
        assertEquals("sms: telephony_unavailable: no_subscription", error(api.handle("sms/send", sendParams(id = "a"))))
        assertTrue(store.records.isEmpty())
        assertTrue(sender.sends.isEmpty())
    }

    @Test
    fun send_rateLimitedPersistsNothing() {
        sender.parts = { body -> List(body.length) { "p" } }
        ok(api.handle("sms/send", sendParams(id = "a", body = "x".repeat(20))))

        assertEquals("sms: device_side_failure: rate_limited", error(api.handle("sms/send", sendParams(id = "b", body = "y".repeat(6)))))
        assertNull(store.records["b"])
        ok(api.handle("sms/send", sendParams(id = "c", body = "z".repeat(5))))
        limiterNow += 60_001
        ok(api.handle("sms/send", sendParams(id = "b", body = "y".repeat(6))))
        assertEquals(listOf("a", "c", "b"), sender.sends.map { it.first })
    }

    @Test
    fun send_localApiPartsCountTowardTheLimit() {
        limiter.record(24)
        sender.parts = { listOf("x", "y") }

        assertEquals("sms: device_side_failure: rate_limited", error(api.handle("sms/send", sendParams(id = "a"))))
        assertTrue(store.records.isEmpty())
    }

    @Test
    fun send_storeFailureIsDeviceSideFailureAndRefundsParts() {
        store.failInsert = true
        assertEquals("sms: device_side_failure: store unavailable", error(api.handle("sms/send", sendParams(id = "a"))))
        assertTrue(sender.sends.isEmpty())
        assertEquals(0, limiter.used())
    }

    @Test
    fun send_dispatchThatThrows_refundsParts() {
        sender.parts = { listOf("x", "y") }
        sender.sendException = IllegalStateException("radio off")
        assertEquals("failed", ok(api.handle("sms/send", sendParams(id = "a"))).getString("state"))
        assertEquals(0, limiter.used())
    }

    @Test
    fun send_storeFullOfOutstandingSends_refusesWithoutSendingOrDiscarding() {
        store.full = true
        assertEquals("sms: device_side_failure: store_full", error(api.handle("sms/send", sendParams(id = "a"))))
        assertTrue(sender.sends.isEmpty())
        assertTrue(store.events.isEmpty())
        assertEquals(0, limiter.used())

        store.full = false
        assertEquals("pending", ok(api.handle("sms/send", sendParams(id = "a"))).getString("state"))
        assertEquals(listOf("a"), sender.sends.map { it.first })
    }

    @Test
    fun send_insertThatLandsAfterAReportedFailure_isDiscarded_soARetrySends() {
        store.insertLandsThenFails = true
        assertEquals("sms: device_side_failure: store unavailable", error(api.handle("sms/send", sendParams(id = "a"))))
        assertNull(store.records["a"])
        assertEquals(0, limiter.used())

        store.insertLandsThenFails = false
        assertEquals("pending", ok(api.handle("sms/send", sendParams(id = "a"))).getString("state"))
        assertEquals(listOf("a"), sender.sends.map { it.first })
        assertEquals(SmsSendPhase.DISPATCHED, store.records["a"]!!.phase)
    }

    @Test
    fun sendStatus_returnsStoredRecordOrNotFound() {
        ok(api.handle("sms/send", sendParams(id = "s1")))
        assertEquals("pending", ok(api.handle("sms/sendStatus", JSONObject().put("id", "s1"))).getString("state"))
        assertEquals("sms: not_found", error(api.handle("sms/sendStatus", JSONObject().put("id", "nope"))))
        assertEquals(
            "sms: unsupported_param: \"message_id\"",
            error(api.handle("sms/sendStatus", JSONObject().put("message_id", "s1"))),
        )
    }

    @Test
    fun deviceExceptionsBecomeDeviceSideFailureWithoutDetailsFromTheException() {
        val throwing = object : SmsProviderSource by provider {
            override fun latest(): SmsProviderRow? = throw SecurityException("content://sms +15550001111")
        }
        val failing = SmsBridgeApi(
            cloudEnabled = { true },
            cloudSubscriptionId = { null },
            hasTelephony = { true },
            hasReadSms = { true },
            hasSendSms = { true },
            subscriptions = { subscriptions },
            provider = throwing,
            records = store,
            sender = sender,
            limiter = limiter,
        )
        assertEquals("sms: device_side_failure: SecurityException", error(failing.handle("sms/list", JSONObject())))
    }

    private fun sendParams(id: String, to: String = "+15550002222", body: String = "hello"): JSONObject =
        JSONObject().put("id", id).put("to", to).put("body", body).put("deliveryReport", false)

    private fun ok(result: SmsBridgeResult): JSONObject {
        assertTrue("expected ok, got $result", result is SmsBridgeResult.Ok)
        return (result as SmsBridgeResult.Ok).json
    }

    private fun error(result: SmsBridgeResult): String {
        assertTrue("expected failure, got $result", result is SmsBridgeResult.Failure)
        return (result as SmsBridgeResult.Failure).error.wire
    }

    private fun ids(result: SmsBridgeResult): List<String> = ids(ok(result))

    private fun ids(json: JSONObject): List<String> {
        val messages = json.getJSONArray("messages")
        return (0 until messages.length()).map { messages.getJSONObject(it).getString("id") }
    }

    companion object {
        private val SIM_A = SmsSimInfo(subscriptionId = 1, simSlot = 0, carrier = "Carrier A", displayName = "SIM A")
        private val SIM_B = SmsSimInfo(subscriptionId = 2, simSlot = 1, carrier = "Carrier B", displayName = "SIM B")
    }
}
