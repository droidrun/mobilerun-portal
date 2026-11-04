package com.mobilerun.portal.sms

/** Counts SMS parts the Portal sent in a sliding window. Relay sends are refused past the cap; local API sends only count. */
class SmsPartRateLimiter(
    private val maxParts: Int = DEFAULT_MAX_PARTS,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val entries = ArrayDeque<Pair<Long, Int>>()

    @Synchronized
    fun tryAcquire(parts: Int): Boolean {
        prune()
        if (usedLocked() + parts > maxParts) return false
        entries.addLast(clock() to parts)
        return true
    }

    @Synchronized
    fun record(parts: Int) {
        prune()
        entries.addLast(clock() to parts)
    }

    /** Returns parts taken by a tryAcquire whose send never started. */
    @Synchronized
    fun refund(parts: Int) {
        val index = entries.indexOfLast { it.second == parts }
        if (index >= 0) entries.removeAt(index)
    }

    @Synchronized
    fun used(): Int {
        prune()
        return usedLocked()
    }

    private fun usedLocked(): Int = entries.sumOf { it.second }

    private fun prune() {
        val cutoff = clock() - windowMs
        while (entries.isNotEmpty() && entries.first().first <= cutoff) entries.removeFirst()
    }

    companion object {
        const val DEFAULT_MAX_PARTS = 25
        const val DEFAULT_WINDOW_MS = 60_000L

        /** Process-wide: every Portal send counts, whatever path it took. */
        val portal = SmsPartRateLimiter()
    }
}
