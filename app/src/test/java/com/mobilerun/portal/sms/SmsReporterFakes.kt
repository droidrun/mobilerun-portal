package com.mobilerun.portal.sms

import org.json.JSONObject

/** Single-thread scheduler on virtual time. */
class FakeReporterScheduler {
    var now = 0L
        private set

    private class Task(val at: Long, val seq: Long, val run: () -> Unit) {
        var cancelled = false
    }

    private val tasks = mutableListOf<Task>()
    private var seq = 0L

    val clock: () -> Long = { now }

    val scheduler = object : SmsReporterScheduler {
        override fun execute(task: () -> Unit) {
            tasks += Task(now, seq++, task)
        }

        override fun schedule(delayMs: Long, task: () -> Unit): SmsCancellable {
            val entry = Task(now + delayMs, seq++, task)
            tasks += entry
            return SmsCancellable { entry.cancelled = true }
        }
    }

    /** Runs every task due now, including ones they post. */
    fun runDue() = advanceBy(0L)

    fun advanceBy(ms: Long) {
        val target = now + ms
        while (true) {
            tasks.removeAll { it.cancelled }
            val next = tasks.filter { it.at <= target }.minWithOrNull(compareBy<Task>({ it.at }, { it.seq })) ?: break
            tasks.remove(next)
            now = maxOf(now, next.at)
            next.run()
        }
        now = target
    }
}

class FakeReportCalls : SmsReportCalls {
    class Call(val method: String, val params: JSONObject, val callback: (SmsReportOutcome) -> Unit)

    val calls = mutableListOf<Call>()
    private val open = mutableListOf<Call>()

    /** Whether a current socket exists; a call without one completes Closed at once, like ReverseRpcCalls. */
    var socketOpen = true

    override fun isOpen(): Boolean = socketOpen

    override fun call(method: String, params: JSONObject, callback: (SmsReportOutcome) -> Unit) {
        if (!socketOpen) {
            calls += Call(method, params, callback)
            callback(SmsReportOutcome.Closed)
            return
        }
        val call = Call(method, params, callback)
        calls += call
        open += call
    }

    fun inFlight(method: String = SmsReporterCore.RECEIVED): List<Call> = open.filter { it.method == method }

    fun complete(call: Call, outcome: SmsReportOutcome) {
        check(open.remove(call)) { "call already completed" }
        call.callback(outcome)
    }

    fun completeAll(method: String, outcome: SmsReportOutcome) {
        inFlight(method).forEach { complete(it, outcome) }
    }

    fun receivedIds(): List<String> =
        calls.filter { it.method == SmsReporterCore.RECEIVED }.map { it.params.getJSONObject("message").getString("id") }
}

class FakeReporterStore : SmsReporterStore {
    var state: SmsScanState? = null
    val pending = linkedMapOf<String, SmsPendingReport>()
    val ring = linkedSetOf<String>()
    /** provider _id -> recorded at */
    val own = mutableMapOf<Long, Long>()
    val records = linkedMapOf<String, SmsSendRecord>()
    val retries = mutableMapOf<String, SmsSendStateRetry>()
    val reported = mutableListOf<String>()
    var seeded = 0

    /** The next N calls of each kind throw, like a transient store failure. */
    var failNextPendingAt = 0
    var failUnreportedReads = 0
    var failPendingWrites = 0
    var failMarkReported = 0

    /** Queue writes of these msg ids throw until removed. */
    val failWritesFor = mutableSetOf<String>()

    private fun failIf(count: Int, decrement: () -> Unit) {
        if (count > 0) {
            decrement()
            throw IllegalStateException("store unavailable")
        }
    }

    override fun <T> atomically(block: () -> T): T = block()

    override fun scanState(): SmsScanState? = state

    override fun seedScanState(latest: SmsProviderRow?, coveredFrom: Long) {
        seeded += 1
        pending.clear()
        ring.clear()
        val mark = SmsWatermark(latest?.id ?: 0L, latest?.date ?: 0L)
        state = SmsScanState(mark, mark, coveredFrom, mark.id)
    }

    override fun isReported(msgId: String): Boolean = msgId in ring

    override fun ownSends(): SmsOwnSends = SmsOwnSends(own.toMap())

    override fun pendingCount(): Int = pending.size

    override fun commitScan(state: SmsScanState, reports: List<SmsPendingReport>, now: Long) {
        this.state = state
        reports.forEach {
            pending.putIfAbsent(it.msgId, it)
            ring += it.msgId
        }
    }

    override fun duePending(now: Long, limit: Int, exclude: Set<String>): List<SmsPendingReport> =
        pending.values
            .filter { it.nextAttemptAt <= now && it.msgId !in exclude }
            .sortedWith(compareBy<SmsPendingReport>({ it.firstQueuedAt }, { it.providerId }))
            .take(limit)

    override fun nextPendingAt(exclude: Set<String>): Long? {
        failIf(failNextPendingAt) { failNextPendingAt -= 1 }
        return pending.values.filter { it.msgId !in exclude }.minOfOrNull { it.nextAttemptAt }
    }

    override fun updatePending(report: SmsPendingReport) {
        failIf(failPendingWrites) { failPendingWrites -= 1 }
        check(report.msgId !in failWritesFor) { "store unavailable" }
        if (pending.containsKey(report.msgId)) pending[report.msgId] = report
    }

    override fun deletePending(msgId: String) {
        failIf(failPendingWrites) { failPendingWrites -= 1 }
        check(msgId !in failWritesFor) { "store unavailable" }
        pending.remove(msgId)
    }

    override fun unreportedRelayRecords(): List<SmsUnreportedSend> {
        failIf(failUnreportedReads) { failUnreportedReads -= 1 }
        return records.values
            .filter { it.origin == SmsSendRecord.ORIGIN_RELAY && it.lastReportedState != it.state.wire }
            .map { SmsUnreportedSend(it, retries[it.id]) }
    }

    override fun saveSendStateRetry(id: String, retry: SmsSendStateRetry) {
        if (records.containsKey(id)) retries[id] = retry
    }

    override fun markReported(id: String, state: SmsSendState) {
        failIf(failMarkReported) { failMarkReported -= 1 }
        reported += "$id:${state.wire}"
        records[id]?.let { records[id] = it.copy(lastReportedState = state.wire) }
    }
}

fun relayRecord(
    id: String,
    state: SmsSendState,
    updatedAt: Long = 1L,
    lastReportedState: String? = null,
    origin: String = SmsSendRecord.ORIGIN_RELAY,
): SmsSendRecord =
    SmsSendRecord(
        id = id,
        origin = origin,
        to = "+15550002222",
        fingerprint = "fp",
        bodyHash = "bh",
        subscriptionId = 1,
        simSlot = 0,
        deliveryReport = true,
        phase = if (state == SmsSendState.PENDING) SmsSendPhase.DISPATCHED else SmsSendPhase.TERMINAL,
        state = state,
        parts = 1,
        sentParts = if (state == SmsSendState.PENDING) emptySet() else setOf(0),
        deliveredParts = if (state == SmsSendState.DELIVERED) setOf(0) else emptySet(),
        callbackToken = "token",
        errorCode = if (state == SmsSendState.FAILED) "RESULT_ERROR_LIMIT_EXCEEDED" else null,
        lastReportedState = lastReportedState,
        createdAt = 1L,
        updatedAt = updatedAt,
    )
