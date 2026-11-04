package com.mobilerun.portal.sms

import android.database.sqlite.SQLiteDatabase
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.Assert.assertThrows
import org.junit.Test

class SmsStoreTest {
    @Test
    fun sendCallbackWrite_neverGivesUp() {
        val store = mockk<SmsStore>()
        every { store.applyCallbackAsync(any(), any()) } answers { callOriginal() }
        every { store.async(any(), any(), any(), any(), any()) } just runs

        store.applyCallbackAsync(
            SmsSendCallback(sent = true, recordId = "r", partIndex = 0, callbackToken = "t", resultOk = true),
        ) {}

        // Nothing else resolves a dispatched record.
        verify { store.async("callback", any(), retried = true, untilDone = true, block = any()) }
    }

    @Test
    fun failedDispatchedWrite_isRetriedUntilItLands() {
        val store = mockk<SmsStore>()
        every { store.markDispatched(any(), any()) } answers { callOriginal() }
        every { store["call"](any<(SQLiteDatabase) -> Any?>()) } throws IllegalStateException("store unavailable")
        every { store.async(any(), any(), any(), any(), any()) } just runs

        assertThrows(IllegalStateException::class.java) { store.markDispatched("r", 1_000L) }

        // SmsManager took it: left prepared, a restart would fail it as interrupted.
        verify { store.async("dispatch", any(), retried = true, untilDone = true, block = any()) }
    }
}
