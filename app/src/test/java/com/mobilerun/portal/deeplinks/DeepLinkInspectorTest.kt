package com.mobilerun.portal.deeplinks

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Process
import android.util.TypedValue
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Installed APK I/O and cache behavior, using the AAPT2-compiled manifest fixture. */
class DeepLinkInspectorTest {
    @get:Rule
    val files = TemporaryFolder()

    private val context = mockk<Context>()
    private val packageManager = mockk<PackageManager>()
    private val portalResources = mockk<Resources>()
    private val targetResources = mockk<Resources>()
    private val application = mockk<ApplicationInfo>()
    private val info = mockk<PackageInfo>()
    private lateinit var linkActivity: ActivityInfo
    private lateinit var inspector: DeepLinkInspector
    private var configuration = mockk<Configuration>()
    private var resourceScheme = "resource-scheme"
    private var resolvedResourceValue: String? = null
    private var hostChangingConfigurations = 0

    @Before
    fun setUp() {
        mockkStatic(Process::class)
        every { Process.myUid() } returns PORTAL_UID
        every { context.resources } returns portalResources
        every { portalResources.configuration } answers { configuration }
        every { context.checkSelfPermission(any()) } returns PackageManager.PERMISSION_DENIED
        every { packageManager.getPackageInfo(PACKAGE, any<Int>()) } returns info
        every { packageManager.getResourcesForApplication(PACKAGE) } returns targetResources
        every { packageManager.getComponentEnabledSetting(any()) } returns
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT

        // Resource lookup is Android-owned. Keep the installed APK's real reference IDs,
        // and supply package resources as PackageManager would on the device.
        mockkConstructor(TypedValue::class)
        every { targetResources.getValue(any<Int>(), any(), true) } answers {
            resolvedResourceValue = when (firstArg<Int>()) {
                0x7f010001 -> resourceScheme
                0x7f010000 -> "resource.example"
                else -> error("Unexpected fixture resource")
            }
            secondArg<TypedValue>().string = resolvedResourceValue
            secondArg<TypedValue>().changingConfigurations =
                if (firstArg<Int>() == 0x7f010000) hostChangingConfigurations else 0
        }
        every { anyConstructed<TypedValue>().coerceToString() } answers { resolvedResourceValue }

        application.enabled = true
        application.uid = OTHER_UID
        application.publicSourceDir = apk("base.apk").absolutePath
        application.splitPublicSourceDirs = null
        application.splitSourceDirs = null
        info.applicationInfo = application
        info.versionCode = 42
        info.lastUpdateTime = 100L
        linkActivity = activity("LinkActivity", exported = true)
        val alias = activity("Alias", exported = true).apply {
            targetActivity = "$PACKAGE.LinkActivity"
            permission = "com.example.PERMISSION"
        }
        info.activities = arrayOf(linkActivity, alias)
        inspector = DeepLinkInspector(context, packageManager)
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun readsBaseAndInstalledSplitApksAndReportsTheirFiltersSeparately() {
        application.splitPublicSourceDirs = arrayOf(apk("feature.apk").absolutePath)

        val result = inspector.discover(PACKAGE)
        val filters = result.getJSONArray("filters").objects()

        assertTrue(result.getBoolean("declarationsComplete"))
        assertEquals(emptyList<String>(), result.getJSONArray("warnings").strings())
        assertEquals(4, filters.size)
        assertEquals(setOf("base", "split:1"), filters.map { it.getString("source") }.toSet())
        assertTrue(filters.any { it.getString("activity") == "$PACKAGE.Alias" })
        assertTrue(link(result).getBoolean("autoVerify"))
        assertEquals("resource-scheme", resolvedScheme(result))
        assertEquals("manifest", result.getString("scope"))
        verify(exactly = 1) { packageManager.getResourcesForApplication(PACKAGE) }
    }

    @Test
    fun unreadableSplitReturnsPartialBaseResultsAndIsRetriedWithoutAnUpdate() {
        val split = apk("feature.apk", byteArrayOf(1, 2, 3))
        application.splitPublicSourceDirs = arrayOf(split.absolutePath)

        val partial = inspector.discover(PACKAGE)
        assertFalse(partial.getBoolean("declarationsComplete"))
        assertEquals(listOf("manifest_unreadable:split:1"), partial.getJSONArray("warnings").strings())
        assertEquals(2, partial.getJSONArray("filters").length())
        assertTrue(partial.getJSONArray("filters").objects().all { it.getString("source") == "base" })

        writeApk(split, fixture())
        val recovered = inspector.discover(PACKAGE)
        assertTrue(recovered.getBoolean("declarationsComplete"))
        assertEquals(4, recovered.getJSONArray("filters").length())
        assertEquals(emptyList<String>(), recovered.getJSONArray("warnings").strings())
        verify(exactly = 2) { packageManager.getResourcesForApplication(PACKAGE) }
    }

    @Test
    fun successfulDeclarationsAreCachedWhileLiveComponentStateIsRefreshed() {
        assertTrue(link(inspector.discover(PACKAGE)).getBoolean("callable"))
        linkActivity.enabled = false

        val result = inspector.discover(PACKAGE)

        assertTrue(result.getBoolean("declarationsComplete"))
        assertFalse(link(result).getBoolean("enabled"))
        assertFalse(link(result).getBoolean("callable"))
        verify(exactly = 1) { packageManager.getResourcesForApplication(PACKAGE) }
        verify(exactly = 2) { packageManager.getPackageInfo(PACKAGE, any<Int>()) }
    }

    @Test
    fun packageUpdateInvalidatesCachedDeclarationsAndReresolvesResources() {
        assertEquals("resource-scheme", resolvedScheme(inspector.discover(PACKAGE)))
        resourceScheme = "updated-scheme"
        info.versionCode = 43
        info.lastUpdateTime = 200L

        val result = inspector.discover(PACKAGE)

        assertEquals("updated-scheme", resolvedScheme(result))
        assertEquals(43L, result.getLong("versionCode"))
        assertEquals(200L, result.getLong("lastUpdateTime"))
        verify(exactly = 2) { packageManager.getResourcesForApplication(PACKAGE) }
    }

    @Test
    fun configurationChangeInvalidatesCachedResourceValues() {
        assertEquals("resource-scheme", resolvedScheme(inspector.discover(PACKAGE)))
        resourceScheme = "localized-scheme"
        configuration = mockk()

        val result = inspector.discover(PACKAGE)

        assertEquals("localized-scheme", resolvedScheme(result))
        verify(exactly = 2) { packageManager.getResourcesForApplication(PACKAGE) }
    }

    @Test
    fun configurationDependentDataValuesAreOmittedAndReported() {
        hostChangingConfigurations = ActivityInfo.CONFIG_LOCALE

        val result = inspector.discover(PACKAGE)

        assertFalse(result.getBoolean("declarationsComplete"))
        assertEquals(listOf("configuration_dependent_data_ignored"), result.getJSONArray("warnings").strings())
        val data = link(result).getJSONArray("data").objects()
        assertTrue(data.none { it.has("host") && it.getString("host") == "resource.example" })
        assertTrue(data.any { it.optString("scheme") == "resource-scheme" && !it.has("host") })
    }

    @Test
    fun emittedDeclarationTextIsBoundedAcrossApks() {
        application.splitPublicSourceDirs = arrayOf(apk("feature.apk").absolutePath)
        resourceScheme = "s".repeat(600_000)

        val result = inspector.discover(PACKAGE)
        val filters = result.getJSONArray("filters").objects()

        assertFalse(result.getBoolean("declarationsComplete"))
        assertEquals(listOf("output_limit_exceeded"), result.getJSONArray("warnings").strings())
        assertEquals(listOf("base", "base"), filters.map { it.getString("source") })
        assertTrue(result.toString().length < 1_200_000)
    }

    @Test
    fun sameUidActivityIsCallableWithoutExportOrPermissionButForeignUidIsNot() {
        application.uid = PORTAL_UID
        linkActivity.exported = false
        linkActivity.permission = "com.example.PRIVATE"

        val own = link(inspector.discover(PACKAGE))
        assertFalse(own.getBoolean("exported"))
        assertEquals("com.example.PRIVATE", own.getString("permission"))
        assertTrue(own.getBoolean("callable"))

        application.uid = OTHER_UID
        assertFalse(link(inspector.discover(PACKAGE)).getBoolean("callable"))
        verify(exactly = 1) { packageManager.getResourcesForApplication(PACKAGE) }
    }

    private fun activity(simpleName: String, exported: Boolean): ActivityInfo = mockk<ActivityInfo>().apply {
        name = "$PACKAGE.$simpleName"
        packageName = PACKAGE
        applicationInfo = application
        enabled = true
        this.exported = exported
    }

    private fun apk(name: String, manifest: ByteArray = fixture()): File = files.newFile(name).also {
        writeApk(it, manifest)
    }

    private fun writeApk(file: File, manifest: ByteArray) {
        ZipOutputStream(file.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("AndroidManifest.xml"))
            output.write(manifest)
            output.closeEntry()
        }
    }

    private fun fixture(): ByteArray = javaClass.getResourceAsStream("/deeplinks/manifest-aapt2.axml")!!
        .use { it.readBytes() }

    private fun link(result: JSONObject): JSONObject = result.getJSONArray("filters").objects()
        .single { it.getString("activity") == "$PACKAGE.LinkActivity" && it.getString("source") == "base" }

    private fun resolvedScheme(result: JSONObject): String = link(result).getJSONArray("data").objects()
        .single { it.optString("host") == "resource.example" }.getString("scheme")

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
    private fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)

    private companion object {
        const val PACKAGE = "com.mobilerun.fixture.links"
        const val PORTAL_UID = 10001
        const val OTHER_UID = 10002
    }
}
