package com.mobilerun.portal.sms

import android.content.ContentResolver
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertThrows
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
}
