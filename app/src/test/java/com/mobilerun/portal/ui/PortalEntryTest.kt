package com.mobilerun.portal.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PortalEntryTest {
    @Test fun launcherWinsWithoutCallingFallbacks() {
        assertEquals("phone-or-tv-launcher", PortalEntry.resolve({ "phone-or-tv-launcher" },
            { error("leanback should not be queried") }, { error("fallback should not be queried") }))
    }
    @Test fun leanbackWinsWhenRegularLauncherIsMissing() {
        assertEquals("tv", PortalEntry.resolve({ null }, { "tv" }, { error("fallback should not be queried") }))
    }
    @Test fun mainActivityIsLastFallback() {
        assertEquals("main", PortalEntry.resolve({ null }, { null }, { "main" }))
    }
}
