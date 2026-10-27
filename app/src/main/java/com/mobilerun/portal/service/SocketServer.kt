package com.mobilerun.portal.service

import android.content.ContentValues
import android.net.Uri
import android.util.Log
import com.mobilerun.portal.api.ApiHandler
import com.mobilerun.portal.api.ApiResponse
import org.json.JSONObject
import com.mobilerun.portal.config.ConfigManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** application/x-www-form-urlencoded: '+' is a space, "%2B" is a literal '+'. */
internal fun decodeFormComponent(value: String): String = Uri.decode(value.replace('+', ' '))

class SocketServer(
    private val apiHandler: ApiHandler,
    private val configManager: ConfigManager,
    private val actionDispatcher: ActionDispatcher,
) {
    companion object {
        private const val TAG = "MobilerunSocketServer"
        private const val DEFAULT_PORT = 8080
        // Request handlers run on a bounded pool that is SEPARATE from the
        // acceptor thread, so a few slow/wedged handlers can't starve accept()
        // (which would otherwise let /ping die along with /state).
        private const val HANDLER_POOL_SIZE = 8
        private const val HANDLER_QUEUE_CAPACITY = 32
        private const val CLIENT_SOCKET_TIMEOUT_MS = 15_000
        private const val ACCEPT_TIMEOUT_MS = 1_000
        private const val HTTP_STATUS_OK = 200
        private const val HTTP_REASON_OK = "OK"
        private const val HTTP_STATUS_SERVICE_UNAVAILABLE = 503
        private const val HTTP_STATUS_BAD_REQUEST = 400
        private const val HTTP_STATUS_UNAUTHORIZED = 401
        private const val AUTHORIZATION_HEADER_PREFIX = "Authorization:"
        private const val BEARER_PREFIX = "Bearer "
        private const val POST_BODY_BUFFER_SIZE = 1024
        private const val UNAUTHORIZED = "Unauthorized"
    }

    private var serverSocket: ServerSocket? = null
    private var isRunning = AtomicBoolean(false)
    private var executorService: ExecutorService? = null
    private var acceptorThread: Thread? = null
    private var port: Int = DEFAULT_PORT

    fun start(port: Int = DEFAULT_PORT): Boolean {
        if (isRunning.get()) {
            Log.w(TAG, "Server already running on port ${this.port}")
            return true
        }

        this.port = port
        Log.i(TAG, "Starting socket server on port $port...")

        return try {
            // Bounded handler pool: bounded queue + a CallerRuns-free reject so a
            // request burst surfaces a 503 instead of growing an unbounded queue.
            executorService = java.util.concurrent.ThreadPoolExecutor(
                HANDLER_POOL_SIZE,
                HANDLER_POOL_SIZE,
                0L,
                java.util.concurrent.TimeUnit.MILLISECONDS,
                java.util.concurrent.ArrayBlockingQueue(HANDLER_QUEUE_CAPACITY),
                java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
            )
            serverSocket = ServerSocket(port).apply { soTimeout = ACCEPT_TIMEOUT_MS }
            isRunning.set(true)

            // Accept on a DEDICATED thread, never on the handler pool, so handlers
            // can never starve the acceptor.
            acceptorThread = Thread({ acceptConnections() }, "socket-acceptor").apply {
                isDaemon = true
                start()
            }

            Log.i(TAG, "Socket server started successfully on port $port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start socket server on port $port", e)
            isRunning.set(false)
            executorService?.shutdownNow()
            executorService = null
            false
        }
    }

    fun stop() {
        if (!isRunning.get()) return

        isRunning.set(false)

        try {
            serverSocket?.close()
            acceptorThread?.interrupt()
            acceptorThread = null
            executorService?.shutdownNow()
            executorService = null
            Log.i(TAG, "Socket server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping socket server", e)
        }
    }

    fun isRunning(): Boolean = isRunning.get()
    fun getPort(): Int = port

    private fun acceptConnections() {
        while (isRunning.get()) {
            try {
                val clientSocket = try {
                    serverSocket?.accept() ?: break
                } catch (e: java.net.SocketTimeoutException) {
                    continue // periodic wake to re-check isRunning
                }
                // Bound per-request reads so a stuck client/handler can't own a
                // worker forever.
                try { clientSocket.soTimeout = CLIENT_SOCKET_TIMEOUT_MS } catch (_: Exception) {}
                try {
                    executorService?.submit { handleClient(clientSocket) }
                } catch (e: java.util.concurrent.RejectedExecutionException) {
                    // Pool + queue full: fail fast with 503 instead of blocking.
                    Log.w(TAG, "handler pool saturated; rejecting request with 503")
                    try {
                        sendErrorResponse(
                            clientSocket.getOutputStream(),
                            HTTP_STATUS_SERVICE_UNAVAILABLE,
                            "Server busy",
                        )
                    } catch (_: Exception) {} finally {
                        try { clientSocket.close() } catch (_: Exception) {}
                    }
                }
            } catch (e: SocketException) {
                if (isRunning.get()) {
                    Log.e(TAG, "Socket exception while accepting connections", e)
                }
                break
            } catch (e: Exception) {
                Log.e(TAG, "Error accepting connection", e)
            }
        }
    }

    private fun handleClient(clientSocket: Socket) {
        try {
            clientSocket.use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val outputStream = socket.getOutputStream()

                val requestLine = reader.readLine()
                if (requestLine == null) return

                val parts = requestLine.split(" ")
                if (parts.size < 2) {
                    sendErrorResponse(outputStream, HTTP_STATUS_BAD_REQUEST, "Bad Request")
                    return
                }

                val method = parts[0]
                val path = parts[1]

                var authToken: String? = null

                // Consume headers
                var line = reader.readLine()
                var contentLength = 0
                
                while (!line.isNullOrEmpty()) {
                    if (line.startsWith(AUTHORIZATION_HEADER_PREFIX, ignoreCase = true)) {
                        authToken = line.substring(14).trim().removePrefix(BEARER_PREFIX).trim()
                    } else if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substring(15).trim().toIntOrNull() ?: 0
                    }
                    line = reader.readLine()
                }

                // Validate Auth Token (Skip for ping, but safer to require for all because of ping attacks)
                // For now, /ping without auth for easier connectivity checks
                if (path != "/ping" && authToken != configManager.authToken) {
                    sendErrorResponse(outputStream, HTTP_STATUS_UNAUTHORIZED, UNAUTHORIZED)
                    return
                }

                when (method) {
                    "GET" -> handleGetRequest(path, outputStream)
                    "POST" -> {
                        handlePostRequest(path, reader, outputStream, contentLength)
                    }
                    else -> sendHttpResponse(
                        outputStream,
                        ApiResponse.Error("Method not allowed: $method").toJson(),
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client", e)
        }
    }

    private fun handleGetRequest(path: String, outputStream: OutputStream) {
        try {
            // Special handling for legacy endpoints that map to dispatcher
            // This project reuses dispatcher for everything now
            // Dispatcher expects "method" name, we have path.
            // Dispatcher handles /action/ prefix or raw names.
            // Legacy GETs like /state need to be mapped.

            // TODO test it because Dispatcher is designed for RPC (method + params).
            // Legacy GETs don't fit perfectly. 
            // I need Binary only for handling for /screenshot.
            val response = when {
                path.startsWith("/screenshot") -> {
                    val hideOverlay = if (path.contains("?")) {
                        !path.contains("hideOverlay=false")
                    } else true
                    val params = JSONObject()
                    params.put("hideOverlay", hideOverlay)
                    actionDispatcher.dispatch("screenshot", params, ActionDispatcher.Origin.HTTP)
                }
                path.startsWith("/clipboard/get") -> {
                    actionDispatcher.dispatch("clipboard/get", JSONObject(), ActionDispatcher.Origin.HTTP)
                }
                // Fallback to legacy logic for other endpoints for now
                // or map them to dispatcher if possible

                // The legacy logic
                path.startsWith("/ping") -> apiHandler.ping()
                path.startsWith("/a11y_tree_full") -> apiHandler.getTreeFull(parseFilterParam(path))
                path.startsWith("/a11y_tree") -> apiHandler.getTree()
                path.startsWith("/state_full") -> apiHandler.getStateFull(parseFilterParam(path))
                path.startsWith("/state") -> apiHandler.getState()
                path.startsWith("/phone_state") -> apiHandler.getPhoneState()
                path.startsWith("/version") -> apiHandler.getVersion()
                path.startsWith("/packages") -> apiHandler.getPackages()
                else -> ApiResponse.Error("Unknown endpoint: $path")
            }

            if (response is ApiResponse.Binary) {
                sendBinaryResponse(outputStream, response.data, "image/png")
            } else {
                sendHttpResponse(outputStream, response.toJson())
            }
        } catch (e: Exception) {
            sendHttpResponse(
                outputStream,
                ApiResponse.Error("Internal server error: ${e.message}").toJson(),
            )
        }
    }

    private fun handlePostRequest(
        path: String,
        reader: BufferedReader,
        outputStream: OutputStream,
        contentLength: Int,
    ) {
        try {
            val postData = StringBuilder()
            val charBuffer = CharArray(POST_BODY_BUFFER_SIZE)
            var totalRead = 0
            
            while (totalRead < contentLength) {
                val toRead = minOf(charBuffer.size, contentLength - totalRead)
                val read = reader.read(charBuffer, 0, toRead)
                if (read == -1) break 
                postData.append(charBuffer, 0, read)
                totalRead += read
            }

            // Convert ContentValues to JSONObject for Dispatcher
            val values = parsePostData(postData.toString())
            val params = JSONObject()
            values.keySet().forEach { key ->
                val value = values.get(key)
                params.put(key, value)
            }

            val response = actionDispatcher.dispatch(path, params, ActionDispatcher.Origin.HTTP)

            if (response is ApiResponse.Binary) {
                sendBinaryResponse(outputStream, response.data, "application/octet-stream")
            } else {
                sendHttpResponse(outputStream, response.toJson())
            }
        } catch (e: Exception) {
            sendHttpResponse(
                outputStream,
                ApiResponse.Error("Internal server error: ${e.message}").toJson(),
            )
        }
    }

    // TODO put in consts
    private fun sendBinaryResponse(
        outputStream: OutputStream,
        data: ByteArray,
        contentType: String,
    ) {
        try {
            val headers = "HTTP/1.1 $HTTP_STATUS_OK $HTTP_REASON_OK\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: ${data.size}\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
            outputStream.write(headers.toByteArray(Charsets.UTF_8))
            outputStream.write(data)
            outputStream.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending Binary response", e)
        }
    }

    private fun parsePostData(data: String): ContentValues {
        val values = ContentValues()
        if (data.isBlank()) return values

        try {
            if (data.trim().startsWith("{")) {
                val json = JSONObject(data)
                json.keys().forEach { key ->
                    val value = json.get(key)
                    when (value) {
                        is String -> values.put(key, value)
                        is Int -> values.put(key, value)
                        is Boolean -> values.put(key, value)
                        else -> values.put(key, value.toString())
                    }
                }
            } else {
                data.split("&").forEach { pair ->
                    val parts = pair.split("=", limit = 2)
                    if (parts.size == 2) {
                        val key = decodeFormComponent(parts[0])
                        val value = decodeFormComponent(parts[1])
                        val intValue = value.toIntOrNull()
                        if (intValue != null) {
                            values.put(key, intValue)
                        } else {
                            when (value.lowercase()) {
                                "true" -> values.put(key, true)
                                "false" -> values.put(key, false)
                                else -> values.put(key, value)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing POST data", e)
        }
        return values
    }

    private fun parseFilterParam(path: String): Boolean {
        if (!path.contains("?")) return true
        val queryString = path.substringAfter("?")
        return !queryString.contains("filter=false")
    }

    private fun sendHttpResponse(outputStream: OutputStream, response: String) {
        try {
            val responseBytes = response.toByteArray(Charsets.UTF_8)
            val headers = "HTTP/1.1 $HTTP_STATUS_OK $HTTP_REASON_OK\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${responseBytes.size}\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
            outputStream.write(headers.toByteArray(Charsets.UTF_8))
            outputStream.write(responseBytes)
            outputStream.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending HTTP response", e)
        }
    }

    private fun sendErrorResponse(outputStream: OutputStream, code: Int, message: String) {
        // Implementation similar to previous, but maybe using ApiResponse if possible, 
        // though this is for protocol-level errors (400, 500)
        try {
            val errorResponse = ApiResponse.Error(message).toJson()
            val responseBytes = errorResponse.toByteArray(Charsets.UTF_8)
            val headers = "HTTP/1.1 $code $message\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${responseBytes.size}\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
            outputStream.write(headers.toByteArray(Charsets.UTF_8))
            outputStream.write(responseBytes)
            outputStream.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending error response", e)
        }
    }
}
