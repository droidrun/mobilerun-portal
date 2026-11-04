package com.mobilerun.portal.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SmsReportScannerTest {
    private lateinit var provider: FakeSmsProvider
    private lateinit var scanner: SmsReportScanner
    private val own = mutableMapOf<Long, Long>()
    private val ring = mutableSetOf<String>()

    @Before
    fun setUp() {
        provider = FakeSmsProvider()
        scanner = SmsReportScanner(provider, PORTAL)
    }

    private fun seeded(coveredFrom: Long = NOW): SmsScanState {
        val latest = provider.latest()
        val mark = SmsWatermark(latest?.id ?: 0L, latest?.date ?: 0L)
        return SmsScanState(mark, mark, coveredFrom)
    }

    private fun scan(state: SmsScanState, filter: Int? = null, now: Long = NOW): SmsScanResult =
        scanner.scan(state, SmsOwnSends(own.toMap()), { it in ring }, filter, now).also { result ->
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
    fun out_recheckDropsGoneAndExpiredRows() {
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

        val expired = scan(second.state, now = NOW + SmsReportScanner.RECHECK_TTL_MS)
        assertTrue(expired.state.recheck.isEmpty())
        assertTrue(expired.reports.isEmpty())
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
            SmsReporterSims.Covered(null, mapOf(1 to "+15550001111")),
            SmsReporterSims.from(SmsSubscriptionsResult.Available(listOf(a)), cloudSubscriptionId = null),
        )
        assertEquals(
            SmsReporterSims.Covered(2, emptyMap()),
            SmsReporterSims.from(SmsSubscriptionsResult.Available(listOf(a, b)), cloudSubscriptionId = 2),
        )
        assertEquals(
            SmsReporterSims.Blocked,
            SmsReporterSims.from(SmsSubscriptionsResult.Available(listOf(a, b)), cloudSubscriptionId = null),
        )
        assertEquals(
            SmsReporterSims.Blocked,
            SmsReporterSims.from(SmsSubscriptionsResult.Available(listOf(a, b)), cloudSubscriptionId = 9),
        )
        assertEquals(SmsReporterSims.Blocked, SmsReporterSims.from(SmsSubscriptionsResult.PermissionDenied, 1))
        assertEquals(SmsReporterSims.Blocked, SmsReporterSims.from(SmsSubscriptionsResult.Available(emptyList()), null))
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
        assertEquals(SmsReporterSims.Blocked, SmsReporterSims.from(SmsSubscriptionsResult.Available(listOf(b)), cloudSubscriptionId = 1))
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

    companion object {
        private const val PORTAL = "com.mobilerun.portal"
        private const val NOW = 1_700_000_000_000L
    }
}
