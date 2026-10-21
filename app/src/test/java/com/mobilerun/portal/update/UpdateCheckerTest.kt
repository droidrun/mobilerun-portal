package com.mobilerun.portal.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpdateCheckerTest {
    @Test
    fun newerMajor_returnsTrue() {
        assertTrue(UpdateChecker.isNewerVersion("2.0.0", "1.9.9"))
    }

    @Test
    fun sameVersion_isNotNewer() {
        assertFalse(UpdateChecker.isNewerVersion("1.0.0", "1.0.0"))
    }

    @Test
    fun olderPatch_isNotNewer() {
        assertFalse(UpdateChecker.isNewerVersion("1.0.0", "1.0.1"))
    }

    @Test
    fun vPrefix_isHandled() {
        assertTrue(UpdateChecker.isNewerVersion("v1.2.0", "1.1.9"))
    }

    @Test
    fun malformedSegment_isTreatedAsZero() {
        assertFalse(UpdateChecker.isNewerVersion("1.0.beta", "1.0.0"))
    }

    @Test
    fun parseFeed_acceptsCanonicalFeed() {
        val feed = """
            {
              "version": "1.2.3",
              "versionCode": 123,
              "packageName": "com.mobilerun.portal",
              "apkUrl": "https://github.com/droidrun/mobilerun-portal/releases/download/v1.2.3/com.mobilerun.portal-1.2.3.apk",
              "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            }
        """.trimIndent()

        val info = UpdateChecker.parseFeed(feed, "com.mobilerun.portal")

        assertEquals("1.2.3", info.latestVersion)
        assertEquals(123L, info.versionCode)
        assertEquals("com.mobilerun.portal", info.packageName)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseFeed_rejectsWrongPackage() {
        UpdateChecker.parseFeed(validFeed(packageName = "com.droidrun.portal"), "com.mobilerun.portal")
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseFeed_rejectsForeignPackageByDefault() {
        UpdateChecker.parseFeed(validFeed(packageName = "com.example.foreign"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseFeed_rejectsMissingApkUrl() {
        val feed = """
            {
              "version": "1.2.3",
              "versionCode": 123,
              "packageName": "com.mobilerun.portal",
              "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            }
        """.trimIndent()
        UpdateChecker.parseFeed(feed, "com.mobilerun.portal")
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseFeed_rejectsHttpUrlInProductionMode() {
        UpdateChecker.parseFeed(
            validFeed(apkUrl = "http://example.com/app.apk"),
            "com.mobilerun.portal",
            allowLocalHttp = false,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseFeed_rejectsMissingSha() {
        val feed = """
            {
              "version": "1.2.3",
              "versionCode": 123,
              "packageName": "com.mobilerun.portal",
              "apkUrl": "https://github.com/droidrun/mobilerun-portal/releases/download/v1.2.3/com.mobilerun.portal-1.2.3.apk"
            }
        """.trimIndent()
        UpdateChecker.parseFeed(feed, "com.mobilerun.portal")
    }

    @Test
    fun sha256Hex_hashesBytes() {
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            UpdateChecker.sha256Hex("hello".toByteArray()),
        )
    }

    @Test
    fun matchesSha256_rejectsCorruptedApkBytes() {
        val apk = File.createTempFile("portal-update", ".apk")
        try {
            apk.writeBytes("corrupted".toByteArray())
            assertFalse(
                UpdateChecker.matchesSha256(
                    apk,
                    "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                ),
            )
        } finally {
            apk.delete()
        }
    }

    private fun validFeed(
        packageName: String = "com.mobilerun.portal",
        apkUrl: String = "https://github.com/droidrun/mobilerun-portal/releases/download/v1.2.3/com.mobilerun.portal-1.2.3.apk",
    ): String {
        return """
            {
              "version": "1.2.3",
              "versionCode": 123,
              "packageName": "$packageName",
              "apkUrl": "$apkUrl",
              "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            }
        """.trimIndent()
    }
}
