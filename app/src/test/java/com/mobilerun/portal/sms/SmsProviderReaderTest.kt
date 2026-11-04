package com.mobilerun.portal.sms

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsProviderReaderTest {
    @Test
    fun unavailableProvider_failsTheReadInsteadOfReturningNoRows() {
        val context = mockk<Context>()
        val resolver = mockk<ContentResolver>()
        every { context.contentResolver } returns resolver
        every { resolver.query(any(), any(), any(), any(), any()) } returns null
        val source = AndroidSmsProviderSource(context)

        assertThrows(IllegalStateException::class.java) { source.row(7L) }
        assertThrows(IllegalStateException::class.java) { source.latest() }
        assertThrows(IllegalStateException::class.java) { source.ownSends("com.mobilerun.portal", 0L, null, 10) }
    }

    @Test
    fun rowsById_readsEveryIdInOneQuery() {
        val context = mockk<Context>()
        val resolver = mockk<ContentResolver>()
        val selection = slot<String>()
        every { context.contentResolver } returns resolver
        every { resolver.query(any(), any(), capture(selection), any(), any()) } returns mockk<Cursor>(relaxed = true)
        val source = AndroidSmsProviderSource(context)

        assertTrue(source.rows(emptyList()).isEmpty())
        verify(exactly = 0) { resolver.query(any(), any(), any<String>(), any(), any()) }

        source.rows(listOf(3L, 5L, 9L))
        assertEquals("_id IN (3,5,9)", selection.captured)
        verify(exactly = 1) { resolver.query(any(), any(), any<String>(), any(), any()) }
    }
}
