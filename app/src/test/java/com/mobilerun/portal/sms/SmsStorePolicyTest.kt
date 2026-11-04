package com.mobilerun.portal.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsStorePolicyTest {
    private val day = 24L * 60L * 60L * 1000L
    private val pkg = "com.mobilerun.portal"

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
        assertEquals(
            listOf("expired", "old-terminal", "new-terminal", "old-pending"),
            SmsStorePolicy.evictions(records, now, maxRecords = 1),
        )
    }

    @Test
    fun recovery_ignoresDispatchedAndCurrentProcessRecords() {
        val record = SmsSendRecordTest.record(parts = 1, createdAt = 1_000)
        assertEquals(
            SmsRecoveryDecision.None,
            SmsSendRecovery.decide(record.copy(phase = SmsSendPhase.DISPATCHED), 1_000_000, 500_000, emptyList(), SmsOwnSends(), pkg),
        )
        assertEquals(SmsRecoveryDecision.None, SmsSendRecovery.decide(record, 1_000_000, 500, emptyList(), SmsOwnSends(), pkg))
    }

    @Test
    fun recovery_waitsAMinuteBeforeCheckingTheProvider() {
        val record = SmsSendRecordTest.record(parts = 1, createdAt = 1_000)
        assertEquals(
            SmsRecoveryDecision.Wait(61_000),
            SmsSendRecovery.decide(record, 30_000, 20_000, emptyList(), SmsOwnSends(), pkg),
        )
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

        val decision = SmsSendRecovery.decide(record, 100_000, 50_000, rows, SmsOwnSends(), pkg) as SmsRecoveryDecision.Resolve

        assertEquals(9L, decision.providerId)
        assertEquals(SmsSendState.SENT, decision.record.state)
        assertEquals(setOf(0, 1), decision.record.sentParts)
        assertEquals(SmsSendPhase.TERMINAL, decision.record.phase)
    }

    @Test
    fun recovery_skipsRowsAlreadyClaimedAndFailsWithoutEvidence() {
        val record = SmsSendRecordTest.record(parts = 1, createdAt = 1_000)
        val rows = listOf(row(id = 9, type = SmsProviderRow.TYPE_SENT, date = 2_000))

        val decision = SmsSendRecovery.decide(record, 100_000, 50_000, rows, SmsOwnSends(mapOf(9L to 2_000L)), pkg)
            as SmsRecoveryDecision.Resolve

        assertEquals(null, decision.providerId)
        assertEquals(SmsSendState.FAILED, decision.record.state)
        assertEquals("RESULT_INTERRUPTED", decision.record.errorCode)
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
    fun recovery_outboxRowMeansDispatchedAndFailedRowMeansFailed() {
        val record = SmsSendRecordTest.record(parts = 1, createdAt = 1_000)
        val outbox = SmsSendRecovery.decide(
            record, 100_000, 50_000, listOf(row(id = 3, type = SmsProviderRow.TYPE_QUEUED, date = 2_000)), SmsOwnSends(), pkg,
        ) as SmsRecoveryDecision.Resolve
        assertEquals(SmsSendPhase.DISPATCHED, outbox.record.phase)
        assertEquals(SmsSendState.PENDING, outbox.record.state)
        assertEquals(3L, outbox.providerId)

        val failed = SmsSendRecovery.decide(
            record, 100_000, 50_000, listOf(row(id = 4, type = SmsProviderRow.TYPE_FAILED, date = 2_000)), SmsOwnSends(), pkg,
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
        val decision = SmsSendRecovery.decide(newer, 100_000, 50_000, rows, SmsOwnSends(), pkg) as SmsRecoveryDecision.Resolve
        assertEquals(match.id, decision.providerId)
        assertEquals(SmsSendState.SENT, decision.record.state)
    }

    private fun row(
        id: Long,
        type: Int,
        date: Long,
        address: String = "+15550001111",
        body: String = "hello",
        creator: String = pkg,
    ) = SmsProviderRow(id = id, type = type, address = address, body = body, date = date, creator = creator)
}
