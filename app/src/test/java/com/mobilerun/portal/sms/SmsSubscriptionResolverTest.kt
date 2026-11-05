package com.mobilerun.portal.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsSubscriptionResolverTest {
    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun resolve_rejectsSubscriptionIdAndSimSlotTogether() {
        val resolver = SmsSubscriptionResolver(mockk(relaxed = true))

        val result = resolver.resolve(subscriptionId = 7, simSlot = 0)

        assertEquals("Specify only one of 'subscription_id' or 'sim_slot'", result.error)
    }

    @Test
    fun resolve_rejectsSimSlotWhenReadPhoneStateMissing() {
        val context = mockk<Context>(relaxed = true)
        mockkStatic(ContextCompat::class)
        every {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
        } returns PackageManager.PERMISSION_DENIED
        val resolver = SmsSubscriptionResolver(context)

        val result = resolver.resolve(subscriptionId = null, simSlot = 0)

        assertEquals("READ_PHONE_STATE permission not granted", result.error)
    }

    @Test
    fun resolve_mapsSimSlotToActiveSubscription() {
        val context = mockk<Context>(relaxed = true)
        val manager = mockk<SubscriptionManager>()
        val info = mockk<SubscriptionInfo>()
        mockkStatic(ContextCompat::class)
        every {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
        } returns PackageManager.PERMISSION_GRANTED
        every { context.getSystemService(SubscriptionManager::class.java) } returns manager
        every { manager.activeSubscriptionInfoList } returns listOf(info)
        every { info.subscriptionId } returns 42
        every { info.simSlotIndex } returns 1
        every { info.carrierName } returns "Carrier"
        every { info.displayName } returns "SIM 2"
        val resolver = SmsSubscriptionResolver(context)

        val result = resolver.resolve(subscriptionId = null, simSlot = 1)

        assertEquals(null, result.error)
        assertEquals(42, result.subscriptionId)
        assertEquals(1, result.simSlot)
    }

    @Test
    fun capabilities_usesSnakeCaseKeys() {
        val context = mockk<Context>(relaxed = true)
        val packageManager = mockk<PackageManager>()
        mockkStatic(ContextCompat::class)
        every { context.packageManager } returns packageManager
        every { packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING) } returns true
        every { packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) } returns true
        every { ContextCompat.checkSelfPermission(context, any()) } returns PackageManager.PERMISSION_DENIED
        val resolver = SmsSubscriptionResolver(context)

        val capabilities = resolver.capabilities()

        assertTrue(capabilities.getBoolean("telephony_available"))
        assertFalse(capabilities.getBoolean("send_sms_granted"))
        assertFalse(capabilities.getBoolean("read_sms_granted"))
        assertFalse(capabilities.getBoolean("receive_sms_granted"))
        assertFalse(capabilities.getBoolean("read_phone_state_granted"))
        assertFalse(capabilities.getBoolean("read_phone_numbers_granted"))
        assertTrue(capabilities.has("active_subscriptions"))
    }

    @Test
    fun bridgeSubscriptions_reportsMissingReadPhoneStateInsteadOfEmptyList() {
        val context = mockk<Context>(relaxed = true)
        mockkStatic(ContextCompat::class)
        every {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
        } returns PackageManager.PERMISSION_DENIED

        val result = SmsSubscriptionResolver(context).bridgeSubscriptions()

        assertEquals(SmsSubscriptionsResult.PermissionDenied, result)
        assertTrue(SmsSubscriptionResolver(context).activeSubscriptions().isEmpty())
    }

    @Test
    fun bridgeSubscriptions_normalizesPhoneNumbersToE164AndOmitsBlank() {
        val context = mockk<Context>(relaxed = true)
        val manager = mockk<SubscriptionManager>()
        val first = subscriptionInfo(subscriptionId = 1, slot = 0, countryIso = "us")
        val second = subscriptionInfo(subscriptionId = 2, slot = 1, countryIso = "de")
        mockkStatic(ContextCompat::class)
        every {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
        } returns PackageManager.PERMISSION_GRANTED
        every { context.getSystemService(SubscriptionManager::class.java) } returns manager
        every { manager.activeSubscriptionInfoList } returns listOf(first, second)
        val numbers = mapOf(1 to "(555) 521-5554", 2 to "  ")
        val resolver = SmsSubscriptionResolver(
            context,
            phoneNumberReader = { _, info -> numbers[info.subscriptionId] },
            e164Formatter = { number, iso -> if (iso == "US") "+1" + number.filter(Char::isDigit) else null },
        )

        val result = resolver.bridgeSubscriptions() as SmsSubscriptionsResult.Available

        assertEquals(
            listOf(
                SmsSimInfo(1, 0, phoneNumber = "+15555215554", carrier = "Carrier", displayName = "SIM"),
                SmsSimInfo(2, 1, phoneNumber = null, carrier = "Carrier", displayName = "SIM"),
            ),
            result.subscriptions,
        )
        assertFalse(result.subscriptions[1].toBridgeJson().has("phoneNumber"))
    }

    @Test
    fun bridgeSubscriptions_securityExceptionIsPermissionDenied() {
        val context = mockk<Context>(relaxed = true)
        val manager = mockk<SubscriptionManager>()
        mockkStatic(ContextCompat::class)
        every { ContextCompat.checkSelfPermission(context, any()) } returns PackageManager.PERMISSION_GRANTED
        every { context.getSystemService(SubscriptionManager::class.java) } returns manager
        every { manager.activeSubscriptionInfoList } throws SecurityException("no")

        assertEquals(SmsSubscriptionsResult.PermissionDenied, SmsSubscriptionResolver(context).bridgeSubscriptions())
    }

    private fun subscriptionInfo(subscriptionId: Int, slot: Int, countryIso: String): SubscriptionInfo {
        val info = mockk<SubscriptionInfo>()
        every { info.subscriptionId } returns subscriptionId
        every { info.simSlotIndex } returns slot
        every { info.countryIso } returns countryIso
        every { info.carrierName } returns "Carrier"
        every { info.displayName } returns "SIM"
        return info
    }
}
