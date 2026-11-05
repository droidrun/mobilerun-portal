package com.mobilerun.portal.config

import android.content.Context
import android.content.SharedPreferences
import com.mobilerun.portal.sms.FakeBridgeSender
import com.mobilerun.portal.sms.FakeSmsProvider
import com.mobilerun.portal.sms.InMemorySendRecordStore
import com.mobilerun.portal.sms.SmsBridgeApi
import com.mobilerun.portal.sms.SmsBridgeResult
import com.mobilerun.portal.sms.SmsSubscriptionsResult
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Modifier

class ConfigManagerSmsCloudTest {
    private lateinit var smsStore: MutableMap<String, Any?>
    private lateinit var sharedStore: MutableMap<String, Any?>
    private lateinit var context: Context

    @Before
    fun setUp() {
        clearSingleton()
        smsStore = mutableMapOf()
        sharedStore = mutableMapOf()
        context = mockk(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), Context.MODE_PRIVATE) } answers {
            when (firstArg<String>()) {
                "mobilerun_sms" -> preferences(smsStore)
                "mobilerun_config" -> preferences(sharedStore)
                else -> preferences(mutableMapOf())
            }
        }
    }

    @After
    fun tearDown() {
        clearSingleton()
    }

    @Test
    fun upgradeWithGatewayOn_leavesCloudSmsOffAndBridgeRefuses() {
        smsStore["sms_gateway_enabled"] = true
        val config = ConfigManager.getInstance(context)

        assertTrue(config.smsGatewayEnabled)
        assertFalse(config.smsCloudEnabled)
        assertNull(config.cloudSubscriptionId)

        val provider = FakeSmsProvider()
        val records = InMemorySendRecordStore()
        val sender = FakeBridgeSender(records.events)
        val api = SmsBridgeApi(
            cloudEnabled = { config.smsCloudEnabled },
            cloudSubscriptionId = { config.cloudSubscriptionId },
            hasTelephony = { true },
            hasReadSms = { true },
            hasSendSms = { true },
            subscriptions = { SmsSubscriptionsResult.Available(emptyList()) },
            provider = provider,
            records = records,
            sender = sender,
        )
        val send = JSONObject().put("id", "a").put("to", "+15550001111").put("body", "hi")
        listOf("sms/list" to JSONObject(), "sms/send" to send, "sms/subscriptions" to JSONObject()).forEach { (method, params) ->
            val result = api.handle(method, params) as SmsBridgeResult.Failure
            assertEquals("sms: permission_denied: cloud_sms_disabled", result.error.wire)
        }
        assertTrue(sender.sends.isEmpty())
        assertEquals(0, provider.queries)
    }

    @Test
    fun cloudSmsPrefsLiveInTheBackupExcludedSmsFileAndNotifyListeners() {
        val config = ConfigManager.getInstance(context)
        val changes = mutableListOf<Boolean>()
        config.addListener(object : ConfigManager.ConfigChangeListener {
            override fun onOverlayVisibilityChanged(visible: Boolean) = Unit
            override fun onOverlayOffsetChanged(offset: Int) = Unit
            override fun onSocketServerEnabledChanged(enabled: Boolean) = Unit
            override fun onSocketServerPortChanged(port: Int) = Unit
            override fun onSmsCloudEnabledChanged(enabled: Boolean) {
                changes += enabled
            }
        })

        config.smsCloudEnabled = true
        config.smsCloudEnabled = true
        config.cloudSubscriptionId = 3

        assertEquals(true, smsStore["sms_cloud_enabled"])
        assertEquals(3, smsStore["sms_cloud_subscription_id"])
        assertEquals(3, config.cloudSubscriptionId)
        assertFalse(sharedStore.containsKey("sms_cloud_enabled"))
        assertEquals(listOf(true), changes)

        config.cloudSubscriptionId = null
        assertNull(config.cloudSubscriptionId)
        assertFalse(smsStore.containsKey("sms_cloud_subscription_id"))
    }

    @Test
    fun resetTurnsCloudSmsOffAndRunsTheSmsResetHook() {
        val config = ConfigManager.getInstance(context)
        config.smsCloudEnabled = true
        config.cloudSubscriptionId = 2
        var resets = 0
        config.onSmsReset = { resets += 1 }

        config.resetToDefaults()

        assertFalse(config.smsCloudEnabled)
        assertNull(config.cloudSubscriptionId)
        assertEquals(1, resets)
    }

    private fun preferences(store: MutableMap<String, Any?>): SharedPreferences {
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { prefs.contains(any()) } answers { store.containsKey(firstArg()) }
        every { prefs.getString(any(), any()) } answers { store[firstArg<String>()] as? String ?: secondArg<String?>() }
        every { prefs.getBoolean(any(), any()) } answers { store[firstArg<String>()] as? Boolean ?: secondArg<Boolean>() }
        every { prefs.getInt(any(), any()) } answers { store[firstArg<String>()] as? Int ?: secondArg<Int>() }
        every { prefs.getLong(any(), any()) } answers { store[firstArg<String>()] as? Long ?: secondArg<Long>() }
        every { prefs.getFloat(any(), any()) } answers { store[firstArg<String>()] as? Float ?: secondArg<Float>() }
        every { prefs.all } answers { store.toMap() }
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } answers { store[firstArg()] = secondArg<String?>(); editor }
        every { editor.putBoolean(any(), any()) } answers { store[firstArg()] = secondArg<Boolean>(); editor }
        every { editor.putInt(any(), any()) } answers { store[firstArg()] = secondArg<Int>(); editor }
        every { editor.putLong(any(), any()) } answers { store[firstArg()] = secondArg<Long>(); editor }
        every { editor.putFloat(any(), any()) } answers { store[firstArg()] = secondArg<Float>(); editor }
        every { editor.remove(any()) } answers { store.remove(firstArg<String>()); editor }
        every { editor.clear() } answers { store.clear(); editor }
        every { editor.apply() } just Runs
        every { editor.commit() } returns true
        return prefs
    }

    private fun clearSingleton() {
        val owners = listOf(ConfigManager::class.java, ConfigManager.Companion::class.java)
        for (owner in owners) {
            val field = owner.declaredFields.firstOrNull { it.name == "INSTANCE" } ?: continue
            field.isAccessible = true
            val receiver = if (Modifier.isStatic(field.modifiers)) null else ConfigManager.Companion
            field.set(receiver, null)
            return
        }
        error("ConfigManager INSTANCE field not found")
    }
}
