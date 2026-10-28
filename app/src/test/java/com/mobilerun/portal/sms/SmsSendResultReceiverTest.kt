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
}
