package com.mobilerun.portal.sms

import com.mobilerun.portal.events.model.EventType
import com.mobilerun.portal.events.model.PortalEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SmsEventContractTest {
    @Test
    fun smsSendStateChanged_serializesAsStableEventType() {
        val event = PortalEvent(
            type = EventType.SMS_SEND_STATE_CHANGED,
            timestamp = 123L,
            payload = JSONObject()
                .put("message_id", "msg-1")
                .put("phone_number", "+15550001111")
                .put("state", "SENT"),
        )

        val json = event.toJsonObject()

        assertEquals("SMS_SEND_STATE_CHANGED", json.getString("type"))
        assertEquals("msg-1", json.getJSONObject("payload").getString("message_id"))
        assertEquals("SENT", json.getJSONObject("payload").getString("state"))
    }
}
