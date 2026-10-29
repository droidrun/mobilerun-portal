package com.mobilerun.portal.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverseConnectionLoggingTest {
    @Test
    fun requestRedaction_coversSensitiveWriteAndSmsPayloads() {
        assertTrue(shouldRedactReverseRequestPayload("clipboard/set"))
        assertTrue(shouldRedactReverseRequestPayload("app/deep-link"))
        assertTrue(shouldRedactReverseRequestPayload("app/deep-link/resolve"))
        assertTrue(shouldRedactReverseRequestPayload("sms/send"))
        assertTrue(shouldRedactReverseRequestPayload("sms/messages/list"))
        assertFalse(shouldRedactReverseRequestPayload("clipboard/get"))
        assertFalse(shouldRedactReverseRequestPayload("app"))
    }

    @Test
    fun responseRedaction_coversSensitiveReadDeepLinkAndSmsPayloads() {
        assertTrue(shouldRedactReverseResponsePayload("clipboard/get"))
        assertTrue(shouldRedactReverseResponsePayload("app/deep-link"))
        assertTrue(shouldRedactReverseResponsePayload("app/deep-link/resolve"))
        assertTrue(shouldRedactReverseResponsePayload("sms/status"))
        assertTrue(shouldRedactReverseResponsePayload("sms/messages/get"))
        assertFalse(shouldRedactReverseResponsePayload("clipboard/set"))
        assertFalse(shouldRedactReverseResponsePayload("app"))
    }
}
