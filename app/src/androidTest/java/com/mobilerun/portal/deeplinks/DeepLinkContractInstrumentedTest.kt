package com.mobilerun.portal.deeplinks

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilerun.portal.api.ApiHandler
import com.mobilerun.portal.core.StateRepository
import com.mobilerun.portal.service.ActionDispatcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Real APK declarations and Android resolution, with every activity launch attempt blocked. */
@RunWith(AndroidJUnit4::class)
class DeepLinkContractInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val launchAttempts = AtomicInteger()
    private val launchMonitor = object : Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult {
            launchAttempts.incrementAndGet()
            return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
        }
    }
    private lateinit var handler: ApiHandler
    private lateinit var dispatcher: ActionDispatcher
    private lateinit var fixturePackage: String

    @Before
    fun setUp() {
        instrumentation.addMonitor(launchMonitor)
        val context = instrumentation.targetContext
        fixturePackage = instrumentation.context.packageName
        // A null service makes this an explicit headless call even if another test enabled a11y.
        handler = ApiHandler(StateRepository(null), { null }, { context.packageManager }, { "test" }, context)
        dispatcher = ActionDispatcher(handler)
    }

    @After
    fun tearDown() {
        instrumentation.waitForIdleSync()
        instrumentation.removeMonitor(launchMonitor)
        if (::handler.isInitialized) handler.close()
        assertEquals("inspection and resolution must not launch any activity", 0, launchAttempts.get())
    }

    @Test
    fun discoveryPreservesCompiledManifestFiltersResourcesAliasesAndCallability() {
        val result = dispatch("app/deep-links", JSONObject().put("package", fixturePackage))
        assertEquals(fixturePackage, result.getString("package"))
        assertEquals("manifest", result.getString("scope"))
        assertEquals(result.toString(), listOf("configuration_dependent_data_ignored"), result.getJSONArray("warnings").strings())
        assertFalse(result.getBoolean("declarationsComplete"))
        assertTrue(result.has("versionCode"))
        assertTrue(result.has("lastUpdateTime"))
        val limitations = result.getJSONArray("limitations").strings()
        assertTrue(limitations.contains("runtime_routes_not_enumerated"))
        assertTrue(limitations.contains("dynamic_app_link_rules_not_evaluated"))

        val filters = result.getJSONArray("filters").objects()
        val activityName = DeepLinkFixtureActivity::class.java.name
        val activityFilters = filters.filter { it.getString("activity") == activityName }
        assertEquals("VIEW filters must remain separate and SEND must be omitted", 2, activityFilters.size)
        val resourceFilter = activityFilters.single { filter ->
            filter.getJSONArray("data").objects().any { it.optString("scheme") == "fixture-resource" }
        }
        assertEquals("base", resourceFilter.getString("source"))
        assertTrue(resourceFilter.getBoolean("enabled"))
        assertTrue(resourceFilter.getBoolean("exported"))
        assertTrue(resourceFilter.getBoolean("callable"))
        assertTrue(resourceFilter.getJSONArray("actions").strings().contains(Intent.ACTION_VIEW))
        assertTrue(resourceFilter.getJSONArray("categories").strings().contains(Intent.CATEGORY_BROWSABLE))
        val resourceData = resourceFilter.getJSONArray("data").getJSONObject(0)
        assertEquals("routes.example", resourceData.getString("host"))
        assertEquals("/products/", resourceData.getString("pathPrefix"))

        val splitDataFilter = activityFilters.single { it !== resourceFilter }
        val separateData = splitDataFilter.getJSONArray("data").objects()
        assertEquals("individual data tags must remain separate", 3, separateData.size)
        assertTrue(separateData.any { it.optString("scheme") == "fixture-secondary" })
        assertTrue(separateData.any { it.optString("host") == "secondary.example" })
        assertTrue(separateData.any { it.optString("pathPattern") == "/items/.*" })

        val alias = filters.single { it.getString("activity") == aliasName() }
        assertEquals(activityName, alias.getString("targetActivity"))
        assertTrue(alias.getBoolean("callable"))

        val privateActivity = filters.single { it.getString("activity") == DeepLinkPrivateFixtureActivity::class.java.name }
        assertFalse(privateActivity.getBoolean("exported"))
        assertFalse(privateActivity.getBoolean("callable"))
        val disabledActivity = filters.single { it.getString("activity") == DeepLinkDisabledFixtureActivity::class.java.name }
        assertFalse(disabledActivity.getBoolean("enabled"))
        assertFalse(disabledActivity.getBoolean("callable"))
        val protectedActivity = filters.single { it.getString("activity") == DeepLinkProtectedFixtureActivity::class.java.name }
        assertEquals("android.permission.MASTER_CLEAR", protectedActivity.getString("permission"))
        assertFalse(protectedActivity.getBoolean("callable"))

        val localizedData = filters.filter { it.getString("activity") == DeepLinkEdgeFixtureActivity::class.java.name }
            .flatMap { it.getJSONArray("data").objects() }
            .single { it.optString("scheme") == "fixture-localized" }
        assertFalse("Android ignores configuration-dependent <data> values", localizedData.has("host"))
    }

    @Test
    fun androidIgnoresConfigurationDependentHostsAndBlocksFileUrisLikeDiscoveryReports() {
        // The dropped host leaves a scheme-only filter, so any host resolves.
        assertTrue(resolve("fixture-localized://unrelated.example/any").getBoolean("canResolve"))

        val file = resolve("file:///fixture/report.txt")
        assertFalse(file.getBoolean("canResolve"))
        assertEquals(listOf("file_uri_exposed"), file.getJSONArray("warnings").strings())
        val match = file.getJSONArray("matches").objects().single()
        assertEquals(DeepLinkEdgeFixtureActivity::class.java.name, match.getString("activity"))
        assertFalse(match.getBoolean("callable"))

        val system = resolve("file:///system/fixture.txt")
        assertTrue(system.getBoolean("canResolve"))
        assertEquals(0, system.getJSONArray("warnings").length())
    }

    @Test
    fun concreteResolutionUsesAndroidMatchingWithoutAccessibilityOrNavigation() {
        val result = dispatch(
            "/action/app/deep-link/resolve",
            JSONObject().put("packageName", fixturePackage)
                .put("deepLink", "fixture-resource://routes.example/products/42"),
        )
        assertEquals(fixturePackage, result.getString("package"))
        assertEquals("android_intent_resolution", result.getString("scope"))
        assertTrue(result.getBoolean("canResolve"))
        assertFalse(result.getBoolean("navigationVerified"))
        val match = result.getJSONArray("matches").objects().single {
            it.getString("activity") == DeepLinkFixtureActivity::class.java.name
        }
        assertTrue(match.getBoolean("callable"))
        val filter = match.getJSONObject("filter")
        assertTrue(filter.getJSONArray("schemes").strings().contains("fixture-resource"))
        assertTrue(filter.getJSONArray("authorities").objects().any { it.getString("host") == "routes.example" })
        assertTrue(filter.getJSONArray("paths").objects().any { it.getString("value") == "/products/" })

        val aliasResult = resolve("fixture-alias://alias.example/open")
        assertTrue(aliasResult.getBoolean("canResolve"))
        assertTrue(aliasResult.getJSONArray("matches").objects().any { it.getString("activity") == aliasName() })

        val unmatched = resolve("fixture-resource://routes.example/outside")
        assertFalse(unmatched.getBoolean("canResolve"))
        assertEquals(0, unmatched.getJSONArray("matches").length())

        val otherAction = resolve("fixture-resource://routes.example/products/42", Intent.ACTION_SEND)
        assertFalse(otherAction.getBoolean("canResolve"))
    }

    @Test
    fun unavailableComponentsCannotBeResolvedAsCallable() {
        listOf(
            "fixture-private://hidden.example",
            "fixture-disabled://off.example",
            "fixture-protected://protected.example",
        ).forEach { deepLink ->
            val result = resolve(deepLink)
            assertFalse(deepLink, result.getBoolean("canResolve"))
            assertTrue(result.getJSONArray("matches").objects().none { it.getBoolean("callable") })
            assertFalse(result.getBoolean("navigationVerified"))
        }
    }

    @Test
    fun contentProviderServesInspectionWithEncodedQueryParameters() {
        val pkg = Uri.encode(fixturePackage)
        val links = providerQuery("app/deep-links?package=$pkg")
        assertEquals(links.toString(), "success", links.getString("status"))
        assertTrue(links.getJSONObject("result").getJSONArray("filters").length() > 0)

        val link = Uri.encode("fixture-resource://routes.example/products/42?a=1&b=x+y")
        val resolved = providerQuery("app/deep-link/resolve?packageName=$pkg&deepLink=$link")
        assertTrue(resolved.toString(), resolved.getJSONObject("result").getBoolean("canResolve"))

        assertEquals(
            "Missing required param: 'package'",
            providerQuery("app/deep-links").getString("error"),
        )
        assertEquals(
            "Missing required param: 'deepLink'",
            providerQuery("app/deep-link/resolve?package=$pkg").getString("error"),
        )
    }

    private fun providerQuery(path: String): JSONObject {
        val uri = Uri.parse("content://${instrumentation.targetContext.packageName}/$path")
        instrumentation.targetContext.contentResolver.query(uri, null, null, null, null).use {
            requireNotNull(it)
            check(it.moveToFirst())
            return JSONObject(it.getString(it.getColumnIndexOrThrow("result")))
        }
    }

    private fun resolve(deepLink: String, action: String? = null): JSONObject = dispatch(
        "app/deep-link/resolve",
        JSONObject().put("package", fixturePackage).put("deepLink", deepLink).apply {
            action?.let { put("action", it) }
        },
    )

    private fun dispatch(action: String, params: JSONObject): JSONObject {
        val response = JSONObject(dispatcher.dispatch(action, params).toJson())
        assertEquals(response.toString(), "success", response.getString("status"))
        return response.getJSONObject("result")
    }

    private fun aliasName(): String = "com.mobilerun.portal.deeplinks.DeepLinkFixtureAlias"

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
    private fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)
}

// These classes are declared only in the instrumented test APK and are never started.
open class DeepLinkFixtureActivity : Activity()
class DeepLinkEdgeFixtureActivity : DeepLinkFixtureActivity()
class DeepLinkPrivateFixtureActivity : DeepLinkFixtureActivity()
class DeepLinkDisabledFixtureActivity : DeepLinkFixtureActivity()
class DeepLinkProtectedFixtureActivity : DeepLinkFixtureActivity()
