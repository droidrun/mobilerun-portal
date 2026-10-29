package com.mobilerun.portal.sms

import io.mockk.every
import io.mockk.mockk
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SmsGatewayTest {
    private var now = 1_000L
    private lateinit var store: SmsStateStore
    private lateinit var sender: FakeSmsSender
    private lateinit var resolver: SmsSubscriptionResolver
    private lateinit var gateway: SmsGateway

    @Before
    fun setUp() {
        store = SmsStateStore(nowProvider = { now })
        sender = FakeSmsSender()
        resolver = mockk()
        every { resolver.hasTelephony() } returns true
        every { resolver.hasSendSms() } returns true
        every { resolver.resolve(null, null) } returns SmsSubscriptionResolution(
            subscriptionId = null,
            simSlot = null,
        )
        every { resolver.resolve(4, null) } returns SmsSubscriptionResolution(
            subscriptionId = 4,
            simSlot = null,
        )
        every { resolver.resolve(null, 0) } returns SmsSubscriptionResolution(
            subscriptionId = null,
            simSlot = 0,
        )
        gateway = SmsGateway(store, sender, resolver)
    }

    @Test
    fun send_dividesStoresAndSendsMultipartMessage() {
        sender.parts = listOf("part-1", "part-2")

        val response = gateway.send(sendParams(messageId = "msg-1", subscriptionId = 4))

        assertTrue(response.ok)
        val json = response.json!!
        assertEquals("msg-1", json.getString("message_id"))
        assertEquals("PENDING", json.getString("state"))
        assertEquals(2, json.getInt("part_count"))
        assertEquals(1, sender.divideCalls)
        assertEquals(1, sender.sendCalls)
        assertEquals(listOf("part-1", "part-2"), sender.lastParts)
        assertEquals("+15550001111", sender.lastRequest?.phoneNumber)
    }

    @Test
    fun send_returnsExistingStatusWithoutResendingDuplicateMessageId() {
        val params = sendParams(messageId = "same-id")

        val first = gateway.send(params)
        now += 1_000L
        val second = gateway.send(params)

        assertTrue(first.ok)
        assertTrue(second.ok)
        assertEquals(1, sender.sendCalls)
        assertEquals(first.json!!.getLong("created_at"), second.json!!.getLong("created_at"))
    }

    @Test
    fun send_retriesSameMessageIdAfterFailedSend() {
        sender.sendException = IllegalStateException("radio off")
        val params = sendParams(messageId = "retry-id")

        val failed = gateway.send(params)
        now += 1_000L
        sender.sendException = null
        val retry = gateway.send(params)

        assertTrue(failed.ok)
        assertEquals("FAILED", failed.json!!.getString("state"))
        assertTrue(retry.ok)
        assertEquals("PENDING", retry.json!!.getString("state"))
        assertEquals(2, sender.sendCalls)
    }

    @Test
    fun send_returnsTypedErrorWhenSendSmsPermissionMissing() {
        every { resolver.hasSendSms() } returns false

        val response = gateway.send(sendParams())

        assertFalse(response.ok)
        assertEquals("SEND_SMS permission not granted", response.error)
        assertEquals(0, sender.sendCalls)
    }

    @Test
    fun send_returnsTypedErrorWhenTelephonyMissing() {
        every { resolver.hasTelephony() } returns false

        val response = gateway.send(sendParams())

        assertFalse(response.ok)
        assertEquals("Device does not report telephony support", response.error)
        assertEquals(0, sender.sendCalls)
    }

    @Test
    fun send_returnsResolverError() {
        every { resolver.resolve(null, 0) } returns SmsSubscriptionResolution(
            subscriptionId = null,
            simSlot = 0,
            error = "READ_PHONE_STATE permission not granted",
        )

        val response = gateway.send(sendParams(simSlot = 0))

        assertFalse(response.ok)
        assertEquals("READ_PHONE_STATE permission not granted", response.error)
        assertEquals(0, sender.sendCalls)
    }

    @Test
    fun send_returnsDivideMessageError() {
        sender.divideException = IllegalStateException("bad encoding")

        val response = gateway.send(sendParams())

        assertFalse(response.ok)
        assertEquals("Failed to divide SMS message: bad encoding", response.error)
        assertEquals(0, sender.sendCalls)
    }

    @Test
    fun send_rejectsNonIntegerSimSlot() {
        val response = gateway.send(sendParams().put("sim_slot", "abc"))

        assertFalse(response.ok)
        assertEquals("Invalid integer param: 'sim_slot'", response.error)
        assertEquals(0, sender.sendCalls)
    }

    @Test
    fun send_rejectsNegativeSubscriptionId() {
        val response = gateway.send(sendParams().put("subscription_id", -1))

        assertFalse(response.ok)
        assertEquals("Invalid integer param: 'subscription_id' must be >= 0", response.error)
        assertEquals(0, sender.sendCalls)
    }

    @Test
    fun send_validatesRequiredParamsBeforeSend() {
        val response = gateway.send(JSONObject().put("phone_number", "").put("message", ""))

        assertFalse(response.ok)
        assertEquals("Missing required params: 'phone_number' and 'message'", response.error)
        assertEquals(0, sender.sendCalls)
    }

    private fun sendParams(
        messageId: String = "msg-1",
        subscriptionId: Int? = null,
        simSlot: Int? = null,
    ): JSONObject =
        JSONObject()
            .put("phone_number", "+15550001111")
            .put("message", "hello")
            .put("message_id", messageId)
            .put("delivery_report", true)
            .apply {
                subscriptionId?.let { put("subscription_id", it) }
                simSlot?.let { put("sim_slot", it) }
            }

    private class FakeSmsSender : SmsSender {
        var parts = listOf("hello")
        var divideCalls = 0
        var sendCalls = 0
        var divideException: Exception? = null
        var sendException: Exception? = null
        var lastRequest: SmsSendRequest? = null
        var lastParts: List<String> = emptyList()
        var lastSubscriptionId: Int? = null
        var lastCallbackToken: String? = null

        override fun divideMessage(message: String, subscriptionId: Int?): List<String> {
            divideCalls += 1
            divideException?.let { throw it }
            return parts
        }

        override fun sendMultipart(
            request: SmsSendRequest,
            parts: List<String>,
            resolvedSubscriptionId: Int?,
            callbackToken: String,
        ) {
            sendCalls += 1
            lastRequest = request
            lastParts = parts
            lastSubscriptionId = resolvedSubscriptionId
            lastCallbackToken = callbackToken
            sendException?.let { throw it }
        }
    }
}
