package com.mobilerun.portal.sms

import org.junit.Assert.assertEquals
import org.junit.Test

class SmsSendResultReceiverTest {
    @Test
    fun smsDeliveryOutcome_treatsMissingOrSuccessStatusAsDelivered() {
        assertEquals(SmsDeliveryOutcome.DELIVERED, smsDeliveryOutcome(null))
        assertEquals(SmsDeliveryOutcome.DELIVERED, smsDeliveryOutcome(0x00))
        assertEquals(SmsDeliveryOutcome.DELIVERED, smsDeliveryOutcome(0x1f))
    }

    @Test
    fun smsDeliveryOutcome_treatsTemporaryStatusAsNoTerminalChange() {
        assertEquals(SmsDeliveryOutcome.TEMPORARY_FAILURE, smsDeliveryOutcome(0x20))
        assertEquals(SmsDeliveryOutcome.TEMPORARY_FAILURE, smsDeliveryOutcome(0x3f))
    }

    @Test
    fun smsDeliveryOutcome_treatsPermanentStatusAsFailed() {
        assertEquals(SmsDeliveryOutcome.PERMANENT_FAILURE, smsDeliveryOutcome(0x40))
        assertEquals(SmsDeliveryOutcome.PERMANENT_FAILURE, smsDeliveryOutcome(0xff))
    }

    @Test
    fun smsDeliveryStatus_decodes3gpp2StatusReports() {
        // (error class << 8 | status) << 16
        val outcome = { errorClass: Int, status: Int ->
            smsDeliveryOutcome(smsDeliveryStatus(((errorClass shl 8) or status) shl 16, "3gpp2"))
        }
        assertEquals(SmsDeliveryOutcome.DELIVERED, outcome(0, 0x02))
        assertEquals(SmsDeliveryOutcome.TEMPORARY_FAILURE, outcome(0, 0x00))
        assertEquals(SmsDeliveryOutcome.TEMPORARY_FAILURE, outcome(2, 0x04))
        assertEquals(SmsDeliveryOutcome.PERMANENT_FAILURE, outcome(3, 0x07))
        assertEquals(0x40, smsDeliveryStatus(0x40, "3gpp"))
    }
}
