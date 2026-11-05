package com.mobilerun.portal.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SmsPermissionCheckTest {
    private val context = mockk<Context>()

    @Before
    fun setUp() {
        mockkStatic(ContextCompat::class, PermissionChecker::class)
        // The runtime permission is granted.
        every { ContextCompat.checkSelfPermission(any(), any()) } returns PackageManager.PERMISSION_GRANTED
    }

    @After
    fun tearDown() {
        unmockkStatic(ContextCompat::class, PermissionChecker::class)
    }

    @Test
    fun softDeniedAppOp_isNotGranted() {
        every { PermissionChecker.checkSelfPermission(any(), any()) } returns PermissionChecker.PERMISSION_DENIED_APP_OP

        assertFalse(isGranted(context, Manifest.permission.READ_SMS))
    }

    @Test
    fun allowedAppOp_isGranted() {
        every { PermissionChecker.checkSelfPermission(any(), any()) } returns PermissionChecker.PERMISSION_GRANTED

        assertTrue(isGranted(context, Manifest.permission.READ_SMS))
    }
}
