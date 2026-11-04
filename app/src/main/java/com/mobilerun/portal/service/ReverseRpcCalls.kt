package com.mobilerun.portal.service

import org.java_websocket.client.WebSocketClient
import org.json.JSONObject
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

internal sealed class ReverseRpcResult {
    data class Ok(val result: Any?) : ReverseRpcResult()
    data class Error(val message: String) : ReverseRpcResult()
    object Timeout : ReverseRpcResult()
    object Closed : ReverseRpcResult()
}

internal interface ReverseRpcTransport {
    val generation: Long
    fun send(text: String): Boolean
}

internal class ReverseRpcSocket(
    val client: WebSocketClient,
    override val generation: Long,
) : ReverseRpcTransport {
    override fun send(text: String): Boolean {
        if (!client.isOpen) return false
        return try {
            client.send(text)
            true
        } catch (_: Exception) {
            false
        }
    }
}

/** Device-to-relay calls. Callbacks run on the completing thread and must not block. */
internal class ReverseRpcCalls(
    private val transport: () -> ReverseRpcTransport?,
    private val schedule: (delayMs: Long, task: Runnable) -> Unit,
    private val nextId: () -> Long = ReverseRequestIds::next,
    private val log: (String) -> Unit = {},
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 20_000L
    }

    private class Pending(
        val method: String,
        val generation: Long,
        val callback: (ReverseRpcResult) -> Unit,
    )

    private val pending = ConcurrentHashMap<Long, Pending>()

    /** Returns the request id, or null when nothing was sent (the callback already got Closed). */
    fun call(
        method: String,
        params: JSONObject,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        callback: (ReverseRpcResult) -> Unit,
    ): Long? {
        val socket = transport()
        if (socket == null) {
            complete(Pending(method, -1L, callback), ReverseRpcResult.Closed)
            return null
        }
        val id = nextId()
        val call = Pending(method, socket.generation, callback)
        // Registered before sending so a fast reply finds it.
        pending[id] = call
        try {
            schedule(timeoutMs, Runnable {
                if (pending.remove(id, call)) complete(call, ReverseRpcResult.Timeout)
            })
        } catch (_: Exception) {
            if (pending.remove(id, call)) complete(call, ReverseRpcResult.Closed)
            return null
        }
        val frame = JSONObject().put("id", id).put("method", method).put("params", params)
        if (!socket.send(frame.toString())) {
            if (pending.remove(id, call)) complete(call, ReverseRpcResult.Closed)
            return null
        }
        return id
    }

    /** True when [frame] answered a pending call on the socket of [generation]. */
    fun onResponse(frame: JSONObject, generation: Long): Boolean {
        val id = reverseResponseId(frame.opt("id")) ?: return false
        val call = pending[id] ?: return false
        if (call.generation != generation || !pending.remove(id, call)) return false
        val result = if (frame.has("error") && !frame.isNull("error")) {
            val error = frame.get("error")
            ReverseRpcResult.Error(error as? String ?: error.toString())
        } else {
            ReverseRpcResult.Ok(frame.opt("result")?.takeUnless { it == JSONObject.NULL })
        }
        complete(call, result)
        return true
    }

    /** Fails the calls of [generation] and older generations with Closed. */
    fun failAll(generation: Long) {
        for ((id, call) in pending) {
            if (call.generation <= generation && pending.remove(id, call)) {
                complete(call, ReverseRpcResult.Closed)
            }
        }
    }

    fun pendingCount(): Int = pending.size

    /** True while a current socket is there to call over. */
    fun isOpen(): Boolean = transport() != null

    private fun complete(call: Pending, result: ReverseRpcResult) {
        try {
            call.callback(result)
        } catch (e: Exception) {
            log("Reverse call ${call.method} callback failed: ${e.javaClass.simpleName}")
        }
    }
}

internal fun reverseResponseId(raw: Any?): Long? = when (raw) {
    is Int -> raw.toLong()
    is Long -> raw
    is Number -> try {
        BigDecimal(raw.toString()).longValueExact()
    } catch (_: Exception) {
        null
    }

    is String -> raw.toLongOrNull()
    else -> null
}
