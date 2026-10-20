package com.mobilerun.portal.taskprompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalTaskDetailsRetryGateTest {

    private val gate = PortalTaskDetailsRetryGate(retryDelayMs = 60_000L)

    @Test
    fun canLoad_allowsFirstLoad() {
        assertTrue(gate.canLoad("task-1", "url|key", 1L, 0L))
    }

    @Test
    fun canLoad_blocksSameTaskUntilRetryDelayAfterFailure() {
        gate.onFailure("task-1", "url|key", 1L, 1_000L)

        assertFalse(gate.canLoad("task-1", "url|key", 1L, 1_000L))
        assertFalse(gate.canLoad("task-1", "url|key", 1L, 60_999L))
        assertTrue(gate.canLoad("task-1", "url|key", 1L, 61_000L))
    }

    @Test
    fun canLoad_allowsOtherTaskAfterFailure() {
        gate.onFailure("task-1", "url|key", 1L, 1_000L)

        assertTrue(gate.canLoad("task-2", "url|key", 1L, 1_000L))
    }

    @Test
    fun canLoad_allowsSameTaskWithChangedCredentials() {
        gate.onFailure("task-1", "url|old-key", 1L, 1_000L)

        assertTrue(gate.canLoad("task-1", "url|new-key", 1L, 1_000L))
    }

    @Test
    fun canLoad_allowsSameTaskAfterReconnect() {
        gate.onFailure("task-1", "url|key", 1L, 1_000L)

        assertTrue(gate.canLoad("task-1", "url|key", 2L, 1_000L))
    }

    @Test
    fun canLoad_ignoresFailureOfRequestStartedBeforeReconnect() {
        gate.onFailure("task-1", "url|key", 1L, 5_000L)

        assertTrue(gate.canLoad("task-1", "url|key", 2L, 5_000L))
    }

    @Test
    fun onFailure_restartsRetryDelay() {
        gate.onFailure("task-1", "url|key", 1L, 1_000L)
        gate.onFailure("task-1", "url|key", 1L, 61_000L)

        assertFalse(gate.canLoad("task-1", "url|key", 1L, 120_999L))
        assertTrue(gate.canLoad("task-1", "url|key", 1L, 121_000L))
    }

    @Test
    fun reset_clearsFailure() {
        gate.onFailure("task-1", "url|key", 1L, 1_000L)
        gate.reset()

        assertTrue(gate.canLoad("task-1", "url|key", 1L, 1_000L))
    }

    @Test
    fun remainingDelayMs_countsDownAfterFailure() {
        gate.onFailure("task-1", "url|key", 1L, 1_000L)

        assertEquals(60_000L, gate.remainingDelayMs("task-1", "url|key", 1L, 1_000L))
        assertEquals(15_000L, gate.remainingDelayMs("task-1", "url|key", 1L, 46_000L))
        assertEquals(0L, gate.remainingDelayMs("task-1", "url|key", 1L, 61_000L))
        assertEquals(0L, gate.remainingDelayMs("task-1", "url|key", 2L, 1_000L))
    }
}
