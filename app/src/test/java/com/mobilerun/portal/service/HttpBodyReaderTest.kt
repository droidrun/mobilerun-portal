package com.mobilerun.portal.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets

class HttpBodyReaderTest {
    @Test
    fun readHttpRequestBody_usesContentLengthAsUtf8ByteCount() {
        val body = "phone_number=%2B15550001111&message=Salam+🌍&message_id=utf8"
        val bytes = body.toByteArray(StandardCharsets.UTF_8)

        val decoded = readHttpRequestBody(ByteArrayInputStream(bytes), bytes.size)

        assertEquals(body, decoded)
    }

    @Test
    fun readHttpRequestBody_rejectsOversizedContentLengthBeforeAllocation() {
        try {
            readHttpRequestBody(
                ByteArrayInputStream(ByteArray(0)),
                contentLength = MAX_HTTP_POST_BODY_BYTES + 1,
            )
        } catch (e: IllegalArgumentException) {
            assertEquals(
                "HTTP POST body too large: ${MAX_HTTP_POST_BODY_BYTES + 1} bytes",
                e.message,
            )
            return
        }
        throw AssertionError("Expected oversized body to be rejected")
    }

    @Test
    fun readHttpRequestBody_rejectsPrematureEof() {
        try {
            readHttpRequestBody(ByteArrayInputStream("abc".toByteArray()), contentLength = 4)
        } catch (e: EOFException) {
            assertEquals("Unexpected EOF while reading HTTP body: expected 4 bytes, got 3", e.message)
            return
        }
        throw AssertionError("Expected premature EOF to be rejected")
    }

    @Test
    fun readHttpLine_consumesCrLfWithoutEatingBodyBytes() {
        val body = "message=Salam+🌍"
        val raw = "POST /sms/send HTTP/1.1\r\nContent-Length: ${body.toByteArray(StandardCharsets.UTF_8).size}\r\n\r\n$body"
            .toByteArray(StandardCharsets.UTF_8)
        val input = ByteArrayInputStream(raw)

        assertEquals("POST /sms/send HTTP/1.1", readHttpLine(input))
        assertEquals("Content-Length: ${body.toByteArray(StandardCharsets.UTF_8).size}", readHttpLine(input))
        assertEquals("", readHttpLine(input))
        assertEquals(body, readHttpRequestBody(input, body.toByteArray(StandardCharsets.UTF_8).size))
    }

    @Test
    fun decodeFormComponent_decodesPlusAsSpaceAndEscapedPlusAsPlus() {
        assertEquals("hello world", decodeFormComponent("hello+world"))
        assertEquals("+15550001111", decodeFormComponent("%2B15550001111"))
        assertEquals("a b+c", decodeFormComponent("a%20b%2Bc"))
        assertEquals("  https://example.com/a", decodeFormComponent("++https%3A%2F%2Fexample.com%2Fa"))
        assertEquals("SGk+Lw==", decodeFormComponent("SGk%2BLw%3D%3D"))
    }

    @Test
    fun parseFormUrlEncoded_preservesLeadingZerosAndRejectsMalformedEscapes() {
        assertEquals(
            listOf("phone_number" to "0501234567", "message" to "hello world"),
            parseFormUrlEncoded("phone_number=0501234567&message=hello+world"),
        )

        try {
            parseFormUrlEncoded("phone_number=%2B1555&message=hi&sim_slot=%ZZ")
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected malformed percent escape to be rejected")
    }

    @Test
    fun parseHttpContentLength_rejectsMalformedNegativeAndOversizedValues() {
        assertEquals(42, parseHttpContentLength(" 42 "))

        listOf("abc", "-1").forEach { raw ->
            try {
                parseHttpContentLength(raw)
            } catch (e: IllegalArgumentException) {
                assertEquals("Invalid Content-Length", e.message)
                return@forEach
            }
            throw AssertionError("Expected '$raw' Content-Length to be rejected")
        }

        try {
            parseHttpContentLength("9999999999999")
        } catch (e: HttpPayloadTooLargeException) {
            assertTrue(e.message!!.contains("HTTP POST body too large"))
            return
        }
        throw AssertionError("Expected oversized Content-Length to be rejected")
    }

    @Test
    fun parseHttpContentLength_acceptsCustomUploadCapAboveDefaultLimit() {
        val contentLength = MAX_HTTP_POST_BODY_BYTES + 1

        assertEquals(
            contentLength,
            parseHttpContentLength(
                contentLength.toString(),
                maxBodyBytes = MAX_HTTP_FILE_UPLOAD_POST_BODY_BYTES,
            ),
        )
    }

    @Test
    fun parseHttpContentLength_rejectsValuesAboveCustomUploadCap() {
        try {
            parseHttpContentLength(
                (MAX_HTTP_FILE_UPLOAD_POST_BODY_BYTES + 1).toString(),
                maxBodyBytes = MAX_HTTP_FILE_UPLOAD_POST_BODY_BYTES,
            )
        } catch (e: HttpPayloadTooLargeException) {
            assertTrue(e.message!!.contains("HTTP POST body too large"))
            return
        }
        throw AssertionError("Expected value above upload cap to be rejected")
    }

    @Test
    fun maxHttpPostBodyBytesForPath_raisesLimitOnlyForFilesUpload() {
        assertEquals(MAX_HTTP_POST_BODY_BYTES, maxHttpPostBodyBytesForPath("/sms/send"))
        assertEquals(MAX_HTTP_POST_BODY_BYTES, maxHttpPostBodyBytesForPath("/state"))
        assertEquals(MAX_HTTP_FILE_UPLOAD_POST_BODY_BYTES, maxHttpPostBodyBytesForPath("/files/upload"))
        assertEquals(MAX_HTTP_FILE_UPLOAD_POST_BODY_BYTES, maxHttpPostBodyBytesForPath("files/upload"))
    }

    @Test
    fun httpReasonPhrase_returnsFixedReasonForErrorStatus() {
        assertEquals("Bad Request", httpReasonPhrase(400))
        assertEquals("Payload Too Large", httpReasonPhrase(413))
        assertEquals("Service Unavailable", httpReasonPhrase(503))
        assertEquals("Error", httpReasonPhrase(599))
    }

    @Test
    fun normalizeJsonPostValue_rejectsJsonNull() {
        try {
            normalizeJsonPostValue("message", JSONObject.NULL)
        } catch (e: IllegalArgumentException) {
            assertEquals("JSON null is not allowed for 'message'", e.message)
            return
        }
        throw AssertionError("Expected JSON null to be rejected")
    }
}
