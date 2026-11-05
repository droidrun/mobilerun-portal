package com.mobilerun.portal.sms

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SmsBridgeModelsTest {
    @Test
    fun messageIds_roundTripAndRejectOtherShapes() {
        assertEquals("12-1700000000000", SmsMessageIds.format(12, 1_700_000_000_000))
        assertEquals(12L to 1_700_000_000_000L, SmsMessageIds.parse("12-1700000000000"))
        listOf("", "12", "-1", "a-1", "1-a", "1-2-3", "1 -2", "99999999999999999999-1").forEach {
            assertNull(it, SmsMessageIds.parse(it))
        }
    }

    @Test
    fun cursor_roundTripsAndIsUrlSafe() {
        val cursor = SmsListCursor("desc", 1_700_000_000_123, 42, 99)
        val encoded = cursor.encode()
        assertFalse(encoded.contains('=') || encoded.contains('+') || encoded.contains('/'))
        assertEquals(cursor, SmsListCursor.decode(encoded))
        assertNull(SmsListCursor.decode("%%%"))
        assertNull(SmsListCursor.decode(java.util.Base64.getUrlEncoder().encodeToString("{\"o\":\"desc\"}".toByteArray())))
    }

    @Test
    fun query_buildsKeysetSelectionDesc() {
        val (selection, args) = SmsProviderQuery(
            types = listOf(1, 2),
            ceilingId = 50,
            limit = 11,
            after = SmsListCursor("desc", 1000, 40, 50),
            since = 10,
            until = 2000,
            storedFrom = 5,
            address = "+1555",
            threadId = "3",
            subscriptionId = 2,
        ).selection()

        assertEquals(
            "type IN (1,2) AND _id <= ? AND date > ? AND date <= ? AND date >= ? AND address = ? AND thread_id = ? " +
                "AND sub_id = ? AND (date < ? OR (date = ? AND _id < ?))",
            selection,
        )
        assertArrayEquals(arrayOf("50", "10", "2000", "5", "+1555", "3", "2", "1000", "1000", "40"), args)
    }

    @Test
    fun query_ascendingUsesGreaterThanAndAscSort() {
        val query = SmsProviderQuery(types = listOf(1), ceilingId = 9, limit = 3, ascending = true, after = SmsListCursor("asc", 5, 2, 9))
        assertEquals("type IN (1) AND _id <= ? AND (date > ? OR (date = ? AND _id > ?))", query.selection().first)
        assertEquals("date ASC, _id ASC LIMIT 3", query.sortOrder())
        assertEquals("date DESC, _id DESC LIMIT 3", query.copy(ascending = false).sortOrder())
    }

    @Test
    fun bridgeMessage_omitsUnknownFieldsAndTruncatesAddress() {
        val row = SmsProviderRow(
            id = 3,
            type = SmsProviderRow.TYPE_INBOX,
            address = "é".repeat(200),
            body = null,
            date = 10,
            dateSent = 0,
            subscriptionId = -1,
        )
        val json = row.toBridgeMessage(mapOf(-1 to "+15550001111"))!!
        assertEquals("", json.getString("body"))
        assertFalse(json.getBoolean("bodyAvailable"))
        assertEquals(128, json.getString("address").length)
        listOf("sentAt", "read", "threadId", "subscriptionId", "phoneNumber").forEach { assertFalse(it, json.has(it)) }
        assertNull(row.copy(type = SmsProviderRow.TYPE_OUTBOX).toBridgeMessage(emptyMap()))
    }

    @Test
    fun hashes_fingerprintCoversToAndBodyOnly() {
        assertEquals(SmsHashes.fingerprint("+1", "a"), SmsHashes.fingerprint("+1", "a"))
        assertFalse(SmsHashes.fingerprint("+1", "ab") == SmsHashes.fingerprint("+1a", "b"))
        assertEquals(64, SmsHashes.bodyHash("x").length)
    }
}
