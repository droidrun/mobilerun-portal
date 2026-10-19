package com.mobilerun.portal.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.mobilerun.portal.R
import com.mobilerun.portal.api.ApiHandler
import com.mobilerun.portal.api.ApiResponse
import com.mobilerun.portal.config.ConfigManager
import com.mobilerun.portal.core.StateRepository
import com.mobilerun.portal.events.EventHub
import com.mobilerun.portal.input.MobilerunKeyboardIME
import com.mobilerun.portal.state.ConnectionState
import com.mobilerun.portal.state.ConnectionStateManager
import com.mobilerun.portal.streaming.WebRtcManager
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal fun shouldRedactReverseRequestPayload(normalizedMethod: String): Boolean =
    normalizedMethod == "clipboard/set" || normalizedMethod == "app/deep-link"

internal fun shouldRedactReverseResponsePayload(normalizedMethod: String): Boolean =
    normalizedMethod == "clipboard/get" || normalizedMethod == "app/deep-link"

internal enum class ReverseMediaRelease {
    STOP_CAPTURE,
    STOP_SESSIONS,
    NONE,
}

/** Capture-only (shared screenshot) captures are left to their own idle stop. */
internal fun reverseMediaReleaseFor(
    captureServiceRunning: Boolean,
    captureSessionMode: WebRtcManager.CaptureSessionMode,
    streamActive: Boolean,
): ReverseMediaRelease = when {
    captureServiceRunning && captureSessionMode == WebRtcManager.CaptureSessionMode.STREAM ->
        ReverseMediaRelease.STOP_CAPTURE

    streamActive -> ReverseMediaRelease.STOP_SESSIONS
    else -> ReverseMediaRelease.NONE
}

internal enum class ReverseForegroundAction {
    NONE,
    START,
    START_THEN_RELEASE,
}

/** A start while capture owns the foreground still has to call startForeground() before releasing it. */
internal fun reverseForegroundActionFor(
    isForeground: Boolean,
    suppressedForStreaming: Boolean,
): ReverseForegroundAction = when {
    isForeground -> ReverseForegroundAction.NONE
    suppressedForStreaming -> ReverseForegroundAction.START_THEN_RELEASE
    else -> ReverseForegroundAction.START
}

class ReverseConnectionService : Service() {

    companion object {
        private const val TAG = "ReverseConnService"
        private const val CHANNEL_ID = "reverse_connection_channel"
        private const val NOTIFICATION_ID = 2002
        private const val TOAST_DEBOUNCE_MS = 60_000L
        private const val CONNECTION_LOST_TIMEOUT_SEC = 30
        private val RECONNECT_TOKEN = Any()
        const val ACTION_DISCONNECT = "com.mobilerun.portal.action.REVERSE_DISCONNECT"
        const val ACTION_RECONNECT = "com.mobilerun.portal.action.REVERSE_RECONNECT"

        /** Sent with startForegroundService(); stops the service in order after any queued start. */
        internal const val ACTION_STOP = "com.mobilerun.portal.action.REVERSE_STOP"

        @Volatile
        private var instance: ReverseConnectionService? = null

        fun getInstance(): ReverseConnectionService? = instance

        internal fun shouldNotifyStreamStoppedAfterReconnect(
            captureActive: Boolean,
            streamActive: Boolean,
            requestId: String?,
        ): Boolean {
            return captureActive && !streamActive && !requestId.isNullOrBlank()
        }
    }

    private val binder = LocalBinder()
    private lateinit var configManager: ConfigManager
    private lateinit var retryController: ReverseConnectionRetryController
    private val actionDispatcherCache =
        ServiceInstanceCache<MobilerunAccessibilityService, ActionDispatcher>()
    private var headlessActionDispatcher: ActionDispatcher? = null
    private lateinit var reverseDeviceEventRelay: ReverseDeviceEventRelay

    private var webSocketClient: WebSocketClient? = null
    private var isServiceRunning = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private val webSocketGeneration = ReverseWebSocketGenerationGate()
    private val signalingExecutor = Executors.newSingleThreadExecutor()
    private val lightweightExecutor = Executors.newSingleThreadExecutor()
    private val commandExecutor = Executors.newSingleThreadExecutor()
    private val installExecutor = Executors.newSingleThreadExecutor()
    private var lastReverseToastAtMs = 0L
    private var isForeground = false

