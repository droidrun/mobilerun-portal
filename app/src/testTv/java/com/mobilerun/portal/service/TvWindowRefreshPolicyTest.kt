package com.mobilerun.portal.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvWindowRefreshPolicyTest {
    @Test fun onlyAnEmptyWindowListAndMissingRootWithAppFocusPermitRepair() {
        for (windowsEmpty in listOf(false, true)) {
            for (rootMissing in listOf(false, true)) {
                for (focusedApp in listOf(false, true)) {
                    assertEquals(windowsEmpty && rootMissing && focusedApp,
                        TvWindowRefreshPolicy().shouldRefresh(windowsEmpty, rootMissing, focusedApp, 0))
                }
            }
        }
    }

    @Test fun rejectedProbeDoesNotSpendTheCooldown() {
        val policy = TvWindowRefreshPolicy()
        assertFalse(policy.shouldRefresh(true, true, false, 10_000))
        assertTrue(policy.shouldRefresh(true, true, true, 10_000))
    }

    @Test fun repeatedReadersAreLimitedUntilExactlyFiveSeconds() {
        val policy = TvWindowRefreshPolicy()
        assertTrue(policy.shouldRefresh(true, true, true, 0))
        assertFalse(policy.shouldRefresh(true, true, true, 0))
        assertFalse(policy.shouldRefresh(true, true, true, 4_999))
        assertTrue(policy.shouldRefresh(true, true, true, 5_000))
        assertFalse(policy.shouldRefresh(true, true, true, 9_999))
    }

    @Test fun consentRefreshSuppressesImmediateDuplicateSelfHealing() {
        val policy = TvWindowRefreshPolicy()
        policy.consentRefreshed(20_000)
        assertFalse(policy.shouldRefresh(true, true, true, 20_400))
        assertFalse(policy.shouldRefresh(true, true, true, 24_999))
        assertTrue(policy.shouldRefresh(true, true, true, 25_000))
    }

    @Test fun concurrentRootReadersShareOneRefreshReservation() {
        val policy = TvWindowRefreshPolicy()
        val start = CountDownLatch(1)
        val done = CountDownLatch(20)
        val accepted = AtomicInteger()
        val pool = Executors.newFixedThreadPool(4)
        try {
            repeat(20) {
                pool.execute {
                    try {
                        start.await()
                        if (policy.shouldRefresh(true, true, true, 5_000)) accepted.incrementAndGet()
                    } finally { done.countDown() }
                }
            }
            start.countDown()
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertEquals(1, accepted.get())
        } finally { pool.shutdownNow() }
    }
}
