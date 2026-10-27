package com.mobilerun.portal.service

import com.mobilerun.portal.core.GenerationGate
import java.util.concurrent.atomic.AtomicLong

internal class ReverseWebSocketGenerationGate {
    private companion object {
        const val NO_RECONNECT_OWNER = -1L
    }

    private val generation = GenerationGate()
    private val reconnectOwner = AtomicLong(NO_RECONNECT_OWNER)

    fun current(): Long = generation.current()

    fun advance(): Long = generation.advance()

    fun isCurrent(candidate: Long): Boolean = generation.isCurrent(candidate)

    fun markReconnectScheduled(candidate: Long) {
        reconnectOwner.set(candidate)
    }

    fun reconnectOwner(): Long? =
        reconnectOwner.get().takeUnless { it == NO_RECONNECT_OWNER }

    fun clearReconnectIfOwnedBy(candidate: Long): Boolean =
        reconnectOwner.compareAndSet(candidate, NO_RECONNECT_OWNER)

    fun clearReconnect() {
        reconnectOwner.set(NO_RECONNECT_OWNER)
    }
}
