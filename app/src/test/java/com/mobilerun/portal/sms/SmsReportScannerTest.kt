package com.mobilerun.portal.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SmsReportScannerTest {
    private lateinit var provider: FakeSmsProvider
    private lateinit var scanner: SmsReportScanner
    private val own = mutableMapOf<Long, Long>()
    private val ownDates = mutableMapOf<Long, Long>()
    private val ring = mutableSetOf<String>()

    @Before
    fun setUp() {
        provider = FakeSmsProvider()
        scanner = SmsReportScanner(provider, PORTAL)
    }

    private fun seeded(coveredFrom: Long = NOW): SmsScanState {
        val latest = provider.latest()
        val mark = SmsWatermark(latest?.id ?: 0L, latest?.date ?: 0L)
        return SmsScanState(mark, mark, coveredFrom, mark.id)
    }

    private fun scan(state: SmsScanState, filter: Int? = null, now: Long = NOW, room: Int = Int.MAX_VALUE): SmsScanResult =
        scanner.scan(state, SmsOwnSends(own.toMap(), ownDates.toMap()), { it in ring }, filter, now, room).also { result ->
            ring += result.reports.map { it.msgId }
        }

    private fun SmsScanResult.ids() = reports.map { it.msgId }

    // --- basics ---

    @Test
    fun newInboxRows_reportedInIdOrder_andWatermarkAdvances() {
        val state = seeded()
        val a = provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 1)
        val b = provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 2)

        val result = scan(state)

        assertEquals(listOf(a.messageId, b.messageId), result.ids())
        assertTrue(result.reports.all { it.direction == "in" })
        assertEquals(SmsWatermark(b.id, b.date), result.state.inbound)
        assertFalse(result.more)
        assertTrue(scan(result.state).reports.isEmpty())
    }

    @Test
    fun enabling_neverBackfills() {
        repeat(3) { provider.add(SmsProviderRow.TYPE_INBOX, date = NOW - 10_000 + it) }
        provider.add(SmsProviderRow.TYPE_SENT, date = NOW - 5_000)

        assertTrue(scan(seeded()).reports.isEmpty())
    }

    @Test
    fun onlyInboxAndSentRowsAreReported() {
        val state = seeded()
        provider.add(SmsProviderRow.TYPE_DRAFT, date = NOW + 1)
        provider.add(SmsProviderRow.TYPE_FAILED, date = NOW + 2)
        val inbox = provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 3)
        val sent = provider.add(SmsProviderRow.TYPE_SENT, date = NOW + 4)

        val result = scan(state)

        assertEquals(listOf(inbox.messageId, sent.messageId), result.ids())
        assertEquals(listOf("in", "out"), result.reports.map { it.direction })
    }

    // --- coveredFrom and caps ---

    @Test
    fun restoreFlood_datedBeforeCoveredFrom_isSkippedButWatermarkMoves() {
        val state = seeded(coveredFrom = NOW)
        provider.add(SmsProviderRow.TYPE_INBOX, date = NOW - SmsReportScanner.SLACK_MS - 1)
        provider.add(SmsProviderRow.TYPE_INBOX, date = NOW - 1)
        val fresh = provider.add(SmsProviderRow.TYPE_INBOX, date = NOW)

        val result = scan(state)

        assertEquals(listOf(fresh.messageId), result.ids())
        assertEquals(fresh.id, result.state.inbound.id)
    }

    @Test
    fun watermarkRowGone_rowsDatedBeforeCoveredFromInTheWindow_areNeverReported() {
        provider.add(SmsProviderRow.TYPE_INBOX, date = NOW - 2 * 60_000)
        provider.add(SmsProviderRow.TYPE_SENT, date = NOW - 90_000)
        val outbox = provider.add(SmsProviderRow.TYPE_OUTBOX, date = NOW - 75_000)
        val newest = provider.add(SmsProviderRow.TYPE_INBOX, date = NOW - 60_000)
        val state = seeded(coveredFrom = NOW)

        // The owner deletes the newest thread; the next SMS reuses its _id.
        provider.remove(newest.id)
        val arrived = provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 60_000)
        assertEquals(newest.id, arrived.id)

        val result = scan(state)
        assertEquals(listOf(arrived.messageId), result.ids())
        assertTrue(result.state.recheck.isEmpty())

        provider.update(outbox.id) { it.copy(type = SmsProviderRow.TYPE_SENT) }
        val again = scan(result.state.copy(recheck = listOf(SmsRecheck(outbox.id, NOW))))
        assertTrue(again.reports.isEmpty())
    }

    @Test
    fun aScanReadsAtMost50Rows_andAsksForMore() {
        val state = seeded()
        val rows = (1..60).map { provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + it) }
        val sent = provider.add(SmsProviderRow.TYPE_SENT, date = NOW + 100)

        val first = scan(state)
        assertEquals(rows.take(50).map { it.messageId }, first.ids())
        assertTrue(first.more)
        assertEquals(rows[49].id, first.state.inbound.id)
        // The out page had no budget left and did not move.
        assertEquals(state.out, first.state.out)

        val second = scan(first.state)
        assertEquals(rows.drop(50).map { it.messageId } + sent.messageId, second.ids())
        assertFalse(second.more)
    }

    // --- queue room ---

    @Test
    fun fullQueue_stopsBeforeTheFirstRowWithoutRoom_andReadsOnFromThere() {
        val state = seeded()
        val a = provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 1)
        val b = provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 2)
        val portal = provider.add(SmsProviderRow.TYPE_SENT, date = NOW + 3, creator = PORTAL)
        val sent = provider.add(SmsProviderRow.TYPE_SENT, date = NOW + 4)

        val first = scan(state, room = 1)
        assertEquals(listOf(a.messageId), first.ids())
        assertTrue(first.queueFull)
        assertEquals(SmsWatermark(a.id, a.date), first.state.inbound)
        // A row needing no room is passed.
        assertEquals(SmsWatermark(portal.id, portal.date), first.state.out)

        val second = scan(first.state)
        assertEquals(listOf(b.messageId, sent.messageId), second.ids())
        assertFalse(second.queueFull)
    }

    @Test
    fun fullQueue_dateReReadKeepsItsCursorAtTheLastQueuedRow() {
        val gone = provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 1)
        val state = seeded()
        val rows = (1..3).map { provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 1 + it) }
        provider.remove(gone.id)
        val top = SmsWatermark(rows[2].id, rows[2].date)

        val first = scan(state, room = 2)
        assertEquals(rows.take(2).map { it.messageId }, first.ids())
        assertEquals(state.inbound, first.state.inbound)
        assertEquals(SmsRecovery(rows[1].date, rows[1].id, top), first.state.inboundRecovery)

        val second = scan(first.state)
        assertEquals(listOf(rows[2].messageId), second.ids())
        assertNull(second.state.inboundRecovery)
        assertEquals(top, second.state.inbound)
    }

    @Test
    fun fullQueue_recheckTurnedSentStaysUntilThereIsRoom() {
        val state = seeded()
        val outbox = provider.add(SmsProviderRow.TYPE_OUTBOX, date = NOW + 1)
        val first = scan(state)
        provider.update(outbox.id) { it.copy(type = SmsProviderRow.TYPE_SENT) }

        val full = scan(first.state, room = 0)
        assertTrue(full.reports.isEmpty())
        assertTrue(full.queueFull)
        assertEquals(first.state.recheck, full.state.recheck)

        assertEquals(listOf(outbox.messageId), scan(full.state, room = 1).ids())
    }

    @Test
    fun fullQueue_freshDateReReadWithNoRoom_resumesAtItsFirstRow() {
        // Reported while the clock ran 2 h ahead; the clock is corrected, the row deleted and its _id reused.
        val state = seeded()
        val ahead = provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 2 * HOUR)
        val reported = scan(state)
        provider.remove(ahead.id)
        val reused = provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 1_000)
        assertEquals(ahead.id, reused.id)

        val blocked = scan(reported.state, now = NOW + 2_000, room = 0)
        assertTrue(blocked.reports.isEmpty())
        assertEquals(SmsRecovery(reused.date, reused.id - 1, SmsWatermark(reused.id, reused.date)), blocked.state.inboundRecovery)

        // More SMS arrive while the queue stays full for 20 min.
        val later = provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 10 * 60_000)
        val drained = scan(blocked.state, now = NOW + 20 * 60_000)
        val next = scan(drained.state, now = NOW + 21 * 60_000)
        assertEquals(listOf(reused.messageId, later.messageId), drained.ids() + next.ids())
    }

    @Test
    fun fullQueue_recheckTurnedSentIsNotEvictedOverCapacity() {
        val held = (0 until SmsReportScanner.MAX_RECHECKS).map { provider.add(SmsProviderRow.TYPE_OUTBOX, date = NOW + it) }
        val state = seeded().copy(recheck = held.mapIndexed { i, row -> SmsRecheck(row.id, NOW + i) })
        provider.update(held[0].id) { it.copy(type = SmsProviderRow.TYPE_SENT) }
        val newest = provider.add(SmsProviderRow.TYPE_OUTBOX, date = NOW + 1_000)

        val full = scan(state, now = NOW + 2_000, room = 0)
        assertTrue(full.queueFull)
        // The oldest unsent goes instead.
        assertEquals(listOf(SmsRecheck(held[1].id, NOW + 1)), full.evictedRechecks)
        assertEquals(listOf(held[0].id) + held.drop(2).map { it.id } + newest.id, full.state.recheck.map { it.providerId })

        assertEquals(listOf(held[0].messageId), scan(full.state, now = NOW + 3_000, room = 1).ids())
    }

    // --- _id reuse ---

    @Test
    fun idReuse_equalId_withNewDate_fallsBackToDateWindow() {
        val state = seeded()
        val first = provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 1)
        val second = provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 2)
        val afterFirst = scan(state)
        assertEquals(listOf(first.messageId, second.messageId), afterFirst.ids())

        // The newest row is deleted and its _id is reused by the next message.
        provider.remove(second.id)
        val reused = provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 60_000)
        assertEquals(second.id, reused.id)

        val result = scan(afterFirst.state)
        assertEquals(listOf(reused.messageId), result.ids())
        assertEquals(SmsWatermark(reused.id, reused.date), result.state.inbound)
        assertTrue(scan(result.state).reports.isEmpty())
    }

    @Test
    fun idReuse_lowerId_afterWatermarkRowIsGone() {
        val state = seeded()
        val a = provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 1)
        val b = provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 2)
        val c = provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 3)
        val reported = scan(state)
        assertEquals(3, reported.reports.size)

        provider.remove(b.id)
        provider.remove(c.id)
        val reused = provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 10)
        assertEquals(b.id, reused.id)

        val result = scan(reported.state)
        // a is inside the 5 min window but already reported.
        assertEquals(listOf(reused.messageId), result.ids())
        assertTrue(a.messageId !in result.ids())
        assertEquals(SmsWatermark(reused.id, reused.date), result.state.inbound)
    }

    @Test
    fun watermarkRowGone_withoutNewRows_keepsWatermark() {
        val state = seeded()
        val a = provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 1)
        val reported = scan(state)
        provider.remove(a.id)

        val result = scan(reported.state)

        assertTrue(result.reports.isEmpty())
        assertEquals(reported.state.inbound, result.state.inbound)
    }

    @Test
    fun watermarkRowGone_fullRecoveryPage_continuesInDateOrder() {
        val gone = provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 1)
        val state = seeded()
        // Dated after the rows below but with a lower _id.
        val late = provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 1_000)
        val early = (1..SmsReportScanner.MAX_ROWS_PER_SCAN).map {
            provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 1 + it)
        }
        provider.remove(gone.id)

        val first = scan(state)
        assertEquals(early.map { it.messageId }, first.ids())
        assertTrue(first.more)

        val second = scan(first.state)
        assertEquals(listOf(late.messageId), second.ids())
        assertFalse(second.more)
        assertNull(second.state.inboundRecovery)
        assertEquals(SmsWatermark(early.last().id, early.last().date), second.state.inbound)

        val next = provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 2_000)
        assertEquals(listOf(next.messageId), scan(second.state).ids())
    }

    @Test
    fun watermarkRowGone_rowsSharingOneDate_arePagedOnce() {
        val gone = provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 1)
        val state = seeded()
        val same = (1..120).map { provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 5) }
        provider.remove(gone.id)

        var result = scan(state)
        val reported = result.ids().toMutableList()
        var scans = 1
        while (result.more) {
            result = scan(result.state)
            reported += result.ids()
            scans += 1
            assertTrue(scans <= 3)
        }

        assertEquals(same.map { it.messageId }, reported)
        assertEquals(SmsWatermark(same.last().id, same.last().date), result.state.inbound)
        assertNull(result.state.inboundRecovery)
    }

    @Test
    fun watermarkRowGone_rowsArrivingDuringRecovery_doNotExtendIt() {
        val gone = provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 1)
        val state = seeded()
        val backlog = (1..SmsReportScanner.MAX_ROWS_PER_SCAN + 10).map {
            provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 1 + it)
        }
        provider.remove(gone.id)

        val first = scan(state)
        assertTrue(first.more)

        // A full page of newer rows lands before the next scan.
        val arriving = (1..SmsReportScanner.MAX_ROWS_PER_SCAN).map {
            provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 10_000 + it)
        }
        val second = scan(first.state)
        assertNull(second.state.inboundRecovery)
        assertEquals(SmsWatermark(backlog.last().id, backlog.last().date), second.state.inbound)
        assertEquals(backlog.map { it.messageId }, first.ids() + second.ids())

        assertEquals(arriving.map { it.messageId }, scan(second.state).ids())
    }

    @Test
    fun watermarkRowGone_rowAddedDuringRecovery_datedBeforeTheCursor_isReported() {
        val gone = provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 1)
        val state = seeded()
        val rows = (1..SmsReportScanner.MAX_ROWS_PER_SCAN + 10).map {
            provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 1 + it)
        }
        provider.remove(gone.id)

        val first = scan(state)
        assertTrue(first.more)

        // Between pages: a row dated before the cursor, then a newer one.
        val delayed = provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 10)
        val newer = provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 1_000)
        val second = scan(first.state)
        assertFalse(second.more)
        assertNull(second.state.inboundRecovery)
        // The re-read ends at the newest row when it started, not past the delayed row.
        assertEquals(SmsWatermark(rows.last().id, rows.last().date), second.state.inbound)

        // Both rows added during the re-read are left to the _id scan.
        val third = scan(second.state)
        assertEquals(listOf(delayed.messageId, newer.messageId), third.ids())
        assertEquals(SmsWatermark(newer.id, newer.date), third.state.inbound)
        assertEquals(
            (rows + delayed + newer).map { it.messageId },
            first.ids() + second.ids() + third.ids(),
        )
    }

    @Test
    fun watermarkRowGone_datedAheadOfNow_rowsDatedNowAreReported() {
        val state = seeded()
        // Reported while the clock ran 2 h ahead.
        val ahead = provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 2 * 60 * 60_000)
        val reported = scan(state)
        assertEquals(listOf(ahead.messageId), reported.ids())

        // The clock is corrected and the owner deletes the thread; new SMS reuse its _id.
        provider.remove(ahead.id)
        val reused = provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 1_000)
        val next = provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 2_000)
        assertEquals(ahead.id, reused.id)

        val result = scan(reported.state, now = NOW + 3_000)
        assertEquals(listOf(reused.messageId, next.messageId), result.ids())
        assertEquals(SmsWatermark(next.id, next.date), result.state.inbound)

        val later = provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 4_000)
        assertEquals(listOf(later.messageId), scan(result.state, now = NOW + 5_000).ids())
    }

    @Test
    fun clockSetBackBeforeCoveredFrom_newRowsAreStillReported() {
        // Turned on while the clock ran 2 h fast; the clock is then corrected.
        val state = seeded(coveredFrom = NOW)
        val back = NOW - 2 * HOUR
        val inbox = provider.add(SmsProviderRow.TYPE_INBOX, date = back + 1_000)
        val sent = provider.add(SmsProviderRow.TYPE_SENT, date = back + 2_000)
        val outbox = provider.add(SmsProviderRow.TYPE_OUTBOX, date = back + 3_000)

        val first = scan(state, now = back + 4_000)
        assertEquals(listOf(inbox.messageId, sent.messageId), first.ids())
        assertEquals(listOf(outbox.id), first.state.recheck.map { it.providerId })

        // A later scan, long after the row arrived, still counts from the first one.
        val later = provider.add(SmsProviderRow.TYPE_INBOX, date = back + 5_000)
        provider.update(outbox.id) { it.copy(type = SmsProviderRow.TYPE_SENT) }
        val second = scan(first.state, now = back + HOUR)
        assertEquals(listOf(later.messageId, outbox.messageId), second.ids())
    }

    @Test
    fun clockSetBack_dateReReadStillSkipsRowsFromBeforeEnabling() {
        // Received in the hour before enabling, while the clock ran 2 h fast.
        val before = provider.add(SmsProviderRow.TYPE_INBOX, date = NOW - HOUR)
        val state = seeded(coveredFrom = NOW)
        val back = NOW - 2 * HOUR
        val arrived = provider.add(SmsProviderRow.TYPE_INBOX, date = back + 1_000)
        val first = scan(state, now = back + 2_000)
        assertEquals(listOf(arrived.messageId), first.ids())

        // The owner deletes the newest row; the next SMS reuses its _id, so the date re-read also
        // reaches the row from before enabling.
        provider.remove(arrived.id)
        val next = provider.add(SmsProviderRow.TYPE_INBOX, date = back + 3_000)
        assertEquals(arrived.id, next.id)
        assertEquals(listOf(next.messageId), scan(first.state, now = back + 4_000).ids())
        assertTrue(before.messageId !in ring)
    }

    // --- out: the owner's own sends from other apps ---

    @Test
    fun out_skipsPortalCreatedRowsAndOwnSendIds() {
        val state = seeded()
        provider.add(SmsProviderRow.TYPE_SENT, date = NOW + 1, creator = PORTAL)
        val ownId = provider.add(SmsProviderRow.TYPE_SENT, date = NOW + 2, creator = null)
        own[ownId.id] = NOW + 2
        val owner = provider.add(SmsProviderRow.TYPE_SENT, date = NOW + 3)

        val result = scan(state)

        assertEquals(listOf(owner.messageId), result.ids())
        assertEquals("out", result.reports.single().direction)
    }

    @Test
    fun out_outboxRowIsRecheckedUntilSent() {
        val state = seeded()
        val outbox = provider.add(SmsProviderRow.TYPE_OUTBOX, date = NOW + 1)

        val first = scan(state, now = NOW + 10)
        assertTrue(first.reports.isEmpty())
        assertEquals(listOf(SmsRecheck(outbox.id, NOW + 10)), first.state.recheck)

        val still = scan(first.state, now = NOW + 20)
        assertTrue(still.reports.isEmpty())
        assertEquals(first.state.recheck, still.state.recheck)

        provider.update(outbox.id) { it.copy(type = SmsProviderRow.TYPE_SENT) }
        val sent = scan(still.state, now = NOW + 30)
        assertEquals(listOf(outbox.messageId), sent.ids())
        assertTrue(sent.state.recheck.isEmpty())
    }

    @Test
    fun out_recheckDropsGoneRows() {
        val state = seeded()
        val failed = provider.add(SmsProviderRow.TYPE_QUEUED, date = NOW + 1)
        val gone = provider.add(SmsProviderRow.TYPE_OUTBOX, date = NOW + 2)
        val stuck = provider.add(SmsProviderRow.TYPE_OUTBOX, date = NOW + 3)
        val first = scan(state)
        assertEquals(3, first.state.recheck.size)

        provider.update(failed.id) { it.copy(type = SmsProviderRow.TYPE_FAILED) }
        provider.remove(gone.id)
        val second = scan(first.state, now = NOW + 1_000)
        assertEquals(listOf(failed.id, stuck.id), second.state.recheck.map { it.providerId })
        assertTrue(second.evictedRechecks.isEmpty())
    }

    @Test
    fun out_rowStuckInTheOutboxForDays_isReportedOnceSent() {
        val state = seeded()
        val stuck = provider.add(SmsProviderRow.TYPE_OUTBOX, date = NOW + 1)
        val first = scan(state)

        val days = scan(first.state, now = NOW + 3 * DAY)
        assertEquals(listOf(stuck.id), days.state.recheck.map { it.providerId })

        provider.update(stuck.id) { it.copy(type = SmsProviderRow.TYPE_SENT) }
        val sent = scan(days.state, now = NOW + 4 * DAY)
        assertEquals(listOf(stuck.messageId), sent.ids())
        assertTrue(sent.state.recheck.isEmpty())
    }

    @Test
    fun out_recheckOverCapacity_evictsTheOldestSeen() {
        val held = (0 until SmsReportScanner.MAX_RECHECKS).map { provider.add(SmsProviderRow.TYPE_OUTBOX, date = NOW + it) }
        val state = seeded().copy(recheck = held.mapIndexed { i, row -> SmsRecheck(row.id, NOW + i) })
        val newest = provider.add(SmsProviderRow.TYPE_OUTBOX, date = NOW + 1_000)

        val result = scan(state, now = NOW + 2_000)

        assertEquals(listOf(SmsRecheck(held.first().id, NOW)), result.evictedRechecks)
        assertEquals(held.drop(1).map { it.id } + newest.id, result.state.recheck.map { it.providerId })
    }

    @Test
    fun out_recheckList_isReadInOneQuery() {
        val held = (0 until SmsReportScanner.MAX_RECHECKS).map { provider.add(SmsProviderRow.TYPE_FAILED, date = NOW + it) }
        val state = seeded().copy(recheck = held.map { SmsRecheck(it.id, NOW) })
        val reads = provider.queries + provider.rowReads

        val result = scan(state, now = NOW + 1_000)

        assertEquals(state.recheck, result.state.recheck)
        // Watermark checks, the two _id pages and the recheck list.
        assertEquals(reads + 5, provider.queries + provider.rowReads)
    }

    @Test
    fun out_failedRowRetriedOnTheSameRowIsReportedOnceSent() {
        val state = seeded()
        val outbox = provider.add(SmsProviderRow.TYPE_OUTBOX, date = NOW + 1)
        val failedFirst = provider.add(SmsProviderRow.TYPE_FAILED, date = NOW + 2)
        val later = provider.add(SmsProviderRow.TYPE_SENT, date = NOW + 3)
        val first = scan(state)
        assertEquals(listOf(later.messageId), first.ids())
        assertEquals(listOf(outbox.id, failedFirst.id), first.state.recheck.map { it.providerId })

        provider.update(outbox.id) { it.copy(type = SmsProviderRow.TYPE_FAILED) }
        val failed = scan(first.state, now = NOW + 1_000)
        assertTrue(failed.reports.isEmpty())
        assertEquals(listOf(outbox.id, failedFirst.id), failed.state.recheck.map { it.providerId })

        provider.update(outbox.id) { it.copy(type = SmsProviderRow.TYPE_SENT) }
        provider.update(failedFirst.id) { it.copy(type = SmsProviderRow.TYPE_SENT) }
        val sent = scan(failed.state, now = NOW + 2_000)
        assertEquals(listOf(outbox.messageId, failedFirst.messageId), sent.ids())
        assertTrue(sent.state.recheck.isEmpty())
    }

    @Test
    fun ownSendIdReusedByANewerRow_isNotSkipped() {
        val state = seeded()
        val portalSend = provider.add(SmsProviderRow.TYPE_SENT, date = NOW + 1, creator = null)
        own[portalSend.id] = NOW + 2
        val first = scan(state)
        assertTrue(first.reports.isEmpty())

        // The owner deletes the Portal's newest row; the next rows reuse its _id.
        provider.remove(portalSend.id)
        val inbound = provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 10 * 60_000)
        assertEquals(portalSend.id, inbound.id)
        assertEquals(listOf(inbound.messageId), scan(first.state).ids())

        provider.remove(inbound.id)
        val ownerSent = provider.add(SmsProviderRow.TYPE_SENT, date = NOW + 20 * 60_000)
        assertEquals(portalSend.id, ownerSent.id)
        assertEquals(listOf(ownerSent.messageId), scan(first.state).ids())
    }

    @Test
    fun ownSendIdReusedByAnOlderRow_isNotSkipped() {
        val state = seeded()
        val portalSend = provider.add(SmsProviderRow.TYPE_SENT, date = NOW + 60_000, creator = null)
        own[portalSend.id] = NOW + 61_000
        ownDates[portalSend.id] = portalSend.date
        val first = scan(state)
        assertTrue(first.reports.isEmpty())

        // Deleted; an imported row dated before it, or one added after the clock was set back, reuses its _id.
        provider.remove(portalSend.id)
        val ownerSent = provider.add(SmsProviderRow.TYPE_SENT, date = NOW + 1_000)
        assertEquals(portalSend.id, ownerSent.id)
        assertEquals(listOf(ownerSent.messageId), scan(first.state).ids())
    }

    @Test
    fun out_portalOutboxRowsAreNeverRechecked() {
        val state = seeded()
        provider.add(SmsProviderRow.TYPE_OUTBOX, date = NOW + 1, creator = PORTAL)

        assertTrue(scan(state).state.recheck.isEmpty())
    }

    // --- multi-SIM ---

    @Test
    fun multiSim_onlyTheCloudSimRowsAreReported() {
        val state = seeded()
        provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 1, subscriptionId = 2)
        val cloud = provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 2, subscriptionId = 1)
        provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 3, subscriptionId = null)
        provider.add(SmsProviderRow.TYPE_SENT, date = NOW + 4, subscriptionId = 2)
        val cloudSent = provider.add(SmsProviderRow.TYPE_SENT, date = NOW + 5, subscriptionId = 1)

        val result = scan(state, filter = 1)

        assertEquals(listOf(cloud.messageId, cloudSent.messageId), result.ids())
    }

    @Test
    fun reporterSims_fromSubscriptions() {
        val a = SmsSimInfo(subscriptionId = 1, simSlot = 0, phoneNumber = "+15550001111")
        val b = SmsSimInfo(subscriptionId = 2, simSlot = 1)

        assertEquals(
            SmsReporterSims.Covered(1, mapOf(1 to "+15550001111")),
            SmsReporterSims.from(SmsSubscriptionsResult.Available(listOf(a)), cloudSubscriptionId = null),
        )
        assertEquals(
            SmsReporterSims.Covered(2, emptyMap()),
            SmsReporterSims.from(SmsSubscriptionsResult.Available(listOf(a, b)), cloudSubscriptionId = 2),
        )
        assertEquals(
            SmsReporterSims.Uncovered,
            SmsReporterSims.from(SmsSubscriptionsResult.Available(listOf(a, b)), cloudSubscriptionId = null),
        )
        assertEquals(
            SmsReporterSims.Uncovered,
            SmsReporterSims.from(SmsSubscriptionsResult.Available(listOf(a, b)), cloudSubscriptionId = 9),
        )
        assertEquals(SmsReporterSims.Blocked, SmsReporterSims.from(SmsSubscriptionsResult.PermissionDenied, 1))
        assertEquals(SmsReporterSims.Blocked, SmsReporterSims.from(SmsSubscriptionsResult.Available(emptyList()), null))
    }

    @Test
    fun reporterSims_theOnlyActiveSimFiltersWithoutAChosenCloudSim() {
        val state = seeded()
        // A removed SIM's row, then the only active SIM's.
        provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 1, subscriptionId = 2)
        val covered = provider.add(SmsProviderRow.TYPE_INBOX, date = NOW + 2, subscriptionId = 1)
        val sims = SmsReporterSims.from(
            SmsSubscriptionsResult.Available(listOf(SmsSimInfo(subscriptionId = 1, simSlot = 0))),
            cloudSubscriptionId = null,
        ) as SmsReporterSims.Covered

        assertEquals(listOf(covered.messageId), scan(state, filter = sims.subscriptionFilter).ids())
    }

    @Test
    fun reporterSims_aChosenCloudSimFiltersWhateverTheSimCount() {
        val a = SmsSimInfo(subscriptionId = 1, simSlot = 0, phoneNumber = "+15550001111")
        val b = SmsSimInfo(subscriptionId = 2, simSlot = 1)

        assertEquals(
            SmsReporterSims.Covered(1, mapOf(1 to "+15550001111")),
            SmsReporterSims.from(SmsSubscriptionsResult.Available(listOf(a)), cloudSubscriptionId = 1),
        )
        // Cloud SIM 1 disabled or removed: only the other SIM is active.
        assertEquals(SmsReporterSims.Uncovered, SmsReporterSims.from(SmsSubscriptionsResult.Available(listOf(b)), cloudSubscriptionId = 1))
        // Right after boot, before the SIMs load.
        assertEquals(SmsReporterSims.Blocked, SmsReporterSims.from(SmsSubscriptionsResult.Available(emptyList()), cloudSubscriptionId = 1))
    }

    @Test
    fun recheckCodec_roundTripsAndSkipsGarbage() {
        val rechecks = listOf(SmsRecheck(4, 100), SmsRecheck(9, 200))

        assertEquals(rechecks, SmsRecheckCodec.decode(SmsRecheckCodec.encode(rechecks)))
        assertEquals(listOf(SmsRecheck(4, 100)), SmsRecheckCodec.decode("4:100,x:1,5,"))
        assertTrue(SmsRecheckCodec.decode(null).isEmpty())
    }

    @Test
    fun recoveryCodec_roundTripsAndRejectsGarbage() {
        val recovery = SmsRecovery(date = 1_000, id = 7, top = SmsWatermark(9, 900))

        assertEquals(recovery, SmsRecoveryCodec.decode(SmsRecoveryCodec.encode(recovery)))
        assertEquals("", SmsRecoveryCodec.encode(null))
        assertNull(SmsRecoveryCodec.decode(""))
        assertNull(SmsRecoveryCodec.decode("1:2:3"))
        assertNull(SmsRecoveryCodec.decode("1:x:3:4"))
    }

    companion object {
        private const val PORTAL = "com.mobilerun.portal"
        private const val NOW = 1_700_000_000_000L
        private const val HOUR = 60L * 60L * 1000L
        private const val DAY = 24 * HOUR
    }
}
