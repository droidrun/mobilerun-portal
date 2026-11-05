package com.mobilerun.portal.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsStorePolicyTest {
    private val day = 24L * 60L * 60L * 1000L
    private val pkg = "com.mobilerun.portal"

    private companion object {
        const val CURRENT = "current-instance"
        const val UPTIME = 3_600_000L
    }

    @Test
    fun evictions_dropExpiredThenOldestTerminalFirst() {
        val now = 30 * day
        val records = listOf(
            SmsStorePolicy.EvictionCandidate("expired", terminal = false, createdAt = now - 8 * day),
            SmsStorePolicy.EvictionCandidate("old-pending", terminal = false, createdAt = now - 3 * day),
            SmsStorePolicy.EvictionCandidate("new-terminal", terminal = true, createdAt = now - 1 * day),
            SmsStorePolicy.EvictionCandidate("old-terminal", terminal = true, createdAt = now - 2 * day),
            SmsStorePolicy.EvictionCandidate("new-pending", terminal = false, createdAt = now),
        )

        assertEquals(listOf("expired"), SmsStorePolicy.evictions(records, now, maxRecords = 4))
        assertEquals(listOf("expired", "old-terminal", "new-terminal"), SmsStorePolicy.evictions(records, now, maxRecords = 2))
    }

    @Test
    fun evictions_overTheCapTakeAwaitingDeliveryNextButNeverOutstandingSends() {
        val now = 30 * day
        val records = listOf(
            SmsStorePolicy.EvictionCandidate("old-pending", terminal = false, createdAt = now - 4 * day),
            SmsStorePolicy.EvictionCandidate("old-awaiting", terminal = false, createdAt = now - 3 * day, awaitingDelivery = true),
            SmsStorePolicy.EvictionCandidate("new-awaiting", terminal = false, createdAt = now - 2 * day, awaitingDelivery = true),
            SmsStorePolicy.EvictionCandidate("terminal", terminal = true, createdAt = now - 1 * day),
            SmsStorePolicy.EvictionCandidate("new-pending", terminal = false, createdAt = now),
        )

        assertEquals(listOf("terminal", "old-awaiting"), SmsStorePolicy.evictions(records, now, maxRecords = 3))
        assertEquals(
            listOf("terminal", "old-awaiting", "new-awaiting"),
            SmsStorePolicy.evictions(records, now, maxRecords = 2),
        )
        // Only outstanding sends left over the cap: the insert is refused instead.
        assertNull(SmsStorePolicy.evictions(records, now, maxRecords = 1))
        // Expiry still drops an outstanding send past the TTL.
        assertEquals(listOf("old-pending"), SmsStorePolicy.evictions(records, now, maxRecords = 4, ttlMs = 3 * day + 1))
    }

    @Test
    fun evictions_overTheCapNeverTakeRecentlyUnreportedRecords() {
        val now = 30 * day
        val records = listOf(
            SmsStorePolicy.EvictionCandidate("old-unreported", terminal = true, createdAt = now - 3 * day, unreported = true, updatedAt = now),
            SmsStorePolicy.EvictionCandidate(
                "old-awaiting-unreported", terminal = false, createdAt = now - 2 * day, awaitingDelivery = true, unreported = true,
                updatedAt = now - SmsStorePolicy.UNREPORTED_KEEP_MS + 1,
            ),
            SmsStorePolicy.EvictionCandidate("reported", terminal = true, createdAt = now - 1 * day),
            SmsStorePolicy.EvictionCandidate("new-pending", terminal = false, createdAt = now),
        )

        assertEquals(listOf("reported"), SmsStorePolicy.evictions(records, now, maxRecords = 3))
        // Its sms/sendState and request id would be lost: the insert is refused instead.
        assertNull(SmsStorePolicy.evictions(records, now, maxRecords = 2))
    }

    @Test
    fun evictions_overTheCapTakeRecordsUnreportedTooLongAfterReportedOnes() {
        // The relay stalls on sms/sendState: keeping these would refuse every send.
        val now = 30 * day
        val stale = now - SmsStorePolicy.UNREPORTED_KEEP_MS
        val records = listOf(
            SmsStorePolicy.EvictionCandidate("old-stale", terminal = true, createdAt = now - 3 * day, unreported = true, updatedAt = stale),
            SmsStorePolicy.EvictionCandidate(
                "awaiting-stale", terminal = false, createdAt = now - 2 * day, awaitingDelivery = true, unreported = true, updatedAt = stale,
            ),
            SmsStorePolicy.EvictionCandidate("recent", terminal = true, createdAt = now - 2 * day, unreported = true, updatedAt = stale + 1),
            SmsStorePolicy.EvictionCandidate("reported", terminal = true, createdAt = now - 1 * day),
            SmsStorePolicy.EvictionCandidate("new-pending", terminal = false, createdAt = now),
        )

        assertEquals(listOf("reported", "old-stale"), SmsStorePolicy.evictions(records, now, maxRecords = 3))
        assertEquals(listOf("reported", "old-stale", "awaiting-stale"), SmsStorePolicy.evictions(records, now, maxRecords = 2))
        assertNull(SmsStorePolicy.evictions(records, now, maxRecords = 1))
    }

    @Test
    fun recovery_ignoresTerminalExpiredAndCurrentProcessRecords() {
        val record = SmsSendRecordTest.record(parts = 1, createdAt = 1_000)
        val dispatched = record.copy(phase = SmsSendPhase.DISPATCHED)
        assertEquals(
            SmsRecoveryDecision.None,
            SmsSendRecovery.decide(record.copy(phase = SmsSendPhase.TERMINAL), 1_000_000, UPTIME, CURRENT, 500_000, emptyList(), SmsOwnSends(), pkg),
        )
        assertEquals(
            SmsRecoveryDecision.None,
            SmsSendRecovery.decide(record.copy(instanceId = CURRENT), 1_000_000, UPTIME, CURRENT, 500_000, emptyList(), SmsOwnSends(), pkg),
        )
        assertEquals(
            SmsRecoveryDecision.None,
            SmsSendRecovery.decide(dispatched.copy(instanceId = CURRENT), 1_000_000, UPTIME, CURRENT, 500_000, emptyList(), SmsOwnSends(), pkg),
        )
        val expired = 1_001 + SmsStorePolicy.RECORD_TTL_MS
        assertEquals(
            SmsRecoveryDecision.None,
            SmsSendRecovery.decide(dispatched, expired, UPTIME, CURRENT, 500_000, emptyList(), SmsOwnSends(), pkg),
        )
    }

    @Test
    fun recovery_dispatchedRecordOfAnEarlierProcess_settlesFromItsRowButNeverFailsWithoutOne() {
        // The sent callback's write was lost with the process.
        val record = SmsSendRecordTest.record(parts = 2, createdAt = 1_000).copy(phase = SmsSendPhase.DISPATCHED)
        val now = 100_000L
        val decide = { rows: List<SmsProviderRow>? ->
            SmsSendRecovery.decide(record, now, UPTIME, CURRENT, 60_000, rows, SmsOwnSends(), pkg)
        }
        val recheck = SmsRecoveryDecision.Wait(now + SmsStoreRetry.SLOW_RETRY_DELAY_MS)

        val sent = decide(listOf(row(id = 9, type = SmsProviderRow.TYPE_SENT, date = 2_000))) as SmsRecoveryDecision.Resolve
        assertEquals(SmsSendState.SENT, sent.record.state)
        assertEquals(setOf(0, 1), sent.record.sentParts)
        assertTrue(sent.record.isTerminal)
        assertEquals(9L, sent.providerId)
        assertNull(sent.recheckAt)

        val failed = decide(listOf(row(id = 9, type = SmsProviderRow.TYPE_FAILED, date = 2_000))) as SmsRecoveryDecision.Resolve
        assertEquals(SmsSendState.FAILED, failed.record.state)
        assertEquals("RESULT_ERROR_GENERIC_FAILURE", failed.record.errorCode)

        // SmsManager took it: no row or a row still in the outbox is not a failure.
        assertEquals(recheck, decide(emptyList()))
        assertEquals(recheck, decide(listOf(row(id = 9, type = SmsProviderRow.TYPE_OUTBOX, date = 2_000))))
        assertEquals(recheck, decide(null))
    }

    @Test
    fun recovery_dispatchedRecord_waitsAMinuteOfThisProcessForQueuedCallbacks() {
        // A failure callback restarted the Portal; the framework already failed the row.
        val record = SmsSendRecordTest.record(parts = 1, createdAt = 1_000).copy(phase = SmsSendPhase.DISPATCHED)
        val rows = listOf(row(id = 9, type = SmsProviderRow.TYPE_FAILED, date = 2_000))
        val now = 600_000L

        assertEquals(
            SmsRecoveryDecision.Wait(now + 59_000),
            SmsSendRecovery.decide(record, now, UPTIME, CURRENT, 1_000, rows, SmsOwnSends(), pkg),
        )
        val failed = SmsSendRecovery.decide(record, now, UPTIME, CURRENT, 60_000, rows, SmsOwnSends(), pkg)
            as SmsRecoveryDecision.Resolve
        assertEquals(SmsSendState.FAILED, failed.record.state)
    }

    @Test
    fun recovery_dispatchedRecordAwaitingDelivery_settlesFromItsOwnRowStatus() {
        // The sent callback landed and claimed row 9; the delivered callback's write was lost.
        val record = SmsSendRecordTest.record(parts = 1, deliveryReport = true, createdAt = 1_000).copy(
            phase = SmsSendPhase.DISPATCHED,
            state = SmsSendState.SENT,
            sentParts = setOf(0),
            messageUri = "content://sms/9",
        )
        val own = SmsOwnSends(mapOf(9L to 2_000L))
        val now = 100_000L
        val decide = { r: SmsSendRecord, status: Int? ->
            val rows = listOf(
                row(id = 8, type = SmsProviderRow.TYPE_SENT, date = 1_500, status = 64),
                row(id = 9, type = SmsProviderRow.TYPE_SENT, date = 2_000, status = status),
            )
            SmsSendRecovery.decide(r, now, UPTIME, CURRENT, 60_000, rows, own, pkg)
        }
        val recheck = SmsRecoveryDecision.Wait(now + SmsStoreRetry.SLOW_RETRY_DELAY_MS)

        val delivered = decide(record, 0) as SmsRecoveryDecision.Resolve
        assertEquals(SmsSendState.DELIVERED, delivered.record.state)
        assertTrue(delivered.record.isTerminal)
        assertEquals(9L, delivered.providerId)

        val undelivered = decide(record, 64) as SmsRecoveryDecision.Resolve
        assertEquals(SmsSendState.FAILED, undelivered.record.state)
        assertEquals("DELIVERY_FAILED", undelivered.record.errorCode)

        assertEquals(recheck, decide(record, 32))
        assertEquals(recheck, decide(record, -1))
        assertEquals(recheck, decide(record, null))
        // A multipart row holds the latest part's report only.
        assertEquals(recheck, decide(record.copy(parts = 2, sentParts = setOf(0, 1)), 0))
    }

    @Test
    fun recovery_sentButUndeliveredRecord_staysACandidateAndIsCheckedAgain() {
        val record = SmsSendRecordTest.record(parts = 1, deliveryReport = true, createdAt = 1_000)
        val now = 100_000L

        val sent = SmsSendRecovery.decide(
            record, now, UPTIME, CURRENT, 60_000, listOf(row(id = 9, type = SmsProviderRow.TYPE_SENT, date = 2_000, status = 32)),
            SmsOwnSends(), pkg,
        ) as SmsRecoveryDecision.Resolve
        assertEquals(SmsSendState.SENT, sent.record.state)
        assertEquals(SmsSendPhase.DISPATCHED, sent.record.phase)
        assertEquals(now + SmsStoreRetry.SLOW_RETRY_DELAY_MS, sent.recheckAt)

        // The next check finds its row although recovery listed it as the Portal's own.
        val delivered = SmsSendRecovery.decide(
            sent.record, now, UPTIME, CURRENT, 60_000, listOf(row(id = 9, type = SmsProviderRow.TYPE_SENT, date = 2_000, status = 0)),
            SmsOwnSends(mapOf(9L to now)), pkg,
        ) as SmsRecoveryDecision.Resolve
        assertEquals(SmsSendState.DELIVERED, delivered.record.state)
    }

    @Test
    fun recovery_waitsAMinuteBeforeCheckingTheProvider() {
        val record = SmsSendRecordTest.record(parts = 1, createdAt = 1_000)
        assertEquals(
            SmsRecoveryDecision.Wait(61_000),
            SmsSendRecovery.decide(record, 30_000, UPTIME, CURRENT, 10_000, emptyList(), SmsOwnSends(), pkg),
        )
    }

    @Test
    fun recovery_clockSetBackSinceThePrepare_stillRecoversAfterAMinuteOfThisProcess() {
        // Prepared by an earlier process; the clock was then set back 10 minutes.
        val record = SmsSendRecordTest.record(parts = 1, createdAt = 1_000_000)
        val opened = 400_000L

        assertEquals(
            SmsRecoveryDecision.Wait(opened + 60_000),
            SmsSendRecovery.decide(record, opened + 10_000, UPTIME, CURRENT, 10_000, emptyList(), SmsOwnSends(), pkg),
        )
        val decision = SmsSendRecovery.decide(record, opened + 60_000, UPTIME, CURRENT, 60_000, emptyList(), SmsOwnSends(), pkg)
            as SmsRecoveryDecision.Resolve
        assertEquals(SmsSendState.FAILED, decision.record.state)
        assertEquals("RESULT_INTERRUPTED", decision.record.errorCode)
    }

    @Test
    fun recovery_clockJumpedAhead_stillWaitsAMinuteOfUptimeSinceTheInsert() {
        // Inserted 5 s of uptime ago by a process that died; the clock then jumped 5 minutes ahead.
        val record = SmsSendRecordTest.record(parts = 1, createdAt = 1_000_000).copy(createdElapsed = UPTIME - 5_000)
        val now = 1_000_000L + 5 * 60_000 + 5_000

        assertEquals(
            SmsRecoveryDecision.Wait(now + 55_000),
            SmsSendRecovery.decide(record, now, UPTIME, CURRENT, 2_000, emptyList(), SmsOwnSends(), pkg),
        )
    }

    @Test
    fun recovery_clockSetBackBeforeTheSendCompleted_stillMatchesItsRow() {
        // Inserted 90 s of uptime ago; the clock was set back 10 minutes before the send completed.
        val record = SmsSendRecordTest.record(parts = 1, createdAt = 1_000_000).copy(createdElapsed = UPTIME - 90_000)
        val insertedNow = 1_000_000L - 10 * 60_000
        val older = row(id = 8, type = SmsProviderRow.TYPE_SENT, date = insertedNow - 1)
        val sent = row(id = 9, type = SmsProviderRow.TYPE_SENT, date = insertedNow + 3_000)

        assertEquals(insertedNow, SmsSendRecovery.evidenceFrom(record, insertedNow + 90_000, UPTIME))
        val decision = SmsSendRecovery.decide(
            record, insertedNow + 90_000, UPTIME, CURRENT, 60_000, listOf(older, sent), SmsOwnSends(), pkg,
        ) as SmsRecoveryDecision.Resolve
        assertEquals(9L, decision.providerId)
        assertEquals(SmsSendState.SENT, decision.record.state)
    }

    @Test
    fun recovery_matchingPortalRowMakesItSent() {
        val record = SmsSendRecordTest.record(parts = 2, createdAt = 1_000)
        val rows = listOf(
            row(id = 5, type = SmsProviderRow.TYPE_SENT, date = 999),
            row(id = 6, type = SmsProviderRow.TYPE_SENT, date = 2_000, creator = "com.other"),
            row(id = 7, type = SmsProviderRow.TYPE_SENT, date = 2_000, body = "other"),
            row(id = 8, type = SmsProviderRow.TYPE_SENT, date = 2_000, address = "+15550009999"),
            row(id = 9, type = SmsProviderRow.TYPE_SENT, date = 2_000, address = "+1 (555) 000-1111"),
        )

        val decision = SmsSendRecovery.decide(record, 100_000, UPTIME, CURRENT, 50_000, rows, SmsOwnSends(), pkg) as SmsRecoveryDecision.Resolve

        assertEquals(9L, decision.providerId)
        assertEquals(SmsSendState.SENT, decision.record.state)
        assertEquals(setOf(0, 1), decision.record.sentParts)
        assertEquals(SmsSendPhase.TERMINAL, decision.record.phase)
    }

    @Test
    fun recovery_skipsRowsAlreadyClaimedAndFailsWithoutEvidence() {
        val record = SmsSendRecordTest.record(parts = 1, createdAt = 1_000)
        val rows = listOf(row(id = 9, type = SmsProviderRow.TYPE_SENT, date = 2_000))

        val decision = SmsSendRecovery.decide(record, 100_000, UPTIME, CURRENT, 50_000, rows, SmsOwnSends(mapOf(9L to 2_000L)), pkg)
            as SmsRecoveryDecision.Resolve

        assertEquals(null, decision.providerId)
        assertEquals(SmsSendState.FAILED, decision.record.state)
        assertEquals("RESULT_INTERRUPTED", decision.record.errorCode)
    }

    @Test
    fun recovery_withoutReadSms_waitsInsteadOfFailing() {
        val record = SmsSendRecordTest.record(parts = 1, createdAt = 1_000)

        assertEquals(
            SmsRecoveryDecision.Wait(100_000 + SmsStoreRetry.SLOW_RETRY_DELAY_MS),
            SmsSendRecovery.decide(record, 100_000, UPTIME, CURRENT, 50_000, null, SmsOwnSends(), pkg),
        )
    }

    @Test
    fun ownSends_aReusedIdNewerThanTheRecordIsNotOwn() {
        val own = SmsOwnSends(mapOf(9L to 2_000L))

        assertTrue(row(id = 9, type = SmsProviderRow.TYPE_SENT, date = 1_900) in own)
        assertTrue(row(id = 9, type = SmsProviderRow.TYPE_SENT, date = 2_000 + SmsOwnSends.CLOCK_SLACK_MS) in own)
        assertFalse(row(id = 9, type = SmsProviderRow.TYPE_INBOX, date = 2_001 + SmsOwnSends.CLOCK_SLACK_MS) in own)
        assertFalse(row(id = 8, type = SmsProviderRow.TYPE_SENT, date = 1_900) in own)
        assertTrue(row(id = 8, type = SmsProviderRow.TYPE_SENT, date = 1_900) in own.plus(8L, 1_950L))
    }

    @Test
    fun ownSends_aMarkerWithItsRowDateMatchesThatRowOnly() {
        val own = SmsOwnSends(mapOf(9L to 2_000L), mapOf(9L to 1_500L))

        assertTrue(row(id = 9, type = SmsProviderRow.TYPE_SENT, date = 1_500) in own)
        // The _id reused by an imported row, or one added after the clock was set back.
        assertFalse(row(id = 9, type = SmsProviderRow.TYPE_SENT, date = 1_400) in own)
        assertFalse(row(id = 9, type = SmsProviderRow.TYPE_SENT, date = 1_600) in own)

        val plus = own.plus(8L, 1_950L, rowDate = 1_900L)
        assertTrue(row(id = 8, type = SmsProviderRow.TYPE_SENT, date = 1_900) in plus)
        assertFalse(row(id = 8, type = SmsProviderRow.TYPE_SENT, date = 1_800) in plus)
        // Recorded again without a row date: the recorded time decides.
        assertTrue(row(id = 9, type = SmsProviderRow.TYPE_SENT, date = 1_400) in own.plus(9L, 2_000L))
    }

    @Test
    fun recovery_outboxRowMeansDispatchedAndFailedRowMeansFailed() {
        val record = SmsSendRecordTest.record(parts = 1, createdAt = 1_000)
        val outbox = SmsSendRecovery.decide(
            record, 100_000, UPTIME, CURRENT, 50_000, listOf(row(id = 3, type = SmsProviderRow.TYPE_QUEUED, date = 2_000)), SmsOwnSends(), pkg,
        ) as SmsRecoveryDecision.Resolve
        assertEquals(SmsSendPhase.DISPATCHED, outbox.record.phase)
        assertEquals(SmsSendState.PENDING, outbox.record.state)
        assertEquals(3L, outbox.providerId)
        assertEquals(100_000 + SmsStoreRetry.SLOW_RETRY_DELAY_MS, outbox.recheckAt)

        val failed = SmsSendRecovery.decide(
            record, 100_000, UPTIME, CURRENT, 50_000, listOf(row(id = 4, type = SmsProviderRow.TYPE_FAILED, date = 2_000)), SmsOwnSends(), pkg,
        ) as SmsRecoveryDecision.Resolve
        assertEquals(SmsSendState.FAILED, failed.record.state)
        assertTrue(failed.record.isTerminal)
    }

    @Test
    fun recoveryEvidence_readsPastAFullPageOfPortalSends() {
        val provider = FakeSmsProvider()
        val older = SmsSendRecordTest.record(parts = 1, createdAt = 1_000)
            .copy(id = "older", to = "+15550002222", bodyHash = SmsHashes.bodyHash("first"))
        val first = provider.add(SmsProviderRow.TYPE_SENT, date = 1_500, address = "+15550002222", body = "first", creator = pkg)
        repeat(SmsSendRecovery.PAGE_SIZE + 50) {
            provider.add(SmsProviderRow.TYPE_SENT, date = 2_000L + it, address = "+15550003333", body = "other", creator = pkg)
        }
        val newer = SmsSendRecordTest.record(parts = 1, createdAt = 3_000)
        val match = provider.add(SmsProviderRow.TYPE_SENT, date = 3_500, body = "hello", creator = pkg)

        val rows = SmsSendRecovery.evidence(listOf(older, newer)) { after, limit ->
            provider.ownSends(pkg, older.createdAt, after, limit)
        }

        assertEquals(listOf(first.id, match.id), rows.map { it.id })
        val decision = SmsSendRecovery.decide(newer, 100_000, UPTIME, CURRENT, 50_000, rows, SmsOwnSends(), pkg) as SmsRecoveryDecision.Resolve
        assertEquals(match.id, decision.providerId)
        assertEquals(SmsSendState.SENT, decision.record.state)
    }

    @Test
    fun storeRetry_runsAFailedWriteAgainUntilItLands() {
        val time = FakeReporterScheduler()
        val retry = SmsStoreRetry(schedule = { delay, task -> time.scheduler.schedule(delay, task) })
        var failures = 2
        var runs = 0

        retry.run("write") {
            runs += 1
            if (failures-- > 0) throw IllegalStateException("disk I/O error")
        }
        assertEquals(1, runs)

        time.advanceBy(SmsStoreRetry.RETRY_DELAY_MS)
        assertEquals(2, runs)
        time.advanceBy(SmsStoreRetry.RETRY_DELAY_MS)
        assertEquals(3, runs)
        time.advanceBy(10 * SmsStoreRetry.RETRY_DELAY_MS)
        assertEquals(3, runs)
    }

    @Test
    fun storeRetry_givesUpAfterTheLastAttempt() {
        val time = FakeReporterScheduler()
        val retry = SmsStoreRetry(schedule = { delay, task -> time.scheduler.schedule(delay, task) })
        var runs = 0

        retry.run("write") {
            runs += 1
            throw IllegalStateException("disk I/O error")
        }
        time.advanceBy(100 * SmsStoreRetry.RETRY_DELAY_MS)

        assertEquals(SmsStoreRetry.MAX_ATTEMPTS, runs)
    }

    @Test
    fun storeRetry_untilDone_keepsTryingSlowerAfterTheLastAttempt() {
        val time = FakeReporterScheduler()
        val retry = SmsStoreRetry(schedule = { delay, task -> time.scheduler.schedule(delay, task) })
        var failures = SmsStoreRetry.MAX_ATTEMPTS + 2
        var runs = 0

        retry.run("recover", untilDone = true) {
            runs += 1
            if (failures-- > 0) throw IllegalStateException("provider unavailable")
        }
        time.advanceBy((SmsStoreRetry.MAX_ATTEMPTS - 1) * SmsStoreRetry.RETRY_DELAY_MS)
        assertEquals(SmsStoreRetry.MAX_ATTEMPTS, runs)

        time.advanceBy(SmsStoreRetry.SLOW_RETRY_DELAY_MS - 1)
        assertEquals(SmsStoreRetry.MAX_ATTEMPTS, runs)
        time.advanceBy(1 + 2 * SmsStoreRetry.SLOW_RETRY_DELAY_MS)
        assertEquals(SmsStoreRetry.MAX_ATTEMPTS + 3, runs)
        time.advanceBy(10 * SmsStoreRetry.SLOW_RETRY_DELAY_MS)
        assertEquals(SmsStoreRetry.MAX_ATTEMPTS + 3, runs)
    }

    @Test
    fun preparedDiscard_thatFailed_runsBeforeALookupOfItsId() {
        val time = FakeReporterScheduler()
        val retry = SmsStoreRetry(schedule = { delay, task -> time.scheduler.schedule(delay, task) })
        val prepared = mutableMapOf("x" to "token", "other" to "token")
        var failures = 1
        val discards = SmsPreparedDiscards(retry) { id, callbackToken ->
            if (failures-- > 0) throw IllegalStateException("disk I/O error")
            if (prepared[id] == callbackToken) prepared.remove(id)
        }

        discards.discard("x", "token")
        assertTrue("x" in prepared)

        assertEquals(null, discards.find("x") { prepared["x"] })
        assertEquals("token", discards.find("other") { prepared["other"] })
        time.advanceBy(10 * SmsStoreRetry.RETRY_DELAY_MS)
        assertEquals(mapOf("other" to "token"), prepared)
    }

    @Test
    fun preparedDiscard_lookupThrowsWhileTheDiscardKeepsFailing() {
        val time = FakeReporterScheduler()
        val retry = SmsStoreRetry(schedule = { delay, task -> time.scheduler.schedule(delay, task) })
        var failing = true
        var deletes = 0
        val discards = SmsPreparedDiscards(retry) { _, _ ->
            if (failing) throw IllegalStateException("disk I/O error")
            deletes += 1
        }
        discards.discard("x", "token")
        time.advanceBy(10 * SmsStoreRetry.RETRY_DELAY_MS)

        assertThrows(IllegalStateException::class.java) { discards.find("x") { "stale" } }
        failing = false
        assertEquals("fresh", discards.find("x") { "fresh" })
        assertEquals("fresh", discards.find("x") { "fresh" })
        assertEquals(1, deletes)
    }

    private fun row(
        id: Long,
        type: Int,
        date: Long,
        address: String = "+15550001111",
        body: String = "hello",
        creator: String = pkg,
        status: Int? = null,
    ) = SmsProviderRow(id = id, type = type, address = address, body = body, date = date, creator = creator, status = status)
}
