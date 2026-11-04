package com.mobilerun.portal.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SmsReporterCoreTest {
    private lateinit var time: FakeReporterScheduler
    private lateinit var provider: FakeSmsProvider
    private lateinit var store: FakeReporterStore
    private lateinit var calls: FakeReportCalls
    private lateinit var core: SmsReporterCore
    private var cloudEnabled = true
    private var readSms = true
    private var sims: SmsReporterSims = SmsReporterSims.Covered(null, mapOf(1 to "+15550009999"))

    @Before
    fun setUp() {
        time = FakeReporterScheduler()
        time.advanceBy(START)
        provider = FakeSmsProvider()
        store = FakeReporterStore()
        calls = FakeReportCalls()
        core = newCore()
        store.seedScanState(null, coveredFrom = START)
    }

    private fun newCore(maxPending: Int = SmsStorePolicy.MAX_PENDING_REPORTS) = SmsReporterCore(
        scheduler = time.scheduler,
        store = store,
        rows = provider,
        calls = calls,
        packageName = PORTAL,
        cloudEnabled = { cloudEnabled },
        hasReadSms = { readSms },
        sims = { sims },
        clock = time.clock,
        maxPending = maxPending,
    )

    /** A new process: the store and the provider survive; tasks, calls and memory do not. */
    private fun restart() {
        val now = time.now
        time = FakeReporterScheduler().also { it.advanceBy(now) }
        calls = FakeReportCalls()
        core = newCore()
    }

    private fun inbox(date: Long = time.now) = provider.add(SmsProviderRow.TYPE_INBOX, date = date)

    private fun scanned() {
        core.scanSoon()
        time.advanceBy(SmsReporterCore.DEBOUNCE_MS)
    }

    private fun open() {
        core.onSocketOpen()
        time.runDue()
    }

    // --- activation ---

    @Test
    fun rowsAreQueuedWhileClosed_andSentOnlyOnceTheSocketOpens() {
        val row = inbox()
        scanned()

        assertEquals(listOf(row.messageId), store.pending.keys.toList())
        assertTrue(calls.calls.isEmpty())

        open()
        assertEquals(listOf(row.messageId), calls.receivedIds())
        calls.completeAll(SmsReporterCore.RECEIVED, SmsReportOutcome.Ok)
        time.runDue()
        assertTrue(store.pending.isEmpty())
    }

    @Test
    fun socketOpen_rescansWithoutAnObserverEvent() {
        val row = inbox()

        open()

        assertEquals(listOf(row.messageId), calls.receivedIds())
    }

    @Test
    fun scans_areDebounced() {
        inbox()
        core.scanSoon()
        core.scanSoon()
        time.advanceBy(SmsReporterCore.DEBOUNCE_MS - 1)
        assertTrue(store.pending.isEmpty())

        val queries = provider.queries
        time.advanceBy(1)
        assertEquals(1, store.pending.size)
        assertEquals(queries + 2, provider.queries)
    }

    @Test
    fun scanFailure_retriesWithoutAnotherEvent() {
        val row = inbox()
        provider.failReads = 1
        scanned()
        assertTrue(store.pending.isEmpty())

        time.advanceBy(SmsReporterCore.BLOCKED_RETRY_MS)
        assertEquals(listOf(row.messageId), store.pending.keys.toList())
    }

    @Test
    fun scanBurst_scansAgainAfterTheDefaultSmsAppWritesTheRow() {
        core.scanBurst()
        time.advanceBy(SmsReporterCore.DEBOUNCE_MS)
        assertTrue(store.pending.isEmpty())

        val late = inbox()
        time.advanceBy(1_000L)
        assertEquals(listOf(late.messageId), store.pending.keys.toList())

        val later = inbox()
        time.advanceBy(15_000L)
        assertEquals(listOf(late.messageId, later.messageId), store.pending.keys.toList())
    }

    @Test
    fun switchOff_neitherScansNorSends() {
        cloudEnabled = false
        inbox()
        open()
        scanned()

        assertTrue(store.pending.isEmpty())
        assertTrue(calls.calls.isEmpty())
    }

    @Test
    fun switchOff_stopsQueuedReportsFromGoingOut() {
        inbox()
        scanned()
        cloudEnabled = false
        core.onCloudSwitchChanged(false)

        open()

        assertTrue(calls.calls.isEmpty())
    }

    @Test
    fun withoutReadSms_nothingIsScannedOrSent() {
        readSms = false
        inbox()
        open()
        scanned()

        assertTrue(store.pending.isEmpty())
        assertTrue(calls.calls.isEmpty())
    }

    @Test
    fun missingScanState_isSeededWithoutBackfill() {
        store.state = null
        inbox()

        scanned()

        assertEquals(2, store.seeded)
        assertTrue(store.pending.isEmpty())
        val next = inbox()
        scanned()
        assertEquals(listOf(next.messageId), store.pending.keys.toList())
    }

    @Test
    fun blockedSims_holdTheScanAndTheWatermark() {
        sims = SmsReporterSims.Blocked
        inbox()
        scanned()

        assertTrue(store.pending.isEmpty())
        assertEquals(0L, store.state!!.inbound.id)
    }

    @Test
    fun uncoveredSims_theSimCoveredNextUploadsNothingFromThePause() {
        val queued = inbox()
        scanned()
        // A second SIM and no cloud SIM chosen: SIM 2's rows arrive uncovered.
        sims = SmsReporterSims.Uncovered
        provider.add(SmsProviderRow.TYPE_INBOX, date = time.now, subscriptionId = 2)
        scanned()

        // SIM 1 is removed: the blocked retry covers SIM 2 as the only active SIM.
        sims = SmsReporterSims.Covered(2, emptyMap())
        val next = provider.add(SmsProviderRow.TYPE_INBOX, date = time.now, subscriptionId = 2)
        time.advanceBy(SmsReporterCore.BLOCKED_RETRY_MS)

        assertEquals(listOf(queued.messageId, next.messageId), store.pending.keys.toList())
    }

    @Test
    fun uncoveredSims_pausedRowsStayOutOfADateReRead() {
        sims = SmsReporterSims.Uncovered
        val paused = provider.add(SmsProviderRow.TYPE_INBOX, date = time.now, subscriptionId = 2)
        val watermark = provider.add(SmsProviderRow.TYPE_INBOX, date = time.now, subscriptionId = 2)
        scanned()
        sims = SmsReporterSims.Covered(2, emptyMap())
        // The watermark row is deleted: rows from 5 min before it are read again by date.
        provider.rows.remove(watermark)

        time.advanceBy(SmsReporterCore.BLOCKED_RETRY_MS)

        assertFalse(paused.messageId in store.pending)
    }

    @Test
    fun switchOn_scansRightAway() {
        val row = inbox()
        open()
        calls.completeAll(SmsReporterCore.RECEIVED, SmsReportOutcome.Ok)
        val next = inbox()

        core.onCloudSwitchChanged(true)
        time.runDue()

        assertEquals(listOf(row.messageId, next.messageId), calls.receivedIds())
    }

    // --- message ---

    @Test
    fun message_carriesTheSmsMessageFields_withTheBodyReadAtSendTime() {
        val row = provider.add(
            SmsProviderRow.TYPE_INBOX,
            date = time.now,
            address = "+15550001111",
            body = "old",
            subscriptionId = 1,
            threadId = 42,
            dateSent = time.now - 3_000,
        )
        scanned()
        provider.update(row.id) { it.copy(body = "code 123456") }

        open()

        val message = calls.calls.single().params.getJSONObject("message")
        assertEquals(row.messageId, message.getString("id"))
        assertEquals("in", message.getString("direction"))
        assertEquals("+15550001111", message.getString("address"))
        assertEquals("code 123456", message.getString("body"))
        assertTrue(message.getBoolean("bodyAvailable"))
        assertEquals(row.date, message.getLong("date"))
        assertEquals(row.date, message.getLong("storedAt"))
        assertEquals("+15550009999", message.getString("phoneNumber"))
        assertEquals(row.date - 3_000, message.getLong("sentAt"))
        assertTrue(message.getBoolean("read"))
        assertEquals("42", message.getString("threadId"))
        assertEquals(1, message.getInt("subscriptionId"))
    }

    @Test
    fun rowGoneOrChangedAtSendTime_isDropped() {
        val changed = inbox()
        val gone = inbox()
        val kept = inbox()
        scanned()
        provider.update(changed.id) { it.copy(date = it.date + 1) }
        provider.remove(gone.id)

        open()

        assertEquals(listOf(kept.messageId), calls.receivedIds())
        assertEquals(listOf(kept.messageId), store.pending.keys.toList())
    }

    @Test
    fun portalSends_areNeverReported() {
        provider.add(SmsProviderRow.TYPE_SENT, date = time.now, creator = PORTAL)
        val local = provider.add(SmsProviderRow.TYPE_SENT, date = time.now, creator = null)
        store.own[local.id] = local.date
        val owner = provider.add(SmsProviderRow.TYPE_SENT, date = time.now)

        open()

        assertEquals(listOf(owner.messageId), calls.receivedIds())
        assertEquals("out", calls.calls.single().params.getJSONObject("message").getString("direction"))
    }

    // --- delivery ---

    @Test
    fun atMostEightCallsInFlight() {
        val rows = (1..12).map { inbox(time.now + it) }
        open()
        assertEquals(8, calls.inFlight().size)

        calls.complete(calls.inFlight().first(), SmsReportOutcome.Ok)
        time.runDue()

        assertEquals(8, calls.inFlight().size)
        assertEquals(rows.take(9).map { it.messageId }, calls.receivedIds())
    }

    @Test
    fun invalidParams_dropsTheReport() {
        inbox()
        open()

        calls.completeAll(SmsReporterCore.RECEIVED, SmsReportOutcome.Error("sms: invalid_params: rejected"))
        time.advanceBy(120_000L)

        assertTrue(store.pending.isEmpty())
        assertEquals(1, calls.calls.size)
    }

    @Test
    fun otherErrors_retryWithBackoff() {
        val row = inbox()
        open()

        calls.completeAll(SmsReporterCore.RECEIVED, SmsReportOutcome.Error("sms: device_side_failure: forward failed"))
        time.runDue()
        assertEquals(1, store.pending[row.messageId]!!.attempts)

        time.advanceBy(1_999L)
        assertEquals(1, calls.calls.size)
        time.advanceBy(1L)
        assertEquals(2, calls.calls.size)

        calls.completeAll(SmsReporterCore.RECEIVED, SmsReportOutcome.Error("sms: device_side_failure: forward failed"))
        time.advanceBy(4_999L)
        assertEquals(2, calls.calls.size)
        time.advanceBy(1L)
        assertEquals(3, calls.calls.size)

        calls.completeAll(SmsReporterCore.RECEIVED, SmsReportOutcome.Ok)
        time.runDue()
        assertTrue(store.pending.isEmpty())
    }

    @Test
    fun nextDeadlineReadFailure_stillRetriesTheReport() {
        inbox()
        open()

        store.failNextPendingAt = 1
        calls.completeAll(SmsReporterCore.RECEIVED, SmsReportOutcome.Error("sms: device_side_failure: forward failed"))
        time.runDue()
        assertEquals(1, calls.calls.size)

        time.advanceBy(SmsReporterCore.BLOCKED_RETRY_MS)
        assertEquals(2, calls.calls.size)
    }

    @Test
    fun queueWriteFailureWhileDraining_sendsTheRestAndRetries() {
        val gone = inbox()
        val kept = inbox()
        scanned()
        provider.remove(gone.id)
        store.failPendingWrites = 2

        open()
        assertEquals(listOf(kept.messageId), calls.receivedIds())
        assertTrue(gone.messageId in store.pending)

        time.advanceBy(SmsReporterCore.BLOCKED_RETRY_MS)
        assertFalse(gone.messageId in store.pending)
    }

    @Test
    fun queueWriteFailureAfterAnOutcome_retriesTheReport() {
        inbox()
        open()

        store.failPendingWrites = 1
        calls.completeAll(SmsReporterCore.RECEIVED, SmsReportOutcome.Error("sms: device_side_failure: forward failed"))
        time.runDue()
        assertEquals(1, calls.calls.size)

        time.advanceBy(SmsReporterCore.BLOCKED_RETRY_MS)
        assertEquals(2, calls.calls.size)
    }

    @Test
    fun deleteFailureAfterOk_neverSendsTheReportAgain() {
        val row = inbox()
        open()

        store.failWritesFor += row.messageId
        calls.completeAll(SmsReporterCore.RECEIVED, SmsReportOutcome.Ok)
        time.advanceBy(10 * SmsReporterCore.BLOCKED_RETRY_MS)
        assertEquals(listOf(row.messageId), calls.receivedIds())

        store.failWritesFor.clear()
        time.advanceBy(SmsReporterCore.BLOCKED_RETRY_MS)
        assertTrue(store.pending.isEmpty())
        assertEquals(listOf(row.messageId), calls.receivedIds())
    }

    @Test
    fun deleteFailure_keepsTheReportOutOfOtherDrains() {
        val a = inbox()
        val b = inbox()
        open()
        val (callA, callB) = calls.inFlight()

        store.failWritesFor += a.messageId
        calls.complete(callA, SmsReportOutcome.Ok)
        calls.complete(callB, SmsReportOutcome.Ok)
        time.runDue()
        val c = inbox()
        scanned()

        assertEquals(listOf(a.messageId, b.messageId, c.messageId), calls.receivedIds())
    }

    @Test
    fun updateFailureOnRetry_keepsTheBackoffAndTheAttemptCount() {
        val row = inbox()
        open()
        val error = SmsReportOutcome.Error("sms: device_side_failure: forward failed")

        store.failWritesFor += row.messageId
        calls.completeAll(SmsReporterCore.RECEIVED, error)
        time.advanceBy(1_999L)
        assertEquals(1, calls.calls.size)
        time.advanceBy(1L)
        assertEquals(2, calls.calls.size)

        // The second error counts: the next backoff is 5 s.
        calls.completeAll(SmsReporterCore.RECEIVED, error)
        time.advanceBy(4_999L)
        assertEquals(2, calls.calls.size)
        time.advanceBy(1L)
        assertEquals(3, calls.calls.size)
    }

    @Test
    fun errors_giveUpAfter24h() {
        inbox()
        open()
        var rounds = 0
        while (store.pending.isNotEmpty() && rounds < 2_000) {
            calls.completeAll(SmsReporterCore.RECEIVED, SmsReportOutcome.Error("sms: device_side_failure: forward failed"))
            time.advanceBy(60_000L)
            rounds += 1
        }

        assertTrue(store.pending.isEmpty())
        assertTrue(time.now - START >= SmsReportPolicy.GIVE_UP_MS)
        assertTrue(calls.inFlight().isEmpty())
    }

    @Test
    fun fullQueue_queuesNothingPastIt_andScansOnAsReportsLeave() {
        core = newCore(maxPending = 2)
        val rows = (1..5).map { inbox(time.now + it) }
        scanned()
        assertEquals(rows.take(2).map { it.messageId }, store.pending.keys.toList())

        open()
        // Full, it does not scan again by itself.
        val queries = provider.queries
        time.advanceBy(10 * SmsReporterCore.BLOCKED_RETRY_MS)
        assertEquals(queries, provider.queries)

        while (calls.inFlight().isNotEmpty()) {
            calls.completeAll(SmsReporterCore.RECEIVED, SmsReportOutcome.Ok)
            time.advanceBy(SmsReporterCore.DEBOUNCE_MS)
            assertTrue(store.pending.size <= 2)
        }
        assertEquals(rows.map { it.messageId }, calls.receivedIds())
        assertTrue(store.pending.isEmpty())
    }

    @Test
    fun fullQueueOfUndeliverableReports_givesUpAfter24h_andScanningResumes() {
        core = newCore(maxPending = 2)
        val stuck = (1..2).map { inbox(time.now + it) }
        val next = inbox(time.now + 3)
        open()
        var rounds = 0
        while (stuck.any { it.messageId in store.pending } && rounds < 2_000) {
            calls.completeAll(SmsReporterCore.RECEIVED, SmsReportOutcome.Error("sms: device_side_failure: forward failed"))
            time.advanceBy(60_000L)
            rounds += 1
        }

        assertTrue(time.now - START >= SmsReportPolicy.GIVE_UP_MS)
        assertEquals((stuck + next).map { it.messageId }, calls.receivedIds().distinct())
    }

    @Test
    fun timeouts_retryTwiceMore_thenFinishUnconfirmed() {
        inbox()
        open()

        repeat(3) {
            calls.completeAll(SmsReporterCore.RECEIVED, SmsReportOutcome.Timeout)
            time.advanceBy(10_000L)
        }

        assertEquals(3, calls.calls.size)
        assertTrue(store.pending.isEmpty())
        time.advanceBy(120_000L)
        assertEquals(3, calls.calls.size)
    }

    @Test
    fun closed_returnsToPendingWithoutAnAttempt_andGoesOutOnReopen() {
        val row = inbox()
        open()

        core.onSocketClosed()
        calls.completeAll(SmsReporterCore.RECEIVED, SmsReportOutcome.Closed)
        time.advanceBy(120_000L)

        val pending = store.pending[row.messageId]!!
        assertEquals(0, pending.attempts)
        assertEquals(0, pending.unconfirmed)
        assertEquals(1, calls.calls.size)

        open()
        assertEquals(listOf(row.messageId, row.messageId), calls.receivedIds())
    }

    @Test
    fun closedRace_whileStillMarkedOpen_doesNotSpin() {
        inbox()
        open()

        calls.completeAll(SmsReporterCore.RECEIVED, SmsReportOutcome.Closed)
        time.runDue()
        assertEquals(1, calls.calls.size)

        time.advanceBy(SmsReporterCore.REQUEUE_DELAY_MS)
        assertEquals(2, calls.calls.size)
    }

    @Test
    fun staleOpenAfterClose_doesNotLoopAgainstAClosedSocket() {
        val row = inbox()
        core.onSendStateChanged(relayRecord("s", SmsSendState.SENT))
        scanned()

        // Disconnect raced the handshake: the open event lands after the close and no socket is current.
        calls.socketOpen = false
        core.onSocketClosed()
        open()
        time.advanceBy(60_000L)

        assertTrue(calls.calls.isEmpty())
        assertEquals(0, store.pending[row.messageId]!!.attempts)

        calls.socketOpen = true
        open()
        assertEquals(listOf(SmsReporterCore.RECEIVED, SmsReporterCore.SEND_STATE), calls.calls.map { it.method })
        assertEquals(listOf(row.messageId), calls.receivedIds())
    }

    @Test
    fun cloudSimChanged_rescansWithoutWaitingForTheBlockedRetry() {
        sims = SmsReporterSims.Blocked
        open()
        scanned()

        sims = SmsReporterSims.Covered(null, emptyMap())
        val row = inbox()
        core.onCloudSimChanged()
        time.runDue()

        assertEquals(listOf(row.messageId), calls.receivedIds())
    }

    // --- sms/sendState ---

    @Test
    fun sendState_isReportedWithTheSwitchOff() {
        cloudEnabled = false
        readSms = false
        val record = relayRecord("send-1", SmsSendState.SENT)
        store.records[record.id] = record
        open()

        core.onSendStateChanged(record)
        time.runDue()

        val call = calls.inFlight(SmsReporterCore.SEND_STATE).single()
        val status = call.params.getJSONObject("status")
        assertEquals("send-1", status.getString("id"))
        assertEquals("sent", status.getString("state"))
        calls.complete(call, SmsReportOutcome.Ok)
        time.runDue()
        assertEquals(listOf("send-1:sent"), store.reported)
    }

    @Test
    fun sendState_waitsForTheSocket_andFlushesUnreportedRecordsOnOpen() {
        store.records["a"] = relayRecord("a", SmsSendState.FAILED, updatedAt = 2)
        store.records["b"] = relayRecord("b", SmsSendState.SENT, updatedAt = 1)
        store.records["done"] = relayRecord("done", SmsSendState.SENT, lastReportedState = "sent")
        store.records["fresh"] = relayRecord("fresh", SmsSendState.PENDING)
        core.onSendStateChanged(store.records.getValue("a"))
        time.runDue()
        assertTrue(calls.calls.isEmpty())

        open()

        val sent = calls.inFlight(SmsReporterCore.SEND_STATE).map { it.params.getJSONObject("status").getString("id") }
        assertEquals(listOf("b", "a"), sent)
        val failed = calls.inFlight(SmsReporterCore.SEND_STATE)[1].params.getJSONObject("status")
        assertEquals("RESULT_ERROR_LIMIT_EXCEEDED", failed.getJSONObject("error").getString("code"))
    }

    @Test
    fun sendState_failedLoadOnOpen_isRetriedWithoutAReconnect() {
        store.records["r"] = relayRecord("r", SmsSendState.SENT)
        store.failUnreportedReads = 1

        open()
        assertTrue(calls.calls.isEmpty())

        time.advanceBy(SmsReporterCore.BLOCKED_RETRY_MS)
        val call = calls.inFlight(SmsReporterCore.SEND_STATE).single()
        assertEquals("r", call.params.getJSONObject("status").getString("id"))
    }

    @Test
    fun sendState_newerStateWhileInFlight_goesOutAfterwards() {
        open()
        core.onSendStateChanged(relayRecord("s", SmsSendState.SENT, updatedAt = 1))
        time.runDue()
        core.onSendStateChanged(relayRecord("s", SmsSendState.DELIVERED, updatedAt = 2))
        time.runDue()
        assertEquals(1, calls.inFlight(SmsReporterCore.SEND_STATE).size)

        calls.completeAll(SmsReporterCore.SEND_STATE, SmsReportOutcome.Ok)
        time.runDue()
        val next = calls.inFlight(SmsReporterCore.SEND_STATE).single()
        assertEquals("delivered", next.params.getJSONObject("status").getString("state"))

        calls.complete(next, SmsReportOutcome.Ok)
        time.runDue()
        assertEquals(listOf("s:delivered"), store.reported)
    }

    @Test
    fun sendState_followsTheSameOutcomeRules() {
        open()
        core.onSendStateChanged(relayRecord("e", SmsSendState.SENT))
        core.onSendStateChanged(relayRecord("t", SmsSendState.SENT))
        core.onSendStateChanged(relayRecord("x", SmsSendState.SENT))
        time.runDue()
        val byId = calls.inFlight(SmsReporterCore.SEND_STATE).associateBy { it.params.getJSONObject("status").getString("id") }

        calls.complete(byId.getValue("e"), SmsReportOutcome.Error("sms: device_side_failure: forward failed"))
        calls.complete(byId.getValue("t"), SmsReportOutcome.Timeout)
        calls.complete(byId.getValue("x"), SmsReportOutcome.Error("sms: invalid_params: rejected"))
        time.runDue()
        assertEquals(listOf("x:sent"), store.reported)

        time.advanceBy(2_000L)
        val retried = calls.inFlight(SmsReporterCore.SEND_STATE).map { it.params.getJSONObject("status").getString("id") }
        assertEquals(setOf("e", "t"), retried.toSet())

        calls.completeAll(SmsReporterCore.SEND_STATE, SmsReportOutcome.Timeout)
        time.advanceBy(5_000L)
        calls.completeAll(SmsReporterCore.SEND_STATE, SmsReportOutcome.Timeout)
        time.runDue()
        // t timed out three times; e has one error and two timeouts and keeps retrying.
        assertTrue("t:sent" in store.reported)
        assertFalse("e:sent" in store.reported)
    }

    @Test
    fun sendState_closedIsRequeuedForTheNextOpen() {
        open()
        core.onSendStateChanged(relayRecord("c", SmsSendState.SENT))
        time.runDue()

        core.onSocketClosed()
        calls.completeAll(SmsReporterCore.SEND_STATE, SmsReportOutcome.Closed)
        time.advanceBy(60_000L)
        assertEquals(1, calls.calls.size)
        assertTrue(store.reported.isEmpty())

        open()
        assertEquals(2, calls.calls.size)
    }

    @Test
    fun sendState_unconfirmedCountSurvivesARestart() {
        store.records["r"] = relayRecord("r", SmsSendState.SENT)
        open()
        calls.completeAll(SmsReporterCore.SEND_STATE, SmsReportOutcome.Timeout)
        time.advanceBy(2_000L)
        calls.completeAll(SmsReporterCore.SEND_STATE, SmsReportOutcome.Timeout)
        time.runDue()

        restart()
        open()
        assertTrue(calls.calls.isEmpty())
        time.advanceBy(5_000L)
        calls.completeAll(SmsReporterCore.SEND_STATE, SmsReportOutcome.Timeout)
        time.runDue()

        // The third timeout overall finishes it, as without the restart.
        assertEquals(listOf("r:sent"), store.reported)
    }

    @Test
    fun sendState_24hWindowSurvivesARestart() {
        store.records["g"] = relayRecord("g", SmsSendState.SENT)
        open()
        assertEquals(1, calls.inFlight(SmsReporterCore.SEND_STATE).size)

        // The process dies with the call unanswered and comes back a day later.
        time.advanceBy(SmsReportPolicy.GIVE_UP_MS)
        restart()
        open()
        calls.completeAll(SmsReporterCore.SEND_STATE, SmsReportOutcome.Error("sms: device_side_failure: forward failed"))
        time.runDue()

        assertEquals(listOf("g:sent"), store.reported)
    }

    @Test
    fun sendState_aNewStateStartsFreshBookkeeping() {
        store.records["n"] = relayRecord("n", SmsSendState.SENT)
        open()
        calls.completeAll(SmsReporterCore.SEND_STATE, SmsReportOutcome.Timeout)
        time.advanceBy(2_000L)
        calls.completeAll(SmsReporterCore.SEND_STATE, SmsReportOutcome.Timeout)
        time.runDue()

        store.records["n"] = relayRecord("n", SmsSendState.DELIVERED, updatedAt = 2)
        restart()
        open()

        val call = calls.inFlight(SmsReporterCore.SEND_STATE).single()
        assertEquals("delivered", call.params.getJSONObject("status").getString("state"))
        calls.complete(call, SmsReportOutcome.Timeout)
        time.advanceBy(2_000L)
        assertEquals(1, calls.inFlight(SmsReporterCore.SEND_STATE).size)
        assertTrue(store.reported.isEmpty())
    }

    @Test
    fun sendState_failedMarkReported_isWrittenAgainWithoutResending() {
        store.records["m"] = relayRecord("m", SmsSendState.SENT)
        open()
        store.failMarkReported = 1
        calls.completeAll(SmsReporterCore.SEND_STATE, SmsReportOutcome.Ok)
        time.runDue()
        assertTrue(store.reported.isEmpty())

        // A reconnect finds the record still unreported but does not send it again.
        core.onSocketClosed()
        open()
        assertEquals(1, calls.calls.size)

        time.advanceBy(SmsReporterCore.BLOCKED_RETRY_MS)
        assertEquals(listOf("m:sent"), store.reported)
        assertEquals(1, calls.calls.size)
    }

    @Test
    fun sendState_failedMarkReported_isWrittenAgainWhileOffline() {
        store.records["m"] = relayRecord("m", SmsSendState.SENT)
        open()
        store.failMarkReported = 1
        calls.completeAll(SmsReporterCore.SEND_STATE, SmsReportOutcome.Ok)
        time.runDue()

        calls.socketOpen = false
        core.onSocketClosed()
        time.advanceBy(SmsReporterCore.BLOCKED_RETRY_MS)
        assertEquals(listOf("m:sent"), store.reported)

        // A restart before the next connect finds it reported.
        restart()
        open()
        assertTrue(calls.calls.isEmpty())
    }

    @Test
    fun sendState_ignoresLocalRecordsAndInitialPending() {
        open()
        core.onSendStateChanged(relayRecord("l", SmsSendState.SENT, origin = "local"))
        core.onSendStateChanged(relayRecord("p", SmsSendState.PENDING))
        time.runDue()

        assertTrue(calls.calls.isEmpty())
    }

    companion object {
        private const val PORTAL = "com.mobilerun.portal"
        private const val START = 1_700_000_000_000L
    }
}
