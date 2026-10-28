package com.mobilerun.portal

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilerun.portal.config.ConfigManager
import com.mobilerun.portal.ui.settings.SettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmsSettingsActivityTest {
    @Test
    fun settingsShowsSmsDiagnosticsSection() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            it.onActivity { activity ->
                assertEquals(android.view.View.VISIBLE, activity.findViewById<android.view.View>(R.id.sms_diagnostics_section).visibility)
                assertEquals(android.view.View.VISIBLE, activity.findViewById<android.view.View>(R.id.switch_sms_gateway).visibility)
                assertEquals(
                    ConfigManager.getInstance(activity).smsGatewayEnabled,
                    activity.findViewById<android.widget.CompoundButton>(R.id.switch_sms_gateway).isChecked,
                )
                assertTrue(activity.findViewById<android.widget.TextView>(R.id.text_sms_permissions_status).text.isNotBlank())
                assertEquals(android.view.View.VISIBLE, activity.findViewById<android.view.View>(R.id.button_request_send_sms).visibility)
                assertEquals(android.view.View.VISIBLE, activity.findViewById<android.view.View>(R.id.button_request_read_sms).visibility)
                assertEquals(android.view.View.VISIBLE, activity.findViewById<android.view.View>(R.id.button_request_receive_sms).visibility)
            }
        }
    }

    @Test
    fun recreateKeepsGatewayTurnedOffOutsideSettings() {
        val config = ConfigManager.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
        val initial = config.smsGatewayEnabled
        try {
            config.smsGatewayEnabled = true
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                config.smsGatewayEnabled = false
                scenario.recreate()

                assertFalse(config.smsGatewayEnabled)
                scenario.onActivity { activity ->
                    assertFalse(activity.findViewById<android.widget.CompoundButton>(R.id.switch_sms_gateway).isChecked)
                }
            }
        } finally {
            config.smsGatewayEnabled = initial
        }
    }
}
