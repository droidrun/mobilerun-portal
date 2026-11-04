package com.mobilerun.portal.sms

import org.json.JSONObject

fun interface SmsCancellable {
    fun cancel()
}

/** One thread: every task runs in order on it. */
interface SmsReporterScheduler {
    fun execute(task: () -> Unit)

    fun schedule(delayMs: Long, task: () -> Unit): SmsCancellable
}

/** Device -> relay calls; the callback may run on any thread. */
interface SmsReportCalls {
    /** False while no current relay socket is open, whatever socket events said. */
    fun isOpen(): Boolean

    fun call(method: String, params: JSONObject, callback: (SmsReportOutcome) -> Unit)
}

/** Reporter persistence: scan state, the pending-report queue, the reported-id ring and send records. */
interface SmsReporterStore {
    /** Runs [block] serialized with switch seeding and clearing. */
    fun <T> atomically(block: () -> T): T

    /** Null until the switch seeded it. */
    fun scanState(): SmsScanState?

    fun seedScanState(latest: SmsProviderRow?, coveredFrom: Long)

    fun isReported(msgId: String): Boolean

    fun ownSends(): SmsOwnSends

    fun pendingCount(): Int

    /** Saves [state], queues [reports] (known ids are ignored) and adds them to the reported-id ring. */
    fun commitScan(state: SmsScanState, reports: List<SmsPendingReport>, now: Long)

    fun duePending(now: Long, limit: Int, exclude: Set<String>): List<SmsPendingReport>

    fun nextPendingAt(exclude: Set<String>): Long?

    /** Updates a queued report; no-op when it was removed meanwhile. */
    fun updatePending(report: SmsPendingReport)

    fun deletePending(msgId: String)

    fun unreportedRelayRecords(): List<SmsUnreportedSend>

    /** Saves the retry bookkeeping of a send's unreported state; no-op when the record is gone. */
    fun saveSendStateRetry(id: String, retry: SmsSendStateRetry)

    fun markReported(id: String, state: SmsSendState)
}

/**
 * Device -> cloud SMS reporting over the relay socket: sms/received for new provider rows while
 * Mobilerun cloud SMS is on and READ_SMS is granted, and sms/sendState for every state change of a
 * relay send whatever the switch says. All state lives on [scheduler]'s thread.
 */
