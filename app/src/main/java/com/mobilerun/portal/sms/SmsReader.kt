package com.mobilerun.portal.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import androidx.core.content.ContextCompat
import org.json.JSONObject

interface SmsMessageSource {
    fun listMessages(request: SmsListRequest): List<SmsStoredMessage>
    fun getMessage(smsId: String): SmsStoredMessage?
}

class SmsReader(
    private val permissionChecker: () -> Boolean,
    private val source: SmsMessageSource,
) {
    fun listMessages(params: JSONObject): SmsReadResponse {
        if (!permissionChecker()) {
            return SmsReadResponse.error("READ_SMS permission not granted")
        }
        return try {
            val request = SmsListRequest.fromJson(params)
            val messages = source.listMessages(request)
            SmsReadResponse.success(
                JSONObject()
                    .put("box", request.box.wireName)
                    .put("limit", request.limit)
                    .put("messages", messages.storedMessagesToJsonArray()),
            )
        } catch (e: IllegalArgumentException) {
            SmsReadResponse.error(e.message ?: "Invalid SMS list request")
        } catch (e: Exception) {
            SmsReadResponse.error("Failed to read SMS messages: ${e.message}")
        }
    }

    fun getMessage(params: JSONObject): SmsReadResponse {
        if (!permissionChecker()) {
            return SmsReadResponse.error("READ_SMS permission not granted")
        }
        val smsId = params.optString("sms_id").trim()
        if (smsId.isBlank()) {
            return SmsReadResponse.error("Missing required param: 'sms_id'")
        }
        return try {
            val message = source.getMessage(smsId)
                ?: return SmsReadResponse.error("SMS message not found: $smsId")
            SmsReadResponse.success(message.toJson())
        } catch (e: Exception) {
            SmsReadResponse.error("Failed to read SMS message: ${e.message}")
        }
    }

    companion object {
        fun forContext(context: Context): SmsReader {
            val appContext = context.applicationContext
            return SmsReader(
                permissionChecker = {
                    ContextCompat.checkSelfPermission(
                        appContext,
                        Manifest.permission.READ_SMS,
                    ) == PackageManager.PERMISSION_GRANTED
                },
                source = AndroidSmsMessageSource(appContext),
            )
        }
    }
}

class AndroidSmsMessageSource(
    private val context: Context,
) : SmsMessageSource {
    override fun listMessages(request: SmsListRequest): List<SmsStoredMessage> {
        val selection = mutableListOf<String>()
        val args = mutableListOf<String>()
        request.since?.let {
            selection += "${Telephony.Sms.DATE} >= ?"
            args += it.toString()
        }
        request.phoneNumber?.let {
            selection += "${Telephony.Sms.ADDRESS} = ?"
            args += it
        }
        return queryMessages(
            uri = uriForBox(request.box),
            selection = selection.joinToString(" AND ").ifBlank { null },
            selectionArgs = args.takeIf { it.isNotEmpty() }?.toTypedArray(),
            sortOrder = "${Telephony.Sms.DATE} DESC LIMIT ${request.limit}",
            fallbackBox = request.box,
        )
    }

    override fun getMessage(smsId: String): SmsStoredMessage? {
        return queryMessages(
            uri = Telephony.Sms.CONTENT_URI,
            selection = "${Telephony.Sms._ID} = ?",
            selectionArgs = arrayOf(smsId),
            sortOrder = null,
            fallbackBox = SmsBox.ALL,
        ).firstOrNull()
    }

    private fun queryMessages(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
        fallbackBox: SmsBox,
    ): List<SmsStoredMessage> {
        val cursor = context.contentResolver.query(
            uri,
            PROJECTION,
            selection,
            selectionArgs,
            sortOrder,
        ) ?: return emptyList()
        cursor.use {
            val result = mutableListOf<SmsStoredMessage>()
            while (it.moveToNext()) {
                result += it.toStoredMessage(fallbackBox)
            }
            return result
        }
    }

    private fun uriForBox(box: SmsBox): Uri {
        return when (box) {
            SmsBox.ALL -> Telephony.Sms.CONTENT_URI
            SmsBox.INBOX -> Telephony.Sms.Inbox.CONTENT_URI
            SmsBox.SENT -> Telephony.Sms.Sent.CONTENT_URI
        }
    }

    private fun Cursor.toStoredMessage(fallbackBox: SmsBox): SmsStoredMessage {
        val type = getOptionalInt(Telephony.Sms.TYPE)
        return SmsStoredMessage(
            smsId = getString(getColumnIndexOrThrow(Telephony.Sms._ID)),
            box = boxFromType(type) ?: fallbackBox,
            phoneNumber = getOptionalString(Telephony.Sms.ADDRESS).orEmpty(),
            message = getOptionalString(Telephony.Sms.BODY).orEmpty(),
            date = getOptionalLong(Telephony.Sms.DATE) ?: 0L,
            dateSent = getOptionalLong(Telephony.Sms.DATE_SENT),
            read = getOptionalInt(Telephony.Sms.READ)?.let { it != 0 },
            threadId = getOptionalString(Telephony.Sms.THREAD_ID),
            subscriptionId = getOptionalInt(COLUMN_SUBSCRIPTION_ID),
        )
    }

    private fun boxFromType(type: Int?): SmsBox? {
        return when (type) {
            Telephony.Sms.MESSAGE_TYPE_INBOX -> SmsBox.INBOX
            Telephony.Sms.MESSAGE_TYPE_SENT -> SmsBox.SENT
            else -> null
        }
    }

    private fun Cursor.getOptionalString(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun Cursor.getOptionalLong(column: String): Long? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getLong(index) else null
    }

    private fun Cursor.getOptionalInt(column: String): Int? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getInt(index) else null
    }

    companion object {
        private val PROJECTION = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.DATE_SENT,
            Telephony.Sms.READ,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.TYPE,
            Telephony.Sms.SUBSCRIPTION_ID,
        )
        private const val COLUMN_SUBSCRIPTION_ID = Telephony.Sms.SUBSCRIPTION_ID
    }
}
