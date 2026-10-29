package com.mobilerun.portal.sms

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsReaderTest {
    @Test
    fun listMessages_rejectsWhenReadSmsPermissionMissing() {
        val reader = SmsReader(
            permissionChecker = { false },
            source = FakeSmsMessageSource(emptyList()),
        )

        val response = reader.listMessages(JSONObject())

        assertEquals(false, response.ok)
        assertEquals("READ_SMS permission not granted", response.error)
    }

    @Test
    fun listMessages_filtersBoxSincePhoneAndLimit() {
        val messages = listOf(
            stored("1", SmsBox.INBOX, "+15550001111", "first", date = 1000L),
            stored("2", SmsBox.SENT, "+15550001111", "second", date = 2000L),
            stored("3", SmsBox.INBOX, "+15550002222", "third", date = 3000L),
            stored("4", SmsBox.INBOX, "+15550001111", "fourth", date = 4000L),
        )
        val reader = SmsReader(
            permissionChecker = { true },
            source = FakeSmsMessageSource(messages),
        )
        val params = JSONObject()
            .put("box", "inbox")
            .put("since", 1500L)
            .put("phone_number", "+15550001111")
            .put("limit", 1)

        val response = reader.listMessages(params)

        assertEquals(true, response.ok)
        val result = response.json!!
        assertEquals(1, result.getJSONArray("messages").length())
        assertEquals("4", result.getJSONArray("messages").getJSONObject(0).getString("sms_id"))
        assertEquals("inbox", result.getString("box"))
        assertEquals(1, result.getInt("limit"))
    }

    @Test
    fun getMessage_returnsNotFoundForMissingId() {
        val reader = SmsReader(
            permissionChecker = { true },
            source = FakeSmsMessageSource(emptyList()),
        )

        val response = reader.getMessage(JSONObject().put("sms_id", "missing"))

        assertEquals(false, response.ok)
        assertEquals("SMS message not found: missing", response.error)
    }

    @Test
    fun listMessages_clampsLimitToSupportedRange() {
        val reader = SmsReader(
            permissionChecker = { true },
            source = FakeSmsMessageSource(
                (1..250).map { index ->
                    stored(index.toString(), SmsBox.INBOX, "+1555", "m$index", date = index.toLong())
                },
            ),
        )

        val response = reader.listMessages(JSONObject().put("limit", 500))

        assertEquals(true, response.ok)
        val json = response.json!!
        assertEquals(200, json.getInt("limit"))
        assertEquals(200, json.getJSONArray("messages").length())
        assertTrue(
            json
                .getJSONArray("messages")
                .getJSONObject(0)
                .getLong("date") > json
                .getJSONArray("messages")
                .getJSONObject(199)
                .getLong("date"),
        )
    }

    private fun stored(
        id: String,
        box: SmsBox,
        phoneNumber: String,
        body: String,
        date: Long,
    ): SmsStoredMessage =
        SmsStoredMessage(
            smsId = id,
            box = box,
            phoneNumber = phoneNumber,
            message = body,
            date = date,
            dateSent = date - 10,
            read = box == SmsBox.INBOX,
            threadId = "thread-$id",
            subscriptionId = null,
        )

    private class FakeSmsMessageSource(
        private val messages: List<SmsStoredMessage>,
    ) : SmsMessageSource {
        override fun listMessages(request: SmsListRequest): List<SmsStoredMessage> =
            messages
                .filter { request.box == SmsBox.ALL || it.box == request.box }
                .filter {
                    val since = request.since
                    since == null || it.date >= since
                }
                .filter { request.phoneNumber == null || it.phoneNumber == request.phoneNumber }
                .sortedByDescending { it.date }
                .take(request.limit)

        override fun getMessage(smsId: String): SmsStoredMessage? =
            messages.firstOrNull { it.smsId == smsId }
    }
}
