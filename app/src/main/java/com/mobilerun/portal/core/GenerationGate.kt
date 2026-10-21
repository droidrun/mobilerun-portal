package com.mobilerun.portal.core

import java.util.concurrent.atomic.AtomicLong

/** Monotonic identity for discarding work from a replaced operation. */
internal class GenerationGate {
    private val generation = AtomicLong(0L)

    fun current(): Long = generation.get()

    fun advance(): Long = generation.incrementAndGet()

    fun isCurrent(candidate: Long): Boolean = generation.get() == candidate
}
