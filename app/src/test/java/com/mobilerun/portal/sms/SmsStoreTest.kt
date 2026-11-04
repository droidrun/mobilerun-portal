package com.mobilerun.portal.sms

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
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
}
