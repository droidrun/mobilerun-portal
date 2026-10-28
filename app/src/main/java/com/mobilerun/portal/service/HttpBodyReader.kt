package com.mobilerun.portal.service

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.json.JSONObject

internal const val MAX_HTTP_POST_BODY_BYTES = 1_048_576
internal const val MAX_HTTP_FILE_UPLOAD_POST_BODY_BYTES = 24 * 1024 * 1024

internal class HttpPayloadTooLargeException(message: String) : IllegalArgumentException(message)

internal fun maxHttpPostBodyBytesForPath(path: String): Int {
    val normalizedPath = path.substringBefore("?").removePrefix("/").removePrefix("action/")
    return if (normalizedPath == "files/upload") {
        MAX_HTTP_FILE_UPLOAD_POST_BODY_BYTES
    } else {
        MAX_HTTP_POST_BODY_BYTES
    }
}

internal fun parseHttpContentLength(
    value: String,
    maxBodyBytes: Int = MAX_HTTP_POST_BODY_BYTES,
): Int {
    val contentLength = value.trim().toLongOrNull()
        ?: throw IllegalArgumentException("Invalid Content-Length")
    if (contentLength < 0) {
        throw IllegalArgumentException("Invalid Content-Length")
    }
    if (contentLength > maxBodyBytes) {
        throw HttpPayloadTooLargeException("HTTP POST body too large: $contentLength bytes")
    }
    return contentLength.toInt()
}

internal fun httpReasonPhrase(code: Int): String = when (code) {
    400 -> "Bad Request"
    401 -> "Unauthorized"
    413 -> "Payload Too Large"
    503 -> "Service Unavailable"
    else -> "Error"
}

internal fun readHttpLine(inputStream: InputStream): String? {
    val buffer = ByteArrayOutputStream()
    while (true) {
        val byte = inputStream.read()
        if (byte == -1) {
            return if (buffer.size() == 0) null else buffer.toString(StandardCharsets.ISO_8859_1.name())
        }
        if (byte == '\n'.code) {
            return buffer.toString(StandardCharsets.ISO_8859_1.name())
        }
        if (byte != '\r'.code) {
            buffer.write(byte)
        }
    }
}

internal fun readHttpRequestBody(
    inputStream: InputStream,
    contentLength: Int,
    maxBodyBytes: Int = MAX_HTTP_POST_BODY_BYTES,
): String {
    if (contentLength <= 0) return ""
    if (contentLength > maxBodyBytes) {
        throw HttpPayloadTooLargeException("HTTP POST body too large: $contentLength bytes")
    }

    val buffer = ByteArray(contentLength)
    var offset = 0
    while (offset < contentLength) {
        val read = inputStream.read(buffer, offset, contentLength - offset)
        if (read == -1) {
            throw EOFException("Unexpected EOF while reading HTTP body: expected $contentLength bytes, got $offset")
        }
        offset += read
    }
    return String(buffer, 0, offset, StandardCharsets.UTF_8)
}

internal fun decodeFormComponent(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8.name())

internal fun parseFormUrlEncoded(data: String): List<Pair<String, String>> {
    if (data.isBlank()) return emptyList()
    return data.split("&").filter { it.isNotEmpty() }.map { pair ->
        val parts = pair.split("=", limit = 2)
        val key = decodeFormComponent(parts[0])
        val value = if (parts.size == 2) decodeFormComponent(parts[1]) else ""
        key to value
    }
}

internal fun normalizeJsonPostValue(key: String, value: Any): Any {
    if (value == JSONObject.NULL) {
        throw IllegalArgumentException("JSON null is not allowed for '$key'")
    }
    return value
}
