package com.mobilerun.portal.state

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    UNAUTHORIZED,
    LIMIT_EXCEEDED,
    BAD_REQUEST,
    ERROR
}

object ConnectionStateManager {
    private val _connectionState = MutableLiveData(ConnectionState.DISCONNECTED)
    val connectionState: LiveData<ConnectionState> = _connectionState
    private val epochLock = Any()
    private var lastSetState = ConnectionState.DISCONNECTED
    private var connectedEpochValue = 0L

    /** Advances on every move to CONNECTED, including ones an observer missed or saw coalesced. */
    val connectedEpoch: Long
        get() = synchronized(epochLock) { connectedEpochValue }

    fun setState(state: ConnectionState) {
        synchronized(epochLock) {
            if (state == ConnectionState.CONNECTED && lastSetState != ConnectionState.CONNECTED) {
                connectedEpochValue++
            }
            lastSetState = state
        }
        // Use postValue to allow calling from background threads
        _connectionState.postValue(state)
    }
    
    fun getState(): ConnectionState {
        return _connectionState.value ?: ConnectionState.DISCONNECTED
    }
}