class SmsReporterCore(
    private val scheduler: SmsReporterScheduler,
    private val store: SmsReporterStore,
    private val rows: SmsReportRowSource,
    private val calls: SmsReportCalls,
    packageName: String,
    private val cloudEnabled: () -> Boolean,
    private val hasReadSms: () -> Boolean,
    private val sims: () -> SmsReporterSims,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    private val maxPending: Int = SmsStorePolicy.MAX_PENDING_REPORTS,
) {
    private val scanner = SmsReportScanner(rows, packageName)

    private var connected = false
    private var scanTask: SmsCancellable? = null
    private var drainTask: SmsCancellable? = null
    private var drainAt = Long.MAX_VALUE
    private var stateTask: SmsCancellable? = null
    private var loadTask: SmsCancellable? = null
    private val inFlight = HashSet<String>()

    /** The last scan stopped at a full queue: each report leaving it scans again. */
    private var queueFull = false

    /** Queue writes that failed, tried again on each drain: the report to save, or null to delete it. */
    private val unsaved = HashMap<String, SmsPendingReport?>()

    private data class StateItem(
        val record: SmsSendRecord,
        val attempts: Int = 0,
        val unconfirmed: Int = 0,
        val nextAttemptAt: Long,
        val firstQueuedAt: Long,
        /** Reporting is over; only its markReported write is left, tried again at [nextAttemptAt]. */
        val finished: Boolean = false,
    ) {
        fun retry() = SmsSendStateRetry(record.state, attempts, unconfirmed, nextAttemptAt, firstQueuedAt)
    }

    private val states = LinkedHashMap<String, StateItem>()
    private val statesInFlight = HashMap<String, SmsSendState>()

    fun start() = post { if (active()) requestScan(DEBOUNCE_MS) }

    fun scanSoon() = post { requestScan(DEBOUNCE_MS) }

    /** An SMS_RECEIVED broadcast: the default SMS app writes the row a little later. */
    fun scanBurst() {
        scanSoon()
        BURST_DELAYS_MS.forEach { delay -> scheduler.schedule(delay) { guarded { requestScan(DEBOUNCE_MS) } } }
    }

    fun onSocketOpen() = post {
        connected = true
        loadStates()
        requestScan(0L)
        drain()
        drainStates()
    }

    /** The cloud SIM changed: rescan now instead of after the blocked-retry delay. */
    fun onCloudSimChanged() = post { requestScan(0L) }

    fun onSocketClosed() = post {
        connected = false
        drainTask?.cancel()
        drainTask = null
        drainAt = Long.MAX_VALUE
        loadTask?.cancel()
        loadTask = null
        drainStates()
    }

    fun onCloudSwitchChanged(enabled: Boolean) = post {
        if (enabled) {
            requestScan(0L)
        } else {
            scanTask?.cancel()
            scanTask = null
            drainTask?.cancel()
            drainTask = null
            drainAt = Long.MAX_VALUE
        }
    }

    /** A persisted state change of a relay send record. */
    fun onSendStateChanged(record: SmsSendRecord) = post {
        offerState(record, clock())
        drainStates()
    }

    // --- scanning ---

    private fun active(): Boolean = cloudEnabled() && hasReadSms()

    private fun requestScan(delayMs: Long) {
        val pending = scanTask
        if (pending != null) {
            if (delayMs > 0L) return
            pending.cancel()
        }
        scanTask = scheduler.schedule(delayMs) {
            guarded {
                scanTask = null
                scan()
            }
        }
    }

    private fun scan() {
        if (!active()) return
        val sims = sims()
        val covered = sims as? SmsReporterSims.Covered
        if (covered == null) {
            if (sims == SmsReporterSims.Uncovered) skipUncovered()
            if (scanTask == null) scanTask = scheduler.schedule(BLOCKED_RETRY_MS) { guarded { scanTask = null; scan() } }
            return
        }
        val now = clock()
        val result = try {
            store.atomically {
                if (!cloudEnabled()) return@atomically null
                val state = store.scanState()
                if (state == null) {
                    store.seedScanState(rows.latest(), now)
                    return@atomically null
                }
                val room = maxPending - store.pendingCount()
                val result = scanner.scan(state, store.ownSends(), store::isReported, covered.subscriptionFilter, now, room)
                val queued = result.reports.map {
                    SmsPendingReport(it.msgId, it.providerId, it.direction, nextAttemptAt = now, firstQueuedAt = now)
                }
                store.commitScan(result.state, queued, now)
                result
            }
        } catch (e: Exception) {
            log("scan failed (${e.javaClass.simpleName})")
            if (scanTask == null) scanTask = scheduler.schedule(BLOCKED_RETRY_MS) { guarded { scanTask = null; scan() } }
            null
        } ?: return
        if (result.reports.isNotEmpty()) log("queued ${result.reports.size} SMS report(s)")
        if (result.queueFull && !queueFull) log("SMS report queue full: scanning waits for it to drain")
        queueFull = result.queueFull
        if (result.evictedRechecks.isNotEmpty()) {
            log("dropped ${result.evictedRechecks.size} oldest unsent SMS recheck(s) over ${SmsReportScanner.MAX_RECHECKS}")
        }
        if (result.more && !result.queueFull) requestScan(0L)
        drain()
    }

    /**
     * Restarts coverage at the newest row, like choosing a cloud SIM: the SIM covered next uploads nothing
     * that arrived while none was. Queued reports stay.
     */
    private fun skipUncovered() {
        val now = clock()
        try {
            store.atomically {
                if (!cloudEnabled()) return@atomically
                val state = store.scanState() ?: return@atomically
                val latest = rows.latest()
                val top = SmsWatermark(latest?.id ?: 0L, latest?.date ?: 0L)
                val skipped = SmsScanState(top, top, coveredFrom = now, coveredFromId = top.id)
                // Nothing new since the last skip: no write.
                if (state != skipped.copy(coveredFrom = state.coveredFrom)) store.commitScan(skipped, emptyList(), now)
            }
        } catch (e: Exception) {
            log("scan failed (${e.javaClass.simpleName})")
        }
    }

    // --- sms/received ---

    /** A stale open event must not start a 1 s requeue loop against a closed socket. */
    private fun online(): Boolean = connected && calls.isOpen()

    private fun drain() {
        if (!online() || !active()) return
        val free = MAX_IN_FLIGHT - inFlight.size
        if (free <= 0) return
        retryUnsaved()
        val now = clock()
        // Until its write lands, a finished report stays out and a retried one keeps its backoff and count.
        val held = unsaved.filterValues { it == null || it.nextAttemptAt > now }.keys
        val due = try {
            store.duePending(now, free, inFlight + held).map { unsaved[it.msgId] ?: it }
        } catch (e: Exception) {
            log("pending read failed (${e.javaClass.simpleName})")
            scheduleDrain(now + BLOCKED_RETRY_MS)
            return
        }
        if (due.isNotEmpty()) {
            val covered = sims() as? SmsReporterSims.Covered
            if (covered == null) {
                scheduleDrain(now + BLOCKED_RETRY_MS)
                return
            }
            due.forEach { send(it, covered, now) }
        }
        scheduleNextDrain()
    }

    private fun send(report: SmsPendingReport, covered: SmsReporterSims.Covered, now: Long) {
        val row = try {
            rows.row(report.providerId)
        } catch (e: Exception) {
            log("row read failed (${e.javaClass.simpleName})")
            save(report.msgId, report.copy(nextAttemptAt = now + BLOCKED_RETRY_MS))
            return
        }
        val message = row
            ?.takeIf { it.messageId == report.msgId }
            ?.takeIf { covered.subscriptionFilter == null || it.subscriptionId == covered.subscriptionFilter }
            ?.toBridgeMessage(covered.phoneNumbers)
            ?.takeIf { it.optString("direction") == report.direction }
        if (message == null) {
            save(report.msgId, null)
            log("dropped SMS report ${report.msgId}: row gone")
            return
        }
        inFlight += report.msgId
        calls.call(RECEIVED, JSONObject().put("message", message)) { outcome ->
            post { onReceivedOutcome(report, outcome) }
        }
    }

    private fun onReceivedOutcome(report: SmsPendingReport, outcome: SmsReportOutcome) {
        inFlight -= report.msgId
        val now = clock()
        when (val decision = SmsReportPolicy.apply(report, outcome, now)) {
            is SmsReportDecision.Retry -> save(
                report.msgId,
                report.copy(
                    attempts = decision.attempts,
                    unconfirmed = decision.unconfirmed,
                    nextAttemptAt = decision.nextAttemptAt,
                    lastError = decision.lastError,
                ),
            )
            SmsReportDecision.Requeue -> {
                if (online()) scheduleDrain(now + REQUEUE_DELAY_MS)
                return
            }
            else -> {
                describe(decision)?.let { log("SMS report ${report.msgId} $it") }
                save(report.msgId, null)
            }
        }
        drain()
    }

    private fun scheduleNextDrain() {
        if (inFlight.size >= MAX_IN_FLIGHT) return
        val now = clock()
        val queued = try {
            store.nextPendingAt(inFlight + unsaved.keys)
        } catch (e: Exception) {
            log("pending read failed (${e.javaClass.simpleName})")
            now + BLOCKED_RETRY_MS
        }
        val held = unsaved.values.mapNotNull { it?.nextAttemptAt }.filter { it > now }.minOrNull()
        val rewrite = if (unsaved.isEmpty()) null else now + BLOCKED_RETRY_MS
        val next = listOfNotNull(queued, held, rewrite).minOrNull() ?: return
        scheduleDrain(next)
    }

    /** Saves [report], or deletes [msgId] when it is null; a failed write is kept in [unsaved]. */
    private fun save(msgId: String, report: SmsPendingReport?): Boolean {
        val written = stored { if (report == null) store.deletePending(msgId) else store.updatePending(report) }
        if (written) unsaved.remove(msgId) else unsaved[msgId] = report
        if (written && report == null && queueFull) requestScan(DEBOUNCE_MS)
        return written
    }

    /** Stops at the first write that fails again. */
    private fun retryUnsaved() {
        for ((msgId, report) in unsaved.toList()) {
            if (!save(msgId, report)) return
        }
    }

    private fun scheduleDrain(at: Long) {
        if (!online()) return
        if (drainTask != null && drainAt <= at) return
        drainTask?.cancel()
        drainAt = at
        drainTask = scheduler.schedule((at - clock()).coerceAtLeast(0L)) {
            guarded {
                drainTask = null
                drainAt = Long.MAX_VALUE
                drain()
            }
        }
    }

    // --- sms/sendState ---

    private fun loadStates() {
        loadTask?.cancel()
        loadTask = null
        val unreported = try {
            store.unreportedRelayRecords()
        } catch (e: Exception) {
            log("send records read failed (${e.javaClass.simpleName})")
            if (online()) {
                loadTask = scheduler.schedule(BLOCKED_RETRY_MS) {
                    guarded {
                        loadTask = null
                        if (online()) {
                            loadStates()
                            drainStates()
                        }
                    }
                }
            }
            return
        }
        val now = clock()
        unreported.forEach { offerState(it.record, now, it.retry) }
    }

    private fun offerState(record: SmsSendRecord, now: Long, saved: SmsSendStateRetry? = null) {
        if (!record.needsSendStateReport()) return
        val existing = states[record.id]
        if (existing != null && existing.record.state == record.state) {
            if (record.updatedAt > existing.record.updatedAt) states[record.id] = existing.copy(record = record)
            return
        }
        // Saved bookkeeping counts only for the state it was saved for.
        val retry = saved?.takeIf { it.state == record.state }
        states[record.id] = if (retry != null) {
            StateItem(record, retry.attempts, retry.unconfirmed, retry.nextAttemptAt, retry.firstQueuedAt)
        } else {
            StateItem(record, nextAttemptAt = now, firstQueuedAt = now).also(::saveRetry)
        }
    }

    private fun saveRetry(item: StateItem) {
        try {
            store.saveSendStateRetry(item.record.id, item.retry())
        } catch (e: Exception) {
            log("send state retry save failed (${e.javaClass.simpleName})")
        }
    }

    private fun drainStates() {
        stateTask?.cancel()
        stateTask = null
        val now = clock()
        // markReported needs no socket, so a finished item is written again while offline too.
        states.values.filter { it.finished && it.nextAttemptAt <= now }.forEach { finish(it, now) }
        if (!online()) {
            states.values.filter { it.finished }.minOfOrNull { it.nextAttemptAt }?.let { scheduleStates(it - now) }
            return
        }
        var free = MAX_STATE_IN_FLIGHT - statesInFlight.size
        for (item in states.values.sortedBy { it.record.updatedAt }) {
            if (free <= 0) break
            if (item.finished || item.record.id in statesInFlight || item.nextAttemptAt > now) continue
            free -= 1
            val sent = item.record
            statesInFlight[sent.id] = sent.state
            calls.call(SEND_STATE, JSONObject().put("status", sent.toBridgeStatus())) { outcome ->
                post { onStateOutcome(sent, outcome) }
            }
        }
        if (free <= 0) return
        val next = states.values.filter { it.record.id !in statesInFlight }.minOfOrNull { it.nextAttemptAt } ?: return
        scheduleStates(next - now)
    }

    private fun scheduleStates(delayMs: Long) {
        stateTask = scheduler.schedule(delayMs.coerceAtLeast(0L)) {
            guarded {
                stateTask = null
                drainStates()
            }
        }
    }

    private fun onStateOutcome(sent: SmsSendRecord, outcome: SmsReportOutcome) {
        statesInFlight.remove(sent.id)
        val item = states[sent.id]
        // A newer state replaced this one while it was in flight; it goes out next.
        if (item == null || item.record.state != sent.state) {
            drainStates()
            return
        }
        val now = clock()
        when (val decision = SmsReportPolicy.decide(item.attempts, item.unconfirmed, item.firstQueuedAt, outcome, now)) {
            is SmsReportDecision.Retry -> {
                val retried = item.copy(
                    attempts = decision.attempts,
                    unconfirmed = decision.unconfirmed,
                    nextAttemptAt = decision.nextAttemptAt,
                )
                states[sent.id] = retried
                saveRetry(retried)
            }
            SmsReportDecision.Requeue -> states[sent.id] = item.copy(nextAttemptAt = now + REQUEUE_DELAY_MS)
            else -> {
                describe(decision)?.let { log("SMS send state ${sent.id} ${sent.state.wire} $it") }
                finish(item, now)
            }
        }
        drainStates()
    }

    /** Drops [item] once markReported lands; until then it stays, so it is neither sent nor decided again. */
    private fun finish(item: StateItem, now: Long) {
        val id = item.record.id
        try {
            store.markReported(id, item.record.state)
            states.remove(id)
        } catch (e: Exception) {
            log("send state save failed (${e.javaClass.simpleName})")
            states[id] = item.copy(finished = true, nextAttemptAt = now + BLOCKED_RETRY_MS)
        }
    }

    // --- helpers ---

    private fun describe(decision: SmsReportDecision): String? = when (decision) {
        is SmsReportDecision.Dropped -> "dropped: ${decision.error}"
        SmsReportDecision.GaveUp -> "gave up after 24 h of errors"
        SmsReportDecision.DoneUnconfirmed -> "unconfirmed: no reply after retries"
        else -> null
    }

    /** A pending-queue write; false when the store failed. */
    private inline fun stored(write: () -> Unit): Boolean =
        try {
            write()
            true
        } catch (e: Exception) {
            log("pending write failed (${e.javaClass.simpleName})")
            false
        }

    private fun post(task: () -> Unit) = scheduler.execute { guarded(task) }

    private inline fun guarded(task: () -> Unit) {
        try {
            task()
        } catch (e: Exception) {
            log("reporter task failed (${e.javaClass.simpleName})")
        }
    }

    companion object {
        const val RECEIVED = "sms/received"
        const val SEND_STATE = "sms/sendState"

        /** Longer than devices-api's 30 s forward budget, so its "forward failed" reply arrives in time. */
        const val CALL_TIMEOUT_MS = 35_000L
        const val DEBOUNCE_MS = 500L
        const val MAX_IN_FLIGHT = 8
        const val MAX_STATE_IN_FLIGHT = 4
        const val REQUEUE_DELAY_MS = 1_000L
        const val BLOCKED_RETRY_MS = 60_000L
        val BURST_DELAYS_MS = longArrayOf(1_000L, 5_000L, 15_000L)
    }
}
