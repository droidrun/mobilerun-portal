package com.mobilerun.portal.taskprompt

import io.mockk.every
import io.mockk.mockk
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PortalCloudClientBodyReadTest {

    private val restBaseUrl = "https://api.example.test/v1"

    /** A 200 response whose body fails while it is read, like a connection reset mid-body. */
    private fun clientWithFailingBody(): PortalCloudClient {
        val call = mockk<Call>()
        val okHttpClient = mockk<OkHttpClient>()
        every { okHttpClient.newCall(any()) } returns call
        every { call.enqueue(any()) } answers {
            val request = Request.Builder().url("$restBaseUrl/tasks").build()
            val body = object : ResponseBody() {
                override fun contentType() = "application/json".toMediaType()
                override fun contentLength() = 64L
                override fun source(): BufferedSource = object : Source {
                    override fun read(sink: Buffer, byteCount: Long): Long =
                        throw IOException("unexpected end of stream")

                    override fun timeout() = Timeout.NONE
                    override fun close() = Unit
                }.buffer()
            }
            val response = Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body)
                .build()
            firstArg<Callback>().onResponse(call, response)
        }
        return PortalCloudClient(okHttpClient)
    }

    @Test
    fun getTaskStatus_reportsErrorWhenBodyReadFails() {
        var result: PortalTaskStatusResult? = null
        clientWithFailingBody().getTaskStatus(restBaseUrl, "token", "task-1") { result = it }

        assertTrue(result is PortalTaskStatusResult.Error)
    }

    @Test
    fun getTask_reportsErrorWhenBodyReadFails() {
        var result: PortalTaskDetailsResult? = null
        clientWithFailingBody().getTask(restBaseUrl, "token", "task-1") { result = it }

        assertTrue(result is PortalTaskDetailsResult.Error)
    }

    @Test
    fun listTasks_reportsErrorWhenBodyReadFails() {
        var result: PortalTaskHistoryResult? = null
        clientWithFailingBody().listTasks(restBaseUrl, "token", null, 1, 20) { result = it }

        assertTrue(result is PortalTaskHistoryResult.Error)
    }
}
