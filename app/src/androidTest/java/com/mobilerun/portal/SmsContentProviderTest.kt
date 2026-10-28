package com.mobilerun.portal

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmsContentProviderTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @Test
    fun smsCapabilities_queryReturnsPermissionAndTelephonyFields() {
        val response = queryJson("content://com.mobilerun.portal/sms/capabilities")

        assertEquals("success", response.getString("status"))
        val result = response.getJSONObject("result")
        assertTrue(result.has("telephony_available"))
        assertTrue(result.has("send_sms_granted"))
        assertTrue(result.has("read_sms_granted"))
        assertTrue(result.has("receive_sms_granted"))
        assertTrue(result.has("read_phone_state_granted"))
        assertTrue(result.has("active_subscriptions"))
    }

    @Test
    fun smsMessages_queryReturnsSuccessOrReadPermissionError() {
        val response = queryJson("content://com.mobilerun.portal/sms/messages?box=inbox&limit=1")

        val status = response.getString("status")
        assertTrue(status == "success" || status == "error")
        if (status == "success") {
            assertTrue(response.getJSONObject("result").has("messages"))
        } else {
            assertTrue(response.getString("error").contains("READ_SMS"))
        }
    }

    @Test
    fun smsSend_insertReturnsStructuredResultUri() {
        val result = insert(
            "content://com.mobilerun.portal/sms/send",
            ContentValues().apply {
                put("phone_number", "")
                put("message", "")
            },
        )

        assertTrue(result.getQueryParameter("status") == "error" || result.getQueryParameter("status") == "success")
        assertTrue(result.getQueryParameter("message") != null)
    }

    private fun queryJson(rawUri: String): JSONObject {
        val cursor = context.contentResolver.query(Uri.parse(rawUri), null, null, null, null)
        cursor.use {
            requireNotNull(it)
            check(it.moveToFirst())
            return JSONObject(it.getString(it.getColumnIndexOrThrow("result")))
        }
    }

    private fun insert(rawUri: String, values: ContentValues): Uri {
        return requireNotNull(context.contentResolver.insert(Uri.parse(rawUri), values))
    }
}
