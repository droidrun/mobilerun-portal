package com.mobilerun.portal.sms

import android.content.Context
import com.mobilerun.portal.api.ApiResponse
import org.json.JSONObject

class SmsController(
    context: Context,
    private val gateway: SmsGateway = SmsGateway(
        stateStore = SmsStateRegistry.store,
        sender = AndroidSmsSender(context.applicationContext),
        subscriptionResolver = SmsSubscriptionResolver(context.applicationContext),
    ),
    private val reader: SmsReader = SmsReader.forContext(context.applicationContext),
) {
    fun capabilities(): ApiResponse = gateway.capabilities().toApiResponse()

    fun send(params: JSONObject): ApiResponse = gateway.send(params).toApiResponse()

    fun status(params: JSONObject): ApiResponse = gateway.status(params).toApiResponse()

    fun listMessages(params: JSONObject): ApiResponse = reader.listMessages(params).toApiResponse()

    fun getMessage(params: JSONObject): ApiResponse = reader.getMessage(params).toApiResponse()

    private fun SmsJsonResult.toApiResponse(): ApiResponse {
        return if (ok && json != null) {
            ApiResponse.RawObject(json)
        } else {
            ApiResponse.Error(error ?: "SMS operation failed")
        }
    }
}
