package com.mobilerun.portal

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilerun.portal.sms.SmsController.Companion.GATEWAY_DISABLED
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmsContentProviderTest {
    private lateinit var context: Context
    private var initialGatewayEnabled = false

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        initialGatewayEnabled = queryJson("content://com.mobilerun.portal/sms/capabilities")
            .getJSONObject("result")
            .getBoolean("gateway_enabled")
    }

    @After
    fun tearDown() {
        setGatewayEnabled(initialGatewayEnabled)
    }

    @Test
    fun smsCapabilities_queryReturnsPermissionAndTelephonyFields() {
        setGatewayEnabled(false)

        val response = queryJson("content://com.mobilerun.portal/sms/capabilities")

        assertEquals("success", response.getString("status"))
        val result = response.getJSONObject("result")
        assertFalse(result.getBoolean("gateway_enabled"))
        assertTrue(result.has("telephony_available"))
        assertTrue(result.has("send_sms_granted"))
        assertTrue(result.has("read_sms_granted"))
        assertTrue(result.has("receive_sms_granted"))
        assertTrue(result.has("read_phone_state_granted"))
        assertTrue(result.has("active_subscriptions"))
    }

    @Test
    fun smsGateway_insertTogglesSwitch() {
        assertEquals("success", setGatewayEnabled(true).getQueryParameter("status"))
        assertTrue(
            queryJson("content://com.mobilerun.portal/sms/capabilities")
                .getJSONObject("result")
                .getBoolean("gateway_enabled"),
        )

        val missing = insert("content://com.mobilerun.portal/sms/gateway", ContentValues())
        assertEquals("error", missing.getQueryParameter("status"))
        assertEquals("Missing required field: enabled", missing.getQueryParameter("message"))
    }

    @Test
    fun smsMethods_returnDisabledErrorWhileGatewayIsOff() {
        setGatewayEnabled(false)

        listOf(
            "content://com.mobilerun.portal/sms/status?message_id=missing",
            "content://com.mobilerun.portal/sms/messages?box=inbox&limit=1",
            "content://com.mobilerun.portal/sms/messages/1",
        ).forEach { uri ->
            val response = queryJson(uri)
            assertEquals(uri, "error", response.getString("status"))
            assertEquals(uri, GATEWAY_DISABLED, response.getString("error"))
        }

        val send = insert(
            "content://com.mobilerun.portal/sms/send",
            ContentValues().apply {
                put("phone_number", "+15550001111")
                put("message", "hello")
            },
        )
        assertEquals("error", send.getQueryParameter("status"))
        assertEquals(GATEWAY_DISABLED, send.getQueryParameter("message"))
    }

    @Test
    fun smsMessages_queryReturnsSuccessOrReadPermissionError() {
        setGatewayEnabled(true)

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
        setGatewayEnabled(true)

        val result = insert(
            "content://com.mobilerun.portal/sms/send",
            ContentValues().apply {
                put("phone_number", "")
                put("message", "")
            },
        )

        assertEquals("error", result.getQueryParameter("status"))
        val message = requireNotNull(result.getQueryParameter("message"))
        assertTrue(message, message != GATEWAY_DISABLED)
    }

    private fun setGatewayEnabled(enabled: Boolean): Uri =
        insert(
            "content://com.mobilerun.portal/sms/gateway",
            ContentValues().apply { put("enabled", enabled) },
        )

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
