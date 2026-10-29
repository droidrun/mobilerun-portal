package com.mobilerun.portal.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmsStateStoreTest {
    @Test
    fun createOrGet_reusesSameMessageIdForSameFingerprint() {
        var now = 1_000L
        val store = SmsStateStore(
            maxRecords = 10,
            ttlMillis = 10_000L,
            nowProvider = { now },
        )
        val request = SmsSendRequest(
            messageId = "msg-1",
            phoneNumber = "+15550001111",
            message = "hello",
            subscriptionId = null,
            simSlot = null,
            deliveryReport = true,
        )

        val first = store.createOrGet(request, partCount = 2)
        now += 1_000L
        val second = store.createOrGet(request, partCount = 2)

        assertEquals(first.status, second.status)
        assertEquals(first.callbackToken, second.callbackToken)
        val status = second.status!!
        assertEquals(SmsSendState.PENDING, status.state)
        assertEquals(2, status.partCount)
    }

    @Test
    fun createOrGet_rejectsSameMessageIdWithDifferentFingerprint() {
        val store = SmsStateStore(nowProvider = { 1_000L })
        val first = SmsSendRequest(
            messageId = "msg-1",
            phoneNumber = "+15550001111",
            message = "hello",
            subscriptionId = null,
            simSlot = null,
            deliveryReport = true,
        )
        val second = first.copy(message = "different")

        store.createOrGet(first, partCount = 1)
        val result = store.createOrGet(second, partCount = 1)

        assertEquals(false, result.created)
        assertEquals("message_id already exists with different request data", result.error)
    }

    @Test
    fun createOrGet_replacesFailedRecordForSameFingerprint() {
        var now = 1_000L
        val store = SmsStateStore(nowProvider = { now })
        val request = SmsSendRequest(
            messageId = "msg-retry",
            phoneNumber = "+15550001111",
            message = "hello",
            subscriptionId = null,
            simSlot = null,
            deliveryReport = true,
        )
        val first = store.createOrGet(request, partCount = 1)
        store.markFailed("msg-retry", "SEND_EXCEPTION", "radio off")

        now += 1_000L
        val retried = store.createOrGet(request, partCount = 2)

        assertEquals(true, retried.created)
        assertNotEquals(first.callbackToken, retried.callbackToken)
        assertEquals(SmsSendState.PENDING, retried.status?.state)
        assertEquals(2, retried.status?.partCount)
        assertNull(retried.status?.errorCode)
    }

    @Test
    fun markPartSentAndDelivered_aggregatesMultipartState() {
        var now = 1_000L
        val store = SmsStateStore(nowProvider = { now })
        val request = SmsSendRequest(
            messageId = "msg-2",
            phoneNumber = "+15550002222",
            message = "long body",
            subscriptionId = 3,
            simSlot = null,
            deliveryReport = true,
        )
        store.createOrGet(request, partCount = 2)

        now += 100L
        val afterOneSent = store.markPartSent("msg-2", partIndex = 0)
        now += 100L
        val afterAllSent = store.markPartSent("msg-2", partIndex = 1)
        now += 100L
        val afterDelivered = store.markPartDelivered("msg-2", partIndex = 0)

        assertEquals(SmsSendState.PENDING, afterOneSent?.state)
        assertEquals(1, afterOneSent?.sentParts)
        assertEquals(SmsSendState.SENT, afterAllSent?.state)
        assertEquals(2, afterAllSent?.sentParts)
        assertEquals(SmsSendState.SENT, afterDelivered?.state)
        assertEquals(1, afterDelivered?.deliveredParts)

        val afterAllDelivered = store.markPartDelivered("msg-2", partIndex = 1)
        assertEquals(SmsSendState.DELIVERED, afterAllDelivered?.state)
        assertEquals(2, afterAllDelivered?.deliveredParts)
    }

    @Test
    fun markPartSent_doesNotDowngradeDeliveredState() {
        val store = SmsStateStore(nowProvider = { 1_000L })
        val request = SmsSendRequest(
            messageId = "msg-delivered-first",
            phoneNumber = "+15550002222",
            message = "hello",
            subscriptionId = null,
            simSlot = null,
            deliveryReport = true,
        )
        store.createOrGet(request, partCount = 1)

        val delivered = store.markPartDelivered("msg-delivered-first", partIndex = 0)
        val sentAfterDelivery = store.markPartSent("msg-delivered-first", partIndex = 0)

        assertEquals(SmsSendState.DELIVERED, delivered?.state)
        assertNull(sentAfterDelivery)
        val stored = store.get("msg-delivered-first")
        assertEquals(SmsSendState.DELIVERED, stored?.state)
        assertEquals(1, stored?.sentParts)
        assertEquals(1, stored?.deliveredParts)
    }

    @Test
    fun callbackTokenMismatch_doesNotMutateCurrentRecord() {
        val store = SmsStateStore(nowProvider = { 1_000L })
        val request = SmsSendRequest(
            messageId = "msg-token",
            phoneNumber = "+15550002222",
            message = "hello",
            subscriptionId = null,
            simSlot = null,
            deliveryReport = true,
        )
        val created = store.createOrGet(request, partCount = 1)

        val ignored = store.markPartSent("msg-token", partIndex = 0, callbackToken = "wrong-token")

        assertNull(ignored)
        assertEquals(SmsSendState.PENDING, store.get("msg-token")?.state)
        val accepted = store.markPartSent("msg-token", partIndex = 0, callbackToken = created.callbackToken)
        assertEquals(SmsSendState.SENT, accepted?.state)
    }

    @Test
    fun markFailed_doesNotDowngradeDeliveredState() {
        val store = SmsStateStore(nowProvider = { 1_000L })
        val request = SmsSendRequest(
            messageId = "msg-delivered-failure",
            phoneNumber = "+15550002222",
            message = "hello",
            subscriptionId = null,
            simSlot = null,
            deliveryReport = true,
        )
        store.createOrGet(request, partCount = 1)
        store.markPartDelivered("msg-delivered-failure", partIndex = 0)

        val failedAfterDelivery = store.markFailed(
            "msg-delivered-failure",
            "DELIVERY_FAILED",
            "late duplicate failure",
        )

        assertNull(failedAfterDelivery)
        assertEquals(SmsSendState.DELIVERED, store.get("msg-delivered-failure")?.state)
        assertNull(store.get("msg-delivered-failure")?.errorCode)
    }

    @Test
    fun markFailed_recordsErrorAndStopsFurtherSuccessTransitions() {
        val store = SmsStateStore(nowProvider = { 1_000L })
        val request = SmsSendRequest(
            messageId = "msg-3",
            phoneNumber = "+15550003333",
            message = "hello",
            subscriptionId = null,
            simSlot = null,
            deliveryReport = false,
        )
        store.createOrGet(request, partCount = 1)

        val failed = store.markFailed("msg-3", "RESULT_ERROR_GENERIC_FAILURE", "generic failure")
        val sentAfterFailure = store.markPartSent("msg-3", partIndex = 0)

        assertEquals(SmsSendState.FAILED, failed?.state)
        assertEquals("RESULT_ERROR_GENERIC_FAILURE", failed?.errorCode)
        assertNull(sentAfterFailure)
        assertEquals(SmsSendState.FAILED, store.get("msg-3")?.state)
    }

    @Test
    fun cleanup_removesExpiredAndOldestRecords() {
        var now = 1_000L
        val store = SmsStateStore(
            maxRecords = 2,
            ttlMillis = 1_000L,
            nowProvider = { now },
        )

        val first = request("msg-1")
        val second = request("msg-2")
        val third = request("msg-3")
        store.createOrGet(first, partCount = 1)
        now += 100L
        store.createOrGet(second, partCount = 1)
        now += 100L
        store.createOrGet(third, partCount = 1)

        assertNull(store.get("msg-1"))
        assertNotEquals(null, store.get("msg-2"))
        assertNotEquals(null, store.get("msg-3"))

        now += 2_000L
        store.cleanup()
        assertNull(store.get("msg-2"))
        assertNull(store.get("msg-3"))
    }

    private fun request(id: String): SmsSendRequest =
        SmsSendRequest(
            messageId = id,
            phoneNumber = "+15550000000",
            message = "hello $id",
            subscriptionId = null,
            simSlot = null,
            deliveryReport = true,
        )

}
