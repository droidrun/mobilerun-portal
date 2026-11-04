package com.mobilerun.portal.sms

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

interface SmsBridgeSender {
    fun divideMessage(message: String, subscriptionId: Int?): List<String>

    fun sendBridgeMultipart(
        recordId: String,
        to: String,
        parts: List<String>,
        subscriptionId: Int?,
        deliveryReport: Boolean,
        callbackToken: String,
    )
}

/**
 * Relay -> device SMS bridge calls (wrtc-stream-bridge contract). Without telephony the SMS tokens
 * are not announced, so every call answers "Unknown method"; with it, every call answers
 * "sms: permission_denied: cloud_sms_disabled" while the Mobilerun cloud SMS switch is off.
 */
class SmsBridgeApi(
    private val cloudEnabled: () -> Boolean,
    private val cloudSubscriptionId: () -> Int?,
    private val hasTelephony: () -> Boolean,
    private val hasReadSms: () -> Boolean,
    private val hasSendSms: () -> Boolean,
    private val subscriptions: () -> SmsSubscriptionsResult,
    private val provider: SmsProviderSource,
    private val records: SmsSendRecordStore,
    private val sender: SmsBridgeSender,
    private val limiter: SmsPartRateLimiter = SmsPartRateLimiter.portal,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val newToken: () -> String = { UUID.randomUUID().toString() },
) {
    fun handle(method: String, params: JSONObject): SmsBridgeResult {
        if (!hasTelephony()) return SmsBridgeResult.UnknownMethod(method)
        if (!cloudEnabled()) return SmsBridgeError.CLOUD_SMS_DISABLED.asResult()
        return try {
            when (method) {
                LIST -> list(params)
                GET -> get(params)
                SUBSCRIPTIONS -> listSubscriptions()
                SEND -> send(params)
                SEND_STATUS, SEND_STATUS_LOWER -> sendStatus(params)
                else -> SmsBridgeError(SmsBridgeError.INVALID_PARAMS, "unknown method").asResult()
            }
        } catch (e: Refused) {
            e.error.asResult()
        } catch (e: Exception) {
            SmsBridgeError(SmsBridgeError.DEVICE_SIDE_FAILURE, e.javaClass.simpleName).asResult()
        }
    }

    private fun list(params: JSONObject): SmsBridgeResult {
        val p = parsed(SmsBridgeParams.parseList(params))
        if (!hasReadSms()) refuse(SmsBridgeError.READ_SMS_DENIED)
        val scope = simScope()
        val ascending = p.order == SmsBridgeListParams.ORDER_ASC
        val after: SmsListCursor?
        val ceiling: Long
        if (p.cursor.isNotEmpty()) {
            after = SmsListCursor.decode(p.cursor)?.takeIf { it.order == p.order }
                ?: refuse(SmsBridgeError.invalid("cursor is not a nextCursor of this order"))
            ceiling = after.ceiling
        } else {
            after = null
            ceiling = provider.latest()?.id ?: return SmsBridgeResult.Ok(listJson(JSONArray(), null))
        }
        val rows = provider.query(
            SmsProviderQuery(
                types = typesFor(p.direction),
                ceilingId = ceiling,
                limit = p.limit + 1,
                ascending = ascending,
                after = after,
                since = p.since,
                until = p.until,
                storedFrom = p.storedFrom,
                address = p.address.ifEmpty { null },
                threadId = p.threadId.ifEmpty { null },
                subscriptionId = scope.filterSubscriptionId,
            ),
        )
        val page = rows.take(p.limit)
        val messages = JSONArray()
        page.forEach { row -> row.toBridgeMessage(scope.phoneNumbers)?.let(messages::put) }
        val next = if (rows.size > p.limit && page.isNotEmpty()) {
            SmsListCursor(p.order, page.last().date, page.last().id, ceiling).encode()
        } else {
            null
        }
        return SmsBridgeResult.Ok(listJson(messages, next))
    }

    private fun get(params: JSONObject): SmsBridgeResult {
        val id = parsed(SmsBridgeParams.parseId(params))
        if (!hasReadSms()) refuse(SmsBridgeError.READ_SMS_DENIED)
        val scope = simScope()
        val (providerId, date) = SmsMessageIds.parse(id) ?: refuse(SmsBridgeError.NOT_FOUND_ERROR)
        val row = provider.get(providerId)
            ?.takeIf { it.date == date }
            ?.takeIf { it.subscriptionId == scope.filterSubscriptionId }
            ?: refuse(SmsBridgeError.NOT_FOUND_ERROR)
        val message = row.toBridgeMessage(scope.phoneNumbers) ?: refuse(SmsBridgeError.NOT_FOUND_ERROR)
        return SmsBridgeResult.Ok(message)
    }

    private fun listSubscriptions(): SmsBridgeResult {
        val scope = simScope()
        return SmsBridgeResult.Ok(JSONObject().put("subscriptions", scope.visible.toBridgeJsonArray()))
    }

    private fun send(params: JSONObject): SmsBridgeResult {
        val p = parsed(SmsBridgeParams.parseSend(params))
        val id = p.id.ifEmpty { newId() }
        val fingerprint = SmsHashes.fingerprint(p.to, p.body)
        records.find(id)?.let { return storedStatus(it, fingerprint) }

        if (!hasSendSms()) refuse(SmsBridgeError.SEND_SMS_DENIED)
        val sim = resolveSender(p)
        val parts = try {
            sender.divideMessage(p.body, sim.subscriptionId).ifEmpty { listOf(p.body) }
        } catch (e: Exception) {
            refuse(SmsBridgeError(SmsBridgeError.DEVICE_SIDE_FAILURE, "divide failed"))
        }
        val acquired = limiter.tryAcquire(parts.size) ?: refuse(SmsBridgeError.RATE_LIMITED)

        val now = clock()
        val record = SmsSendRecord(
            id = id,
            origin = SmsSendRecord.ORIGIN_RELAY,
            to = p.to,
            fingerprint = fingerprint,
            bodyHash = SmsHashes.bodyHash(p.body),
            subscriptionId = sim.subscriptionId,
            simSlot = sim.simSlot,
            deliveryReport = p.deliveryReport,
            phase = SmsSendPhase.PREPARED,
            state = SmsSendState.PENDING,
            parts = parts.size,
            sentParts = emptySet(),
            deliveredParts = emptySet(),
            callbackToken = newToken(),
            createdAt = now,
            updatedAt = now,
        )
        val inserted = try {
            records.insert(record)
        } catch (e: SmsStoreFullException) {
            limiter.refund(acquired)
            refuse(SmsBridgeError.STORE_FULL)
        } catch (e: Exception) {
            limiter.refund(acquired)
            // A timed-out or interrupted insert may still land; drop it so a retry of this id sends.
            runCatching { records.discardPrepared(id, record.callbackToken) }
            refuse(SmsBridgeError(SmsBridgeError.DEVICE_SIDE_FAILURE, "store unavailable"))
        }
        if (!inserted) {
            limiter.refund(acquired)
            val existing = records.find(id) ?: refuse(SmsBridgeError(SmsBridgeError.DEVICE_SIDE_FAILURE, "store unavailable"))
            return storedStatus(existing, fingerprint)
        }

        try {
            sender.sendBridgeMultipart(id, p.to, parts, sim.subscriptionId, p.deliveryReport, record.callbackToken)
        } catch (e: Exception) {
            // SmsManager threw before taking any part: nothing went out.
            limiter.refund(acquired)
            val message = e.javaClass.simpleName
            val failed = runCatching { records.markFailed(id, SmsSendCallbacks.SEND_EXCEPTION, message, clock()) }
                .getOrNull()
                ?: record.withFailure(SmsSendCallbacks.SEND_EXCEPTION, message, clock())
                ?: record
            return SmsBridgeResult.Ok(failed.toBridgeStatus())
        }
        val dispatched = runCatching { records.markDispatched(id, clock()) ?: records.find(id) }.getOrNull()
            ?: record.copy(phase = SmsSendPhase.DISPATCHED)
        return SmsBridgeResult.Ok(dispatched.toBridgeStatus())
    }

    private fun sendStatus(params: JSONObject): SmsBridgeResult {
        val id = parsed(SmsBridgeParams.parseId(params))
        val record = records.find(id) ?: refuse(SmsBridgeError.NOT_FOUND_ERROR)
        return SmsBridgeResult.Ok(record.toBridgeStatus())
    }

    /** A known id never sends again, even after a failure; the same id with another to/body is refused. */
    private fun storedStatus(existing: SmsSendRecord, fingerprint: String): SmsBridgeResult {
        if (existing.fingerprint != fingerprint) refuse(SmsBridgeError.invalid("id reused"))
        return SmsBridgeResult.Ok(existing.toBridgeStatus())
    }

    private fun resolveSender(p: SmsBridgeSendParams): SmsSimInfo {
        val sims = activeSims()
        if (sims.isEmpty()) refuse(SmsBridgeError.NO_SUBSCRIPTION)
        val requested = when {
            p.subscriptionId != null ->
                sims.firstOrNull { it.subscriptionId.toLong() == p.subscriptionId } ?: refuse(SmsBridgeError.NO_SUBSCRIPTION)
            p.simSlot != null ->
                sims.firstOrNull { it.simSlot.toLong() == p.simSlot } ?: refuse(SmsBridgeError.NO_SUBSCRIPTION)
            else -> null
        }
        val cloud = SmsCloudSims.pick(sims, cloudSubscriptionId())
        if (requested != null) {
            if (cloud == null || requested.subscriptionId != cloud.subscriptionId) refuse(SmsBridgeError.NOT_CLOUD_SIM)
            return requested
        }
        return cloud ?: refuse(SmsBridgeError.NO_CLOUD_SIM)
    }

    private class SimScope(
        val filterSubscriptionId: Int,
        val visible: List<SmsSimInfo>,
    ) {
        val phoneNumbers: Map<Int, String> =
            visible.mapNotNull { sim -> sim.phoneNumber?.let { sim.subscriptionId to it } }.toMap()
    }

    /** Only the covered SIM is exposed, and only its rows, whether it was chosen or is the only active SIM. */
    private fun simScope(): SimScope {
        val sims = activeSims()
        val cloudId = cloudSubscriptionId()
        val sim = SmsCloudSims.pick(sims, cloudId)
            ?: refuse(if (cloudId == null && sims.isEmpty()) SmsBridgeError.NO_SUBSCRIPTION else SmsBridgeError.NO_CLOUD_SIM)
        return SimScope(filterSubscriptionId = sim.subscriptionId, visible = listOf(sim))
    }

    private fun activeSims(): List<SmsSimInfo> = when (val result = subscriptions()) {
        is SmsSubscriptionsResult.Available -> result.subscriptions
        SmsSubscriptionsResult.PermissionDenied -> refuse(SmsBridgeError.READ_PHONE_STATE_DENIED)
        SmsSubscriptionsResult.Unavailable ->
            refuse(SmsBridgeError(SmsBridgeError.DEVICE_SIDE_FAILURE, "subscriptions unavailable"))
    }

    private fun typesFor(direction: String): List<Int> = when (direction) {
        SmsBridgeListParams.DIRECTION_IN -> listOf(SmsProviderRow.TYPE_INBOX)
        SmsBridgeListParams.DIRECTION_OUT -> listOf(SmsProviderRow.TYPE_SENT)
        else -> listOf(SmsProviderRow.TYPE_INBOX, SmsProviderRow.TYPE_SENT)
    }

    private fun listJson(messages: JSONArray, nextCursor: String?): JSONObject =
        JSONObject()
            .put("messages", messages)
            .apply { nextCursor?.let { put("nextCursor", it) } }

    private fun <T> parsed(result: SmsBridgeParse<T>): T = when (result) {
        is SmsBridgeParse.Ok -> result.value
        is SmsBridgeParse.Err -> refuse(result.error)
    }

    private class Refused(val error: SmsBridgeError) : Exception(null, null, false, false)

    private fun refuse(error: SmsBridgeError): Nothing = throw Refused(error)

    companion object {
        const val LIST = "sms/list"
        const val GET = "sms/get"
        const val SUBSCRIPTIONS = "sms/subscriptions"
        const val SEND = "sms/send"
        const val SEND_STATUS = "sms/sendStatus"
        private const val SEND_STATUS_LOWER = "sms/sendstatus"

        /** Bridge names; an sms/send takes the bridge path only when its params carry "body". */
        fun isBridgeCall(method: String, params: JSONObject): Boolean = when (method) {
            LIST, GET, SUBSCRIPTIONS, SEND_STATUS, SEND_STATUS_LOWER -> true
            SEND -> params.has("body")
            else -> false
        }
    }
}
