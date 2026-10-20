package com.mobilerun.portal.ui.taskprompt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskHistoryLoadMoreStateTest {
    private fun shouldLoadMore(
        hasNextPage: Boolean = true,
        isLoading: Boolean = false,
        isPausedAfterFailure: Boolean = false,
        firstVisibleItem: Int = 10,
        visibleItemCount: Int = 8,
        totalItemCount: Int = 20,
    ) = TaskHistoryLoadMoreState.shouldLoadMore(
        hasNextPage = hasNextPage,
        isLoading = isLoading,
        isPausedAfterFailure = isPausedAfterFailure,
        firstVisibleItem = firstVisibleItem,
        visibleItemCount = visibleItemCount,
        totalItemCount = totalItemCount,
    )

    @Test
    fun shouldLoadMore_returnsTrueNearTheEnd() {
        assertTrue(shouldLoadMore())
    }

    @Test
    fun shouldLoadMore_returnsFalseAwayFromTheEnd() {
        assertFalse(shouldLoadMore(firstVisibleItem = 0))
    }

    @Test
    fun shouldLoadMore_returnsFalseWithoutNextPageOrWhileLoading() {
        assertFalse(shouldLoadMore(hasNextPage = false))
        assertFalse(shouldLoadMore(isLoading = true))
        assertFalse(shouldLoadMore(totalItemCount = 0))
    }

    @Test
    fun shouldLoadMore_returnsFalseWhilePausedAfterFailure() {
        assertFalse(shouldLoadMore(isPausedAfterFailure = true))
    }

    @Test
    fun isNearEnd_includesThePrefetchDistance() {
        assertTrue(TaskHistoryLoadMoreState.isNearEnd(firstVisibleItem = 10, visibleItemCount = 8, totalItemCount = 20))
        assertFalse(TaskHistoryLoadMoreState.isNearEnd(firstVisibleItem = 9, visibleItemCount = 8, totalItemCount = 20))
    }
}