    @Volatile
    private var foregroundSuppressed = false

    private val reconnectBackoff = ReverseReconnectBackoff()

    @Volatile
    private var lastStartId = -1

    @Volatile
    private var terminalState: ConnectionState? = null

    @Volatile
    private var reverseMediaReleased = false

    inner class LocalBinder : Binder() {
        fun getService(): ReverseConnectionService = this@ReverseConnectionService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        configManager = ConfigManager.getInstance(this)
        retryController = ReverseConnectionRetryController(
            readHttp402Snapshot = configManager::reverseJoinHttp402Snapshot,
            setHttp402Blocked = { blocked ->
                configManager.reverseJoinBlockedByHttp402 = blocked
            },
            markExplicitlyDisconnected = configManager::markReverseJoinExplicitlyDisconnected,
        )
        EventHub.init(configManager)
        reverseDeviceEventRelay = ReverseDeviceEventRelay(::currentReverseEventSender)
        reverseDeviceEventRelay.start()
        createNotificationChannel()
        Log.d(TAG, "Service Created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(
            TAG,
            "onStartCommand: intent=$intent, action=${intent?.action}, flags=$flags, startId=$startId"
        )
        lastStartId = startId
        if (intent?.action == ACTION_DISCONNECT) {
            Log.i(TAG, "onStartCommand: Disconnect requested")
            disconnectByUser(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "onStartCommand: Stop requested")
            ensureForeground(deferNotification = true)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        Log.d(TAG, "onStartCommand: Ensuring foreground...")
        ensureForeground()
        terminalState = null
        reverseMediaReleased = false
        val explicitReconnect = intent?.action == ACTION_RECONNECT
        if (explicitReconnect) {
            Log.i(TAG, "onStartCommand: Reconnect requested with latest cloud config")
            isServiceRunning.set(true)
            cancelPendingReconnects()
        }
        when (val startDecision = retryController.onStart(explicitReconnect)) {
            is ReverseConnectionStartDecision.RestoreHttp402Block -> {
                Log.i(TAG, "onStartCommand: HTTP 402 block is active; stopping without connecting")
                restoreHttp402BlockedState(startDecision.state)
                terminalState = startDecision.state
                stopSelf(startId)
                return START_NOT_STICKY
            }

            ReverseConnectionStartDecision.Connect -> Unit
        }
        if (explicitReconnect) {
            connectToHost()
            return START_STICKY
        }
        val wasRunning = isServiceRunning.getAndSet(true)
        Log.d(
            TAG,
            "onStartCommand: wasRunning=$wasRunning, now isServiceRunning=${isServiceRunning.get()}"
        )
        if (!wasRunning) {
            Log.i(
                TAG,
                "onStartCommand: Starting Reverse Connection Service, calling connectToHost()"
            )
            connectToHost()
        } else {
            Log.d(TAG, "onStartCommand: Service already running, skipping connectToHost()")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isServiceRunning.set(false)
        handler.removeCallbacksAndMessages(null)
        reverseDeviceEventRelay.stop()
        if (!reverseMediaReleased) {
            releaseReverseMedia("service_destroyed")
        }
        disconnect()
        actionDispatcherCache.clear()
        headlessActionDispatcher = null
        ConnectionStateManager.setState(
            retryController.blockedPresentationStateOrNull()
                ?: terminalState
                ?: ConnectionState.DISCONNECTED,
        )
        try {
            signalingExecutor.shutdownNow()
        } catch (_: Exception) {
        }
        try {
            lightweightExecutor.shutdownNow()
        } catch (_: Exception) {
        }
        try {
            commandExecutor.shutdownNow()
        } catch (_: Exception) {
        }
        try {
            installExecutor.shutdownNow()
        } catch (_: Exception) {
        }
        if (isForeground) {
            @Suppress("DEPRECATION")
            stopForeground(true)
            isForeground = false
        }
        Log.i(TAG, "Service Destroyed")
    }


    fun sendText(text: String): Boolean {
        val client = webSocketClient
        if (client != null && client.isOpen) {
            client.send(text)
            return true
        }
        return false
    }

    private fun currentReverseEventSender(): ((String) -> Boolean)? {
        val client = webSocketClient ?: return null
        if (!client.isOpen) return null
        return { text ->
            try {
                client.send(text)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send reverse device event", e)
                false
            }
        }
    }

    fun buildHeaders(): MutableMap<String, String> {
        val authToken = configManager.reverseConnectionToken

        val headers = mutableMapOf<String, String>()
        if (authToken.isNotBlank())
            headers["Authorization"] = "Bearer $authToken"

        headers["X-Device-ID"] = configManager.deviceID
        headers["X-Device-Name"] = configManager.deviceName
        headers["X-Device-Country"] = configManager.deviceCountryCode

        val serviceKey = configManager.reverseConnectionServiceKey
        if (serviceKey.isNotBlank()) {
            headers["X-Remote-Device-Key"] = serviceKey
        }

        return headers
    }

    private fun logConnectionAttempt(uri: URI, headers: Map<String, String>) {
        val destination = buildString {
            append(uri.scheme ?: "ws")
            append("://")
            append(uri.host ?: "unknown")
            if (uri.port != -1) {
                append(":")
                append(uri.port)
            }
            append(uri.path ?: "")
        }
        Log.d(
            TAG,
            "connectToHost: destination=$destination tokenPresent=${
                configManager.reverseConnectionToken.isNotBlank()
            } serviceKeyPresent=${configManager.reverseConnectionServiceKey.isNotBlank()} headerNames=${
                headers.keys.sorted().joinToString()
            }",
        )
    }

    private fun connectToHost() {
        Log.d(TAG, "connectToHost: called, isServiceRunning=${isServiceRunning.get()}")
        if (!isServiceRunning.get()) {
            Log.w(TAG, "connectToHost: Service not running, aborting")
            return
        }
        retryController.blockedPresentationStateOrNull()?.let { blockedState ->
            Log.i(TAG, "connectToHost: HTTP 402 block is active; skipping automatic connect")
            restoreHttp402BlockedState(blockedState)
            return
        }

        val hostUrl = configManager.reverseConnectionUrlOrDefault
        if (hostUrl.isBlank()) {
            Log.w(TAG, "connectToHost: No host URL configured")
            ConnectionStateManager.setState(ConnectionState.DISCONNECTED)
            return
        }

        try {
            Log.d(TAG, "connectToHost: Setting state to CONNECTING")
            ConnectionStateManager.setState(ConnectionState.CONNECTING)
            val connectionGeneration = closeWebSocket() // Prevent resource leaks from zombie connections
            val finalUrl = hostUrl.replace("{deviceId}", configManager.deviceID)
            val uri = URI(finalUrl)
            val headers = buildHeaders()
            logConnectionAttempt(uri, headers)

            webSocketClient = object : WebSocketClient(uri, headers) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    if (!isCurrentWebSocketCallback(connectionGeneration, "onOpen")) return
                    Log.i(
                        TAG,
                        "onOpen: Connected to Host: $hostUrl, status=${handshakedata?.httpStatus}, message=${handshakedata?.httpStatusMessage}"
                    )
                    retryController.onConnected()
                    reconnectBackoff.onConnected(SystemClock.elapsedRealtime())
                    reverseMediaReleased = false
                    ConnectionStateManager.setState(ConnectionState.CONNECTED)
                    showReverseConnectionToastIfEnoughTimeIsPassed()
                    WebRtcManager.getExistingInstance()?.let { manager ->
                        manager.setReverseConnectionService(this@ReverseConnectionService)
                        manager.onReverseConnectionOpen()
                        notifyStreamStateAfterReconnect(manager)
                    }
                }

                override fun onMessage(message: String?) {
                    if (!isCurrentWebSocketCallback(connectionGeneration, "onMessage")) return
                    handleMessage(this, message)
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    if (!isCurrentWebSocketCallback(connectionGeneration, "onClose")) return
                    Log.w(TAG, "Disconnected from Host: code=$code reason=$reason remote=$remote")
                    logNetworkState("onClose")

                    when (val decision = retryController.onClose(reason, ::cancelPendingReconnects)) {
                        is ReverseConnectionRetryDecision.Stop -> {
                            val state = if (decision.persistHttp402Block) {
                                retryController.blockedPresentationStateOrNull()
                                    ?: decision.state
                            } else {
                                decision.state
                            }
                            Log.w(
                                TAG,
                                "onClose: Terminal error ($state), stopping service",
                            )
                            ConnectionStateManager.setState(state)
                            stopAfterTerminalState(state, connectionGeneration)
                            return
                        }

                        ReverseConnectionRetryDecision.Retry -> Unit
                    }

                    // Transient disconnect: preserve media pipeline, just reconnect WS
                    ConnectionStateManager.setState(ConnectionState.DISCONNECTED)
                    scheduleReconnect(connectionGeneration)
                }

                override fun onError(ex: Exception?) {
                    if (!isCurrentWebSocketCallback(connectionGeneration, "onError")) return
                    Log.e(
                        TAG,
                        "onError: Connection Error: ${ex?.javaClass?.simpleName}: ${ex?.message}",
                        ex
                    )
                    logNetworkState("onError")
                    // onClose usually follows; schedule reconnect as safety net
                    // (isReconnecting guard prevents duplicate scheduling)
                    scheduleReconnect(connectionGeneration)
                }
            }
            Log.i(TAG, "connectToHost: Created WebSocketClient, calling connect()...")
            webSocketClient?.connectionLostTimeout = CONNECTION_LOST_TIMEOUT_SEC
            webSocketClient?.connect()
            Log.i(TAG, "connectToHost: connect() called, waiting for callbacks...")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate connection", e)
            ConnectionStateManager.setState(ConnectionState.DISCONNECTED)
            scheduleReconnect(webSocketGeneration.current())
        }
    }

    private var isReconnecting = AtomicBoolean(false)

    private fun scheduleReconnect(connectionGeneration: Long = webSocketGeneration.current()) {
        retryController.blockedPresentationStateOrNull()?.let { blockedState ->
            Log.i(TAG, "Ignoring reconnect request while HTTP 402 block is active")
            restoreHttp402BlockedState(blockedState)
            return
        }
        if (!webSocketGeneration.isCurrent(connectionGeneration)) {
            Log.d(TAG, "Ignoring stale reconnect request for generation $connectionGeneration")
            return
        }
        clearStaleQueuedReconnectOwner()
        if (!isServiceRunning.get()) return
        if (isReconnecting.getAndSet(true)) return // Already scheduled

        val step = reconnectBackoff.nextRetry(SystemClock.elapsedRealtime())
        if (step.releaseMedia) {
            Log.w(
                TAG,
                "No stable connection for ${ReverseReconnectBackoff.LONG_OUTAGE_MS / 60_000}min, releasing media",
            )
            releaseReverseMedia("ws_disconnected")
        }
        webSocketGeneration.markReconnectScheduled(connectionGeneration)

        ConnectionStateManager.setState(ConnectionState.RECONNECTING)
        Log.d(TAG, "Scheduling reconnect in ${step.delayMs}ms")
        val reconnect = Runnable {
            retryController.blockedPresentationStateOrNull()?.let { blockedState ->
                Log.i(TAG, "Skipping delayed reconnect while HTTP 402 block is active")
                clearQueuedReconnectIfOwnedBy(connectionGeneration)
                ConnectionStateManager.setState(blockedState)
                return@Runnable
            }
            if (!webSocketGeneration.isCurrent(connectionGeneration)) {
                Log.d(TAG, "Ignoring stale delayed reconnect for generation $connectionGeneration")
                clearQueuedReconnectIfOwnedBy(connectionGeneration)
                return@Runnable
            }
            if (isServiceRunning.get()) {
                clearQueuedReconnectIfOwnedBy(connectionGeneration)
                Log.d(TAG, "Attempting reconnect...")
                connectToHost()
            } else {
                clearQueuedReconnectIfOwnedBy(connectionGeneration)
                ConnectionStateManager.setState(ConnectionState.DISCONNECTED)
            }
        }
        handler.postAtTime(reconnect, RECONNECT_TOKEN, SystemClock.uptimeMillis() + step.delayMs)
    }

    private fun clearStaleQueuedReconnectOwner() {
        val owner = webSocketGeneration.reconnectOwner() ?: return
        if (!webSocketGeneration.isCurrent(owner)) {
            clearQueuedReconnectIfOwnedBy(owner)
        }
    }

    private fun clearQueuedReconnectIfOwnedBy(connectionGeneration: Long) {
        if (webSocketGeneration.clearReconnectIfOwnedBy(connectionGeneration)) {
            isReconnecting.set(false)
        }
    }

    private fun cancelPendingReconnects() {
        isReconnecting.set(false)
        webSocketGeneration.clearReconnect()
        reconnectBackoff.reset()
        handler.removeCallbacksAndMessages(RECONNECT_TOKEN)
    }

    private fun restoreHttp402BlockedState(state: ConnectionState) {
        cancelPendingReconnects()
        ConnectionStateManager.setState(state)
    }

    private fun closeWebSocket(): Long {
        val connectionGeneration = webSocketGeneration.advance()
        try {
            webSocketClient?.close()
            webSocketClient = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing connection", e)
        }
        return connectionGeneration
    }

    private fun disconnect() {
        closeWebSocket()
    }

    private fun isCurrentWebSocketCallback(connectionGeneration: Long, callback: String): Boolean {
        if (webSocketGeneration.isCurrent(connectionGeneration)) return true
        Log.d(TAG, "Ignoring stale WebSocket $callback for generation $connectionGeneration")
        return false
    }

    private fun disconnectByUser(startId: Int) {
        retryController.onExplicitDisconnect()
        isServiceRunning.set(false)
        isReconnecting.set(false)
        webSocketGeneration.clearReconnect()
        reconnectBackoff.reset()
        handler.removeCallbacksAndMessages(null)
        ScreenCaptureService.requestStop("user_disconnect")
        reverseMediaReleased = true
        disconnect()
        ConnectionStateManager.setState(ConnectionState.DISCONNECTED)
        // A newer queued start must still reach onStartCommand, and its sender owns the enabled flag.
        if (stopSelfResult(startId)) {
            configManager.reverseConnectionEnabled = false
        }
    }

    /** Stops the service after a terminal close so it is not left sticky in the foreground. */
    private fun stopAfterTerminalState(state: ConnectionState, connectionGeneration: Long) {
        handler.post {
            if (!webSocketGeneration.isCurrent(connectionGeneration)) {
                Log.d(TAG, "Skipping stale terminal stop for generation $connectionGeneration")
                return@post
            }
            terminalState = state
            isServiceRunning.set(false)
            cancelPendingReconnects()
            releaseReverseMedia("ws_disconnected")
            disconnect()
            stopSelf(lastStartId)
        }
    }

    private fun releaseReverseMedia(reason: String) {
        reverseMediaReleased = true
        val manager = WebRtcManager.getExistingInstance()
        if (manager == null) {
            ScreenCaptureService.requestStop(reason)
            return
        }
        val release = reverseMediaReleaseFor(
            captureServiceRunning = ScreenCaptureService.getInstance() != null,
            captureSessionMode = manager.getCaptureFastStateSnapshot().captureSessionMode,
            streamActive = manager.isStreamActive(),
        )
        Log.i(TAG, "Releasing reverse media ($reason): $release")
        when (release) {
            ReverseMediaRelease.STOP_CAPTURE -> ScreenCaptureService.requestStop(reason)
            ReverseMediaRelease.STOP_SESSIONS -> {
                manager.notifyStreamStoppedAsync(reason)
                manager.requestGracefulStop(reason)
            }

            ReverseMediaRelease.NONE -> {
                // Drop a stream request still waiting on the MediaProjection dialog.
                manager.setStreamRequestId(null)
                manager.consumePendingIceServers()
            }
        }
    }

    /**
     * After WS reconnect, check if capture survived but the peer connection died
     * during the outage. If so, notify the server so it can request a new stream
     * (which will reuse existing capture via startStreamWithExistingCapture).
     */
    private fun notifyStreamStateAfterReconnect(manager: WebRtcManager) {
        val hasAnySession = manager.getActiveSessionIds().isNotEmpty()
        if (shouldNotifyStreamStoppedAfterReconnect(
                manager.isCaptureActive(),
                manager.isStreamActive(),
                if (hasAnySession) "active" else null,
            )
        ) {
            // Use async variant — this runs on the WS receive thread
            manager.notifyStreamStoppedAsync("ws_reconnected")
        }
    }

    private fun logNetworkState(prefix: String) {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = connectivityManager?.activeNetwork
        val capabilities = connectivityManager?.getNetworkCapabilities(network)
        if (capabilities == null) {
            Log.d(TAG, "$prefix network=unknown")
            return
        }
        val transports = mutableListOf<String>()
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports.add("wifi")
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports.add("cellular")
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports.add("ethernet")
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transports.add("vpn")
        val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        Log.d(TAG, "$prefix network=${transports.joinToString(",")} validated=$validated")
    }

    /** [deferNotification] keeps an answer-only startForeground() from flashing the notification. */
    private fun ensureForeground(deferNotification: Boolean = false) {
        when (reverseForegroundActionFor(isForeground, foregroundSuppressed)) {
            ReverseForegroundAction.NONE -> Unit
            ReverseForegroundAction.START -> startForegroundNotification(deferNotification)
            ReverseForegroundAction.START_THEN_RELEASE -> {
                // Answer startForegroundService() even while capture owns the foreground.
                if (startForegroundNotification(deferred = true)) suspendForegroundForStreaming()
            }
        }
    }

    private fun startForegroundNotification(deferred: Boolean): Boolean {
        return try {
            val notification = createNotification(deferred)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
            false
        }
    }

    fun suspendForegroundForStreaming() {
        foregroundSuppressed = true
        if (!isForeground) return
        try {
            @Suppress("DEPRECATION")
            stopForeground(true)
            isForeground = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop foreground service", e)
        }
    }

    fun resumeForegroundAfterStreaming() {
        foregroundSuppressed = false
        ensureForeground()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Reverse Connection",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(deferred: Boolean): Notification {
        val intent = Intent(this, com.mobilerun.portal.ui.MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val disconnectIntent = Intent(this, ReverseConnectionService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            1,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.reverse_connection_service_title))
            .setContentText(getString(R.string.reverse_connection_service_text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.reverse_connection_disconnect_action),
                disconnectPendingIntent
            )
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setForegroundServiceBehavior(
                if (deferred) {
                    NotificationCompat.FOREGROUND_SERVICE_DEFERRED
                } else {
                    NotificationCompat.FOREGROUND_SERVICE_DEFAULT
                },
            )
            .build()
    }

    private fun showReverseConnectionToastIfEnoughTimeIsPassed() {
        val now = SystemClock.elapsedRealtime()
        if (lastReverseToastAtMs == 0L || now - lastReverseToastAtMs >= TOAST_DEBOUNCE_MS) {
            lastReverseToastAtMs = now
            handler.post {
                Toast.makeText(
                    this@ReverseConnectionService,
                    getString(R.string.reverse_connection_connected),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun handleMessage(client: WebSocketClient, message: String?) {
        if (message == null) return

        var id: Any? = null
        var methodForResponseLogging: String? = null

        try {
            // Check if the message is a valid JSON before parsing
            if (!message.trim().startsWith("{") && !message.trim().startsWith("[")) {
                Log.w(TAG, "Received non-JSON message: $message")
                return
            }

            val json = JSONObject(message)
            // Support both integer and string IDs (e.g., UUIDs)
            id = json.opt("id")?.takeIf { it != JSONObject.NULL }

            // Method may be empty for JSON-RPC responses to outgoing messages (e.g., webrtc/offer)
            val method = json.optString("method", "")
            val normalizedMethod =
                method.removePrefix("/action/").removePrefix("action.").removePrefix("/")
            methodForResponseLogging = normalizedMethod

            if (shouldRedactReverseRequestPayload(normalizedMethod)) {
                Log.d(TAG, "Received message: $normalizedMethod (id=$id, params=<redacted>)")
            } else {
                val logMsg = if (message.length > 200) message.take(200) + "..." else message
                Log.d(TAG, "Received message: $logMsg")
            }

            if (method.isEmpty()) {
                if (json.has("result")) {
                    Log.d(TAG, "Received JSON-RPC result for id=$id")
                } else if (json.has("error")) {
                    Log.w(TAG, "Received JSON-RPC error for id=$id: ${json.opt("error")}")
                } else {
                    Log.w(TAG, "Received message without method, result, or error: $message")
                }
                return
            }

            val params = json.optJSONObject("params") ?: JSONObject()

            // Truncate params log to avoid spamming with large SDP/ICE payloads
            val paramsLog = if (shouldRedactReverseRequestPayload(normalizedMethod)) {
                "<redacted>"
            } else {
                params.toString().let { if (it.length > 100) it.take(100) + "..." else it }
            }
            Log.d(TAG, "Dispatching $method (id=$id, params=$paramsLog)")

            val dispatcher = resolveActionDispatcher(normalizedMethod)
            if (dispatcher == null) {
                val error =
                    "Accessibility Service not ready. Only stream/*, webrtc/*, screen/keepAwake/*, global, and triggers/* are available."
                Log.e(TAG, error)
                sendErrorResponse(client, id, error, normalizedMethod)
                return
            }

            val requestId = id
            val dispatchStartedAtMs = SystemClock.elapsedRealtime()
            val executor =
                when (WebSocketDispatchPolicy.bucketForNormalizedMethod(normalizedMethod)) {
                    WebSocketDispatchBucket.SIGNALING -> signalingExecutor
                    WebSocketDispatchBucket.LIGHTWEIGHT -> lightweightExecutor
                    WebSocketDispatchBucket.COMMAND -> commandExecutor
                    WebSocketDispatchBucket.INSTALL -> installExecutor
                }
            executor.submit {
                dispatchAndRespond(
                    client = client,
                    dispatcher = dispatcher,
                    method = method,
                    normalizedMethod = normalizedMethod,
                    params = params,
                    origin = ActionDispatcher.Origin.WEBSOCKET_REVERSE,
                    requestId = requestId,
                    dispatchStartedAtMs = dispatchStartedAtMs,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing message", e)
            sendErrorResponse(
                client,
                id,
                e.message ?: "unknown exception",
                methodForResponseLogging,
            )
        }
    }

    private fun dispatchAndRespond(
        client: WebSocketClient,
        dispatcher: ActionDispatcher,
        method: String,
        normalizedMethod: String,
        params: JSONObject,
        origin: ActionDispatcher.Origin,
        requestId: Any?,
        dispatchStartedAtMs: Long,
    ) {
        try {
            val result = dispatcher.dispatch(
                method,
                params,
                origin = origin,
                requestId = requestId,
            )
            val elapsedMs = SystemClock.elapsedRealtime() - dispatchStartedAtMs
            Log.d(
                TAG,
                "Completed $method (id=$requestId, elapsedMs=$elapsedMs, result=${result.javaClass.simpleName})",
            )
            sendResponse(client, result, requestId, normalizedMethod)
        } catch (e: Exception) {
            val elapsedMs = SystemClock.elapsedRealtime() - dispatchStartedAtMs
            Log.e(TAG, "Failed $method (id=$requestId, elapsedMs=$elapsedMs)", e)
            sendErrorResponse(
                client,
                requestId,
                e.message ?: "unknown exception",
                normalizedMethod,
            )
        }
    }

    private fun sendResponse(
        client: WebSocketClient,
        response: ApiResponse,
        requestId: Any?,
        normalizedMethod: String? = null,
    ) {
        if (!client.isOpen) {
            Log.w(TAG, "Skipping response for closed reverse socket (id=$requestId)")
            return
        }
        try {
            val payload = response.toJson(requestId)
            client.send(payload)
            if (
                normalizedMethod != null &&
                shouldRedactReverseResponsePayload(normalizedMethod)
            ) {
                Log.d(TAG, "Sent response for $normalizedMethod (id=$requestId, payload=<redacted>)")
            } else {
                val logPayload = if (payload.length > 200) payload.take(200) + "..." else payload
                Log.d(TAG, "Sent response: $logPayload")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send response for id=$requestId", e)
        }
    }

    private fun sendErrorResponse(
        client: WebSocketClient,
        requestId: Any?,
        message: String,
        normalizedMethod: String? = null,
    ) {
        if (requestId == null) return
        sendResponse(client, ApiResponse.Error(message), requestId, normalizedMethod)
    }

    private fun buildHeadlessActionDispatcher(): ActionDispatcher {
        val apiHandler = ApiHandler(
            stateRepo = StateRepository(service = null),
            getKeyboardIME = { MobilerunKeyboardIME.getInstance() },
            getPackageManager = { packageManager },
            appVersionProvider = {
                try {
                    packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
                } catch (e: Exception) {
                    "unknown"
                }
            },
            context = this,
        )
        return ActionDispatcher(apiHandler)
    }

    private fun resolveActionDispatcher(normalizedMethod: String): ActionDispatcher? {
        val service = MobilerunAccessibilityService.getInstance()
        if (service != null) {
            return actionDispatcherCache.get(service) { currentService ->
                currentService.getActionDispatcher()
            }
        }
        actionDispatcherCache.clear()

        if (!HeadlessActionSupport.isAllowed(normalizedMethod)) {
            return null
        }

        headlessActionDispatcher?.let { return it }
        synchronized(this) {
            headlessActionDispatcher?.let { return it }
            return buildHeadlessActionDispatcher().also { headlessActionDispatcher = it }
        }
    }
}
