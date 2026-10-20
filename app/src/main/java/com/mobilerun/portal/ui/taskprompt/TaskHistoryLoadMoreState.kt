package com.mobilerun.portal.ui.taskprompt

internal object TaskHistoryLoadMoreState {
    private const val PREFETCH_DISTANCE = 2

    fun isNearEnd(
        firstVisibleItem: Int,
        visibleItemCount: Int,
        totalItemCount: Int,
    ): Boolean {
        return firstVisibleItem + visibleItemCount >= totalItemCount - PREFETCH_DISTANCE
    }

    fun shouldLoadMore(
        hasNextPage: Boolean,
        isLoading: Boolean,
        isPausedAfterFailure: Boolean,
        firstVisibleItem: Int,
        visibleItemCount: Int,
        totalItemCount: Int,
    ): Boolean {
        if (!hasNextPage || isLoading || isPausedAfterFailure || totalItemCount == 0) {
            return false
        }
        return isNearEnd(firstVisibleItem, visibleItemCount, totalItemCount)
    }
}
