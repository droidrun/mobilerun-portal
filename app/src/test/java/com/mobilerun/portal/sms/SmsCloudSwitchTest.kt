package com.mobilerun.portal.sms

import org.junit.Assert.assertEquals
import org.junit.Test

class SmsCloudSwitchTest {
    private val events = mutableListOf<String>()
    private var latest: SmsProviderRow? = SmsProviderRow(id = 41, type = SmsProviderRow.TYPE_INBOX, address = "+1", body = "x", date = 900)
    private var cloudSim: Int? = null

    private val switch = SmsCloudSwitch(
        setEnabled = { events += "enabled=$it" },
        cloudSubscriptionId = { cloudSim },
        setCloudSubscriptionId = {
            cloudSim = it
            events += "sim=$it"
        },
        latestRow = { latest },
        seed = { row, coveredFrom -> events += "seed=${row?.id}@${row?.date} coveredFrom=$coveredFrom" },
        clear = { events += "clear" },
        clock = { 5_000L },
    )

    @Test
    fun enable_seedsWatermarksAtTheNewestRowBeforeTurningOn() {
        switch.prepare(subscriptionId = 2)
        switch.turnOn()

        assertEquals(listOf("sim=2", "seed=41@900 coveredFrom=5000", "enabled=true"), events)
    }

    @Test
    fun enable_onAnEmptyProviderSeedsZero() {
        latest = null
        switch.prepare(subscriptionId = null)

        assertEquals(listOf("sim=null", "seed=null@null coveredFrom=5000"), events)
    }

    @Test
    fun disable_turnsOffFirstThenClearsReporterState() {
        switch.disable()

        assertEquals(listOf("enabled=false", "sim=null", "clear"), events)
    }

    @Test
    fun chooseSim_reseedsBeforeSwitchingSoNothingFromThePauseIsUploaded() {
        cloudSim = 1
        switch.chooseSim(2)

        assertEquals(listOf("seed=41@900 coveredFrom=5000", "sim=2"), events)
    }

    @Test
    fun chooseSim_sameSimKeepsTheWatermarks() {
        cloudSim = 2
        switch.chooseSim(2)

        assertEquals(emptyList<String>(), events)
    }
}
