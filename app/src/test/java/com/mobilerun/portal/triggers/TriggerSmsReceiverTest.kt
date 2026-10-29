package com.mobilerun.portal.triggers

import android.telephony.SubscriptionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TriggerSmsReceiverTest {
    @Test
    fun smsSubscriptionIdFromIntent_readsKnownSubscriptionExtras() {
        assertEquals(
            12,
            smsSubscriptionIdFromExtras(mapOf(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX to 12)),
        )
        assertEquals(13, smsSubscriptionIdFromExtras(mapOf("subscription" to "13")))
        assertEquals(14, smsSubscriptionIdFromExtras(mapOf("sub_id" to 14L)))
    }

    @Test
    fun smsSubscriptionIdFromIntent_ignoresPhoneIdExtra() {
        assertNull(smsSubscriptionIdFromExtras(mapOf("phone" to 1)))
    }

    @Test
    fun smsSimSlotFromIntent_readsKnownSlotExtras() {
        assertEquals(0, smsSimSlotFromExtras(mapOf(SubscriptionManager.EXTRA_SLOT_INDEX to 0)))
        assertEquals(1, smsSimSlotFromExtras(mapOf("slot" to "1")))
        assertEquals(2, smsSimSlotFromExtras(mapOf("sim_slot" to 2L)))
    }

    @Test
    fun smsExtraParsing_ignoresInvalidValues() {
        assertNull(smsSubscriptionIdFromExtras(mapOf("subscription" to "abc")))
        assertNull(smsSubscriptionIdFromExtras(mapOf("subscription" to -1)))
        assertNull(smsSimSlotFromExtras(mapOf("slot" to 1.5)))
    }
}
