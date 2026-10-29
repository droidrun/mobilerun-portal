package com.mobilerun.portal.sms

import java.security.MessageDigest
import java.util.UUID

class SmsStateStore(
    private val maxRecords: Int = DEFAULT_MAX_RECORDS,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
) {
    private data class Record(
        val fingerprint: String,
        val callbackToken: String,
        var status: SmsSendStatus,
        val sentParts: MutableSet<Int> = linkedSetOf(),
        val deliveredParts: MutableSet<Int> = linkedSetOf(),
    )

    private val records = LinkedHashMap<String, Record>()

    @Synchronized
    fun createOrGet(request: SmsSendRequest, partCount: Int): SmsStoreCreateResult {
        cleanupLocked()
        val fingerprint = fingerprint(request)
        records[request.messageId]?.let { existing ->
            return if (existing.fingerprint == fingerprint) {
                if (existing.status.state == SmsSendState.FAILED) {
                    createLocked(request, partCount, fingerprint)
                } else {
                    SmsStoreCreateResult(
                        created = false,
                        status = existing.status,
                        callbackToken = existing.callbackToken,
                    )
                }
            } else {
                SmsStoreCreateResult(
                    created = false,
                    status = existing.status,
                    callbackToken = existing.callbackToken,
                    error = "message_id already exists with different request data",
                )
            }
        }

        return createLocked(request, partCount, fingerprint)
    }

    private fun createLocked(
        request: SmsSendRequest,
        partCount: Int,
        fingerprint: String,
    ): SmsStoreCreateResult {
        val now = nowProvider()
        val status = SmsSendStatus(
            messageId = request.messageId,
            phoneNumber = request.phoneNumber,
            state = SmsSendState.PENDING,
            partCount = partCount,
            sentParts = 0,
            deliveredParts = 0,
            createdAt = now,
            updatedAt = now,
            subscriptionId = request.subscriptionId,
            simSlot = request.simSlot,
        )
        val callbackToken = UUID.randomUUID().toString()
        records[request.messageId] = Record(
            fingerprint = fingerprint,
            callbackToken = callbackToken,
            status = status,
        )
        trimToMaxRecordsLocked()
        return SmsStoreCreateResult(created = true, status = status, callbackToken = callbackToken)
    }

    @Synchronized
    fun get(messageId: String): SmsSendStatus? {
        cleanupLocked()
        return records[messageId]?.status
    }

    @Synchronized
    fun markPartSent(
        messageId: String,
        partIndex: Int,
        callbackToken: String? = null,
    ): SmsSendStatus? {
        val record = records[messageId] ?: return null
        if (!record.acceptsCallback(callbackToken)) return null
        if (record.status.state == SmsSendState.FAILED) return null
        if (partIndex !in 0 until record.status.partCount) return null

        val wasDelivered = record.status.state == SmsSendState.DELIVERED
        if (!record.sentParts.add(partIndex)) return null
        val nextState =
            when {
                wasDelivered -> SmsSendState.DELIVERED
                record.sentParts.size >= record.status.partCount -> SmsSendState.SENT
                else -> record.status.state
            }
        record.status = record.status.copy(
            state = nextState,
            sentParts = record.sentParts.size,
            updatedAt = nowProvider(),
        )
        return if (wasDelivered) null else record.status
    }

    @Synchronized
    fun markPartDelivered(
        messageId: String,
        partIndex: Int,
        callbackToken: String? = null,
    ): SmsSendStatus? {
        val record = records[messageId] ?: return null
        if (!record.acceptsCallback(callbackToken)) return null
        if (record.status.state == SmsSendState.FAILED) return null
        if (partIndex !in 0 until record.status.partCount) return null

        if (!record.deliveredParts.add(partIndex)) return null
        val nextState =
            if (record.deliveredParts.size >= record.status.partCount) SmsSendState.DELIVERED
            else record.status.state
        record.status = record.status.copy(
            state = nextState,
            deliveredParts = record.deliveredParts.size,
            updatedAt = nowProvider(),
        )
        return record.status
    }

    @Synchronized
    fun markFailed(
        messageId: String,
        errorCode: String,
        errorMessage: String,
        callbackToken: String? = null,
    ): SmsSendStatus? {
        val record = records[messageId] ?: return null
        if (!record.acceptsCallback(callbackToken)) return null
        if (record.status.state == SmsSendState.DELIVERED || record.status.state == SmsSendState.FAILED) {
            return null
        }
        record.status = record.status.copy(
            state = SmsSendState.FAILED,
            errorCode = errorCode,
            errorMessage = errorMessage,
            updatedAt = nowProvider(),
        )
        return record.status
    }

    @Synchronized
    fun cleanup() {
        cleanupLocked()
    }

    private fun cleanupLocked() {
        val now = nowProvider()
        val expired = records
            .filterValues { now - it.status.updatedAt > ttlMillis }
            .keys
            .toList()
        expired.forEach { records.remove(it) }
        trimToMaxRecordsLocked()
    }

    private fun trimToMaxRecordsLocked() {
        while (records.size > maxRecords) {
            val oldest = records.keys.firstOrNull() ?: return
            records.remove(oldest)
        }
    }

    private fun Record.acceptsCallback(callbackToken: String?): Boolean {
        return callbackToken == null || callbackToken == this.callbackToken
    }

    private fun fingerprint(request: SmsSendRequest): String {
        val raw = listOf(
            request.phoneNumber.trim(),
            request.message,
            request.subscriptionId?.toString().orEmpty(),
            request.simSlot?.toString().orEmpty(),
            request.deliveryReport.toString(),
        ).joinToString(separator = "\u001f")
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val DEFAULT_MAX_RECORDS = 500
        private const val DEFAULT_TTL_MILLIS = 24L * 60L * 60L * 1000L
    }
}

object SmsStateRegistry {
    val store = SmsStateStore()
}
