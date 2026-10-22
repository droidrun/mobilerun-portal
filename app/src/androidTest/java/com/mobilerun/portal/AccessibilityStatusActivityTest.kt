package com.mobilerun.portal

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.UiAutomation
import android.content.ComponentName
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilerun.portal.service.MobilerunAccessibilityService
import com.mobilerun.portal.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class AccessibilityStatusActivityTest {
    @Test(timeout = 60_000L)
    fun bannerTracksLateServiceConnectionAndDisconnectionWithoutResumingActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // The default UiAutomation suppresses other accessibility services during tests.
        val automation = instrumentation.getUiAutomation(
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
        )
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val originalServices = Settings.Secure.getString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        val originalEnabled = Settings.Secure.getString(
            resolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
        )
        val component = ComponentName(context, MobilerunAccessibilityService::class.java)
        val otherServices = originalServices.orEmpty().split(':').filter {
            it.isNotBlank() && ComponentName.unflattenFromString(it) != component
        }
        val application = context.applicationContext as Application
        var resumeCount = 0
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is MainActivity) resumeCount++
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }

        fun setServices(services: List<String>) {
            automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
            try {
                assertTrue(
                    Settings.Secure.putString(
                        resolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        services.joinToString(":"),
                    ),
                )
                assertTrue(
                    Settings.Secure.putInt(
                        resolver,
                        Settings.Secure.ACCESSIBILITY_ENABLED,
                        if (services.isEmpty()) 0 else 1,
                    ),
                )
            } finally {
                automation.dropShellPermissionIdentity()
            }
        }

        try {
            setServices(otherServices)
            awaitCondition("accessibility service initially unbound") {
                MobilerunAccessibilityService.getInstance() == null
            }
            application.registerActivityLifecycleCallbacks(callbacks)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var originalActivity: MainActivity
                var initialResumeCount = 0
                scenario.onActivity { activity ->
                    originalActivity = activity
                    initialResumeCount = resumeCount
                    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.accessibility_banner).visibility)
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.accessibility_status_enabled).visibility)
                }

                fun awaitBanner(connected: Boolean) {
                    awaitCondition("runtime connection and banner reflect connected=$connected") {
                        var matches = false
                        scenario.onActivity { activity ->
                            assertSame("activity must not be recreated", originalActivity, activity)
                            assertEquals("activity must stay resumed", Lifecycle.State.RESUMED, activity.lifecycle.currentState)
                            assertEquals("status must update without another onResume", initialResumeCount, resumeCount)
                            matches = (MobilerunAccessibilityService.getInstance() != null) == connected &&
                                activity.findViewById<View>(R.id.accessibility_banner).visibility ==
                                (if (connected) View.GONE else View.VISIBLE) &&
                                activity.findViewById<View>(R.id.accessibility_status_enabled).visibility ==
                                (if (connected) View.VISIBLE else View.GONE)
                        }
                        matches
                    }
                }

                // Enable through secure settings so Android binds after the initial UI check,
                // without leaving MainActivity to visit Settings or calling onResume again.
                setServices(otherServices + component.flattenToString())
                awaitBanner(connected = true)
                setServices(otherServices)
                awaitBanner(connected = false)
                setServices(otherServices + component.flattenToString())
                awaitBanner(connected = true)
            }
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks)
            automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
            try {
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, originalServices)
                Settings.Secure.putString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, originalEnabled)
            } finally {
                automation.dropShellPermissionIdentity()
            }
        }
    }

    private fun awaitCondition(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50L)
        }
        assertTrue(description, condition())
    }
}
