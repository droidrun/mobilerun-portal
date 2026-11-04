package com.mobilerun.portal.sms

import org.junit.Assert.assertEquals
import org.junit.Test

class SmsCloudSwitchTest {
    private val events = mutableListOf<String>()
    private var latest: SmsProviderRow? = SmsProviderRow(id = 41, type = SmsProviderRow.TYPE_INBOX, address = "+1", body = "x", date = 900)

    private val switch = SmsCloudSwitch(
        setEnabled = { events += "enabled=$it" },
        setCloudSubscriptionId = { events += "sim=$it" },
        latestRow = { latest },
        seed = { row, coveredFrom -> events += "seed=${row?.id}@${row?.date} coveredFrom=$coveredFrom" },
        clear = { events += "clear" },
        clock = { 5_000L },
    )

    @Test
    fun enable_seedsWatermarksAtTheNewestRowBeforeTurningOn() {
        switch.enable(cloudSubscriptionId = 2)

        assertEquals(listOf("sim=2", "seed=41@900 coveredFrom=5000", "enabled=true"), events)
    }

    @Test
    fun enable_onAnEmptyProviderSeedsZero() {
        latest = null
        switch.enable(cloudSubscriptionId = null)

        assertEquals(listOf("sim=null", "seed=null@null coveredFrom=5000", "enabled=true"), events)
    }

    @Test
    fun disable_turnsOffFirstThenClearsReporterState() {
        switch.disable()

        assertEquals(listOf("enabled=false", "sim=null", "clear"), events)
    }
}
