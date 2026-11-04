package com.mobilerun.portal.sms

/** Counts SMS parts the Portal sent in a sliding window. Relay sends are refused past the cap; local API sends only count. */
class SmsPartRateLimiter(
    private val maxParts: Int = DEFAULT_MAX_PARTS,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    /** One send's parts in the window; refunding it removes exactly this entry. */
    class Acquisition internal constructor(internal val at: Long, internal val parts: Int)

    private val entries = ArrayDeque<Acquisition>()

    /** Null past the cap. */
    @Synchronized
    fun tryAcquire(parts: Int): Acquisition? {
        prune()
        if (usedLocked() + parts > maxParts) return null
        return Acquisition(clock(), parts).also { entries.addLast(it) }
    }

    @Synchronized
    fun record(parts: Int) {
        prune()
        entries.addLast(Acquisition(clock(), parts))
    }

    /** Returns the parts of a tryAcquire whose send never started. */
    @Synchronized
    fun refund(acquisition: Acquisition) {
        entries.removeAll { it === acquisition }
    }

    @Synchronized
    fun used(): Int {
        prune()
        return usedLocked()
    }

    private fun usedLocked(): Int = entries.sumOf { it.parts }

    private fun prune() {
        val cutoff = clock() - windowMs
        while (entries.isNotEmpty() && entries.first().at <= cutoff) entries.removeFirst()
    }

    companion object {
        const val DEFAULT_MAX_PARTS = 25
        const val DEFAULT_WINDOW_MS = 60_000L

        /** Process-wide: every Portal send counts, whatever path it took. */
        val portal = SmsPartRateLimiter()
    }
}
