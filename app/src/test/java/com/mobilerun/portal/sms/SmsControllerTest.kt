package com.mobilerun.portal.sms

import com.mobilerun.portal.api.ApiResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SmsControllerTest {
    private var enabled = false
    private lateinit var sender: RecordingSmsSender
    private lateinit var source: RecordingMessageSource
    private lateinit var resolver: SmsSubscriptionResolver
    private lateinit var controller: SmsController

    @Before
    fun setUp() {
        sender = RecordingSmsSender()
        source = RecordingMessageSource()
        resolver = mockk()
        every { resolver.hasTelephony() } returns true
        every { resolver.hasSendSms() } returns true
        every { resolver.resolve(null, null) } returns SmsSubscriptionResolution(
            subscriptionId = null,
            simSlot = null,
        )
        every { resolver.capabilities() } answers {
            JSONObject().put("send_sms_granted", true).put("active_subscriptions", JSONArray())
        }
        controller = SmsController(
            context = mockk(relaxed = true),
            gateway = SmsGateway(SmsStateStore(), sender, resolver),
            reader = SmsReader(permissionChecker = { true }, source = source),
            gatewayEnabled = { enabled },
        )
    }

    @Test
    fun disabledGateway_rejectsSendStatusAndReadsWithoutTouchingSmsApis() {
        val responses = listOf(
            controller.send(sendParams()),
            controller.status(JSONObject().put("message_id", "msg-1")),
            controller.listMessages(JSONObject().put("box", "inbox")),
            controller.getMessage(JSONObject().put("sms_id", "1")),
        )

        responses.forEach {
            assertEquals(ApiResponse.Error(SmsController.GATEWAY_DISABLED), it)
        }
        assertEquals(0, sender.divideCalls)
        assertEquals(0, sender.sendCalls)
        assertEquals(0, source.listCalls)
        assertEquals(0, source.getCalls)
        verify(exactly = 0) { resolver.hasSendSms() }
        verify(exactly = 0) { resolver.resolve(any(), any()) }
    }

    @Test
    fun enabledGateway_passesRequestsThrough() {
        enabled = true

        val sent = controller.send(sendParams()) as ApiResponse.RawObject
        val status = controller.status(JSONObject().put("message_id", "msg-1")) as ApiResponse.RawObject
        val listed = controller.listMessages(JSONObject().put("box", "inbox")) as ApiResponse.RawObject
        val fetched = controller.getMessage(JSONObject().put("sms_id", "1")) as ApiResponse.RawObject

        assertEquals("PENDING", sent.json.getString("state"))
        assertEquals(1, sender.sendCalls)
        assertEquals("msg-1", status.json.getString("message_id"))
        assertEquals(1, listed.json.getJSONArray("messages").length())
        assertEquals("1", fetched.json.getString("sms_id"))
        assertEquals(1, source.listCalls)
        assertEquals(1, source.getCalls)
    }

    @Test
    fun capabilities_reportsGatewaySwitchInBothStates() {
        val disabled = controller.capabilities() as ApiResponse.RawObject
        enabled = true
        val enabledResult = controller.capabilities() as ApiResponse.RawObject

        assertFalse(disabled.json.getBoolean("gateway_enabled"))
        assertTrue(disabled.json.getBoolean("send_sms_granted"))
        assertTrue(enabledResult.json.getBoolean("gateway_enabled"))
    }

    private fun sendParams(): JSONObject =
        JSONObject()
            .put("phone_number", "+15550001111")
            .put("message", "hello")
            .put("message_id", "msg-1")

    private class RecordingSmsSender : SmsSender {
        var divideCalls = 0
        var sendCalls = 0

        override fun divideMessage(message: String, subscriptionId: Int?): List<String> {
            divideCalls += 1
            return listOf(message)
        }

        override fun sendMultipart(
            request: SmsSendRequest,
            parts: List<String>,
            resolvedSubscriptionId: Int?,
            callbackToken: String,
        ) {
            sendCalls += 1
        }
    }

    private class RecordingMessageSource : SmsMessageSource {
        var listCalls = 0
        var getCalls = 0
        private val message = SmsStoredMessage(
            smsId = "1",
            box = SmsBox.INBOX,
            phoneNumber = "+15550001111",
            message = "hello",
            date = 1_000L,
            dateSent = null,
            read = null,
            threadId = null,
            subscriptionId = null,
        )

        override fun listMessages(request: SmsListRequest): List<SmsStoredMessage> {
            listCalls += 1
            return listOf(message)
        }

        override fun getMessage(smsId: String): SmsStoredMessage? {
            getCalls += 1
            return message.takeIf { it.smsId == smsId }
        }
    }
}
