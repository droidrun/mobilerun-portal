package com.mobilerun.portal.sms

import android.app.Activity
import android.telephony.SmsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsSendRecordTest {
    @Test
    fun parts_moveRecordToSentThenTerminal() {
        val record = record(parts = 2, deliveryReport = false)

        val one = record.withPartSent(0, 10)!!
        assertEquals(SmsSendState.PENDING, one.state)
        assertEquals(SmsSendPhase.DISPATCHED, one.phase)
        assertNull(one.withPartSent(0, 11))
        assertNull(one.withPartSent(2, 11))
        val both = one.withPartSent(1, 12)!!
        assertEquals(SmsSendState.SENT, both.state)
        assertEquals(SmsSendPhase.TERMINAL, both.phase)
        assertEquals(12L, both.updatedAt)
    }

    @Test
    fun deliveryReport_keepsSentOpenUntilDelivered() {
        val sent = record(parts = 1, deliveryReport = true).withPartSent(0, 10)!!
        assertEquals(SmsSendState.SENT, sent.state)
        assertEquals(SmsSendPhase.DISPATCHED, sent.phase)
        val delivered = sent.withPartDelivered(0, 20)!!
        assertEquals(SmsSendState.DELIVERED, delivered.state)
        assertEquals(SmsSendPhase.TERMINAL, delivered.phase)
        assertNull(delivered.withFailure("X", "late", 30))
    }

    @Test
    fun failure_isTerminalAndFinal() {
        val failed = record(parts = 2).withFailure("RESULT_ERROR_RADIO_OFF", "Radio is off", 10)!!
        assertEquals(SmsSendState.FAILED, failed.state)
        assertEquals(SmsSendPhase.TERMINAL, failed.phase)
        assertNull(failed.withPartSent(0, 11))
        assertNull(failed.withFailure("X", "again", 11))
        val error = failed.toBridgeStatus().getJSONObject("error")
        assertEquals("RESULT_ERROR_RADIO_OFF", error.getString("code"))
        assertEquals("Radio is off", error.getString("message"))
    }

    @Test
    fun dispatched_neverRegressesACallbackThatArrivedFirst() {
        val prepared = record(parts = 1, deliveryReport = false)
        assertEquals(SmsSendPhase.DISPATCHED, prepared.withDispatched(5)!!.phase)
        val sentFirst = prepared.withPartSent(0, 5)!!
        assertNull(sentFirst.withDispatched(6))
    }

    @Test
    fun callback_mapsResultAndErrorCodesAndStoresUri() {
        val record = record(parts = 1)

        val failed = SmsSendCallbacks.apply(
            record,
            callback(resultOk = false, resultCode = SmsManager.RESULT_ERROR_LIMIT_EXCEEDED, errorCode = 7, uri = "content://sms/41"),
            now = 10,
        )!!
        assertEquals(SmsSendState.FAILED, failed.state)
        assertEquals("RESULT_ERROR_LIMIT_EXCEEDED", failed.errorCode)
        assertEquals("SMS send limit exceeded (errorCode=7)", failed.errorMessage)
        assertEquals("content://sms/41", failed.messageUri)

        val sent = SmsSendCallbacks.apply(record, callback(resultOk = true, resultCode = Activity.RESULT_OK), now = 10)!!
        assertEquals(SmsSendState.SENT, sent.state)
        assertNull(sent.messageUri)
    }

    @Test
    fun callback_ignoresForeignTokenAndTemporaryDeliveryStatus() {
        val record = record(parts = 1, deliveryReport = true)
        assertNull(SmsSendCallbacks.apply(record, callback(token = "other"), now = 10))
        val temporary = callback().copy(sent = false, deliveryOutcome = SmsDeliveryOutcome.TEMPORARY_FAILURE)
        assertNull(SmsSendCallbacks.apply(record, temporary, now = 10))
        val permanent = callback().copy(sent = false, deliveryOutcome = SmsDeliveryOutcome.PERMANENT_FAILURE, deliveryStatus = 0x40)
        assertEquals("DELIVERY_FAILED", SmsSendCallbacks.apply(record, permanent, now = 10)!!.errorCode)
    }

    @Test
    fun callback_uriAloneStillUpdatesTheRecord() {
        val sent = record(parts = 2).withPartSent(0, 1)!!
        val duplicate = SmsSendCallbacks.apply(sent, callback(partIndex = 0, uri = "content://sms/9"), now = 2)!!
        assertEquals("content://sms/9", duplicate.messageUri)
        assertEquals(setOf(0), duplicate.sentParts)
    }

    @Test
    fun providerIdFromUri_acceptsSmsUrisOnly() {
        assertEquals(41L, SmsSendCallbacks.providerIdFromUri("content://sms/41"))
        assertEquals(41L, SmsSendCallbacks.providerIdFromUri("content://sms/sent/41"))
        listOf(null, "", "content://mms/41", "content://sms/", "content://sms/abc", "file://sms/1").forEach {
            assertNull(it, SmsSendCallbacks.providerIdFromUri(it))
        }
    }

    @Test
    fun bridgeStatus_hasWireKeysAndNoError() {
        val json = record(parts = 3).copy(subscriptionId = 4, simSlot = 1).toBridgeStatus()
        assertEquals(
            setOf("id", "to", "state", "parts", "sentParts", "deliveredParts", "createdAt", "updatedAt", "subscriptionId", "simSlot"),
            json.keys().asSequence().toSet(),
        )
        assertEquals("pending", json.getString("state"))
        assertTrue(json.getInt("parts") == 3)
    }

    private fun callback(
        resultOk: Boolean = true,
        resultCode: Int = Activity.RESULT_OK,
        errorCode: Int = -1,
        uri: String? = null,
        token: String = "token",
        partIndex: Int = 0,
    ) = SmsSendCallback(
        sent = true,
        recordId = "r1",
        partIndex = partIndex,
        callbackToken = token,
        resultOk = resultOk,
        resultCode = resultCode,
        errorCode = errorCode,
        providerUri = uri,
    )

    companion object {
        fun record(parts: Int, deliveryReport: Boolean = false, createdAt: Long = 0L) = SmsSendRecord(
            id = "r1",
            origin = SmsSendRecord.ORIGIN_RELAY,
            to = "+15550001111",
            fingerprint = SmsHashes.fingerprint("+15550001111", "hello"),
            bodyHash = SmsHashes.bodyHash("hello"),
            subscriptionId = null,
            simSlot = null,
            deliveryReport = deliveryReport,
            phase = SmsSendPhase.PREPARED,
            state = SmsSendState.PENDING,
            parts = parts,
            sentParts = emptySet(),
            deliveredParts = emptySet(),
            callbackToken = "token",
            createdAt = createdAt,
            updatedAt = createdAt,
        )
    }
}
