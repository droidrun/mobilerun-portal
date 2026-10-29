package com.mobilerun.portal.ui

import com.mobilerun.portal.state.ConnectionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionCardUiPolicyTest {
    @Test
    fun connectionProgressStatesOfferSignOutWhenCloudCredentialsExist() {
        listOf(
            ConnectionState.CONNECTING,
            ConnectionState.RECONNECTING,
        ).forEach { state ->
            assertTrue(
                "$state should expose sign out",
                ConnectionCardUiPolicy.shouldShowSignOut(
                    state = state,
                    hasCloudCredentials = true,
                ),
            )
        }
    }

    @Test
    fun connectedAndCloudErrorStatesOfferSignOutWhenCloudCredentialsExist() {
        listOf(
            ConnectionState.CONNECTED,
            ConnectionState.UNAUTHORIZED,
            ConnectionState.LIMIT_EXCEEDED,
            ConnectionState.BAD_REQUEST,
            ConnectionState.ERROR,
        ).forEach { state ->
            assertTrue(
                "$state should expose sign out",
                ConnectionCardUiPolicy.shouldShowSignOut(
                    state = state,
                    hasCloudCredentials = true,
                ),
            )
        }
    }

    @Test
    fun supportedStatesDoNotOfferSignOutWithoutCloudCredentials() {
        ConnectionState.values().forEach { state ->
            assertFalse(
                "$state should hide sign out without credentials",
                ConnectionCardUiPolicy.shouldShowSignOut(
                    state = state,
                    hasCloudCredentials = false,
                ),
            )
        }
    }

    @Test
    fun disconnectedDoesNotOfferSignOutEvenWithCloudCredentials() {
        assertFalse(
            ConnectionCardUiPolicy.shouldShowSignOut(
                state = ConnectionState.DISCONNECTED,
                hasCloudCredentials = true,
            ),
        )
    }

    @Test
    fun disconnectedWithCloudCredentialsOffersReconnect() {
        assertTrue(
            ConnectionCardUiPolicy.shouldOfferReconnect(
                state = ConnectionState.DISCONNECTED,
                hasCloudCredentials = true,
                credentialsRejected = false,
            ),
        )
    }

    @Test
    fun disconnectedAfterRejectedCredentialsOffersSignIn() {
        assertFalse(
            ConnectionCardUiPolicy.shouldOfferReconnect(
                state = ConnectionState.DISCONNECTED,
                hasCloudCredentials = true,
                credentialsRejected = true,
            ),
        )
    }

    @Test
    fun reconnectIsOfferedOnlyWhenDisconnectedWithCloudCredentials() {
        ConnectionState.values().forEach { state ->
            assertFalse(
                "$state should not offer reconnect without credentials",
                ConnectionCardUiPolicy.shouldOfferReconnect(
                    state = state,
                    hasCloudCredentials = false,
                    credentialsRejected = false,
                ),
            )
            if (state != ConnectionState.DISCONNECTED) {
                assertFalse(
                    "$state should not offer reconnect",
                    ConnectionCardUiPolicy.shouldOfferReconnect(
                        state = state,
                        hasCloudCredentials = true,
                        credentialsRejected = false,
                    ),
                )
            }
        }
    }
}
