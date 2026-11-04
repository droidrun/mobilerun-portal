package com.mobilerun.portal.service

import java.util.concurrent.atomic.AtomicLong

/** One id space for every device-originated request: the relay keys them by bare id. */
internal object ReverseRequestIds {
    private val next = AtomicLong(1L)

    fun next(): Long = next.getAndIncrement()
}
