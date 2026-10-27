package com.mobilerun.portal.service

/** Owns one pending value, including during a slow consumer. Scheduled work must run serially. */
internal class LatestEventCoalescer<T : Any>(
    private val nowMs: () -> Long,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val consume: (T) -> Unit,
    private val discard: (T) -> Unit = {},
    private val intervalMs: Long = 300,
) : AutoCloseable {
    private val lock = Any()
    private var pending: T? = null
    private var active = false
    private var closed = false
    private var lastStarted: Long? = null

    fun offer(value: T) = synchronized(lock) {
        if (closed) {
            discard(value)
        } else {
            pending?.let(discard)
            pending = value
            scheduleIfNeeded()
        }
    }

    private fun scheduleIfNeeded() {
        if (closed || active || pending == null) return
        active = true
        val delay = lastStarted?.let { (intervalMs - (nowMs() - it)).coerceAtLeast(0) } ?: 0
        schedule(delay, ::drain)
    }

    private fun drain() {
        val value = synchronized(lock) {
            if (closed) return
            lastStarted = nowMs()
            pending.also { pending = null }
        }
        try {
            if (value != null) consume(value)
        } finally {
            value?.let(discard)
            synchronized(lock) {
                active = false
                scheduleIfNeeded()
            }
        }
    }

    override fun close() = synchronized(lock) {
        closed = true
        pending?.let(discard)
        pending = null
    }
}
