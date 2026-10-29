package com.mobilerun.portal.sms

import android.content.Context
import com.mobilerun.portal.api.ApiResponse
import com.mobilerun.portal.config.ConfigManager
import org.json.JSONObject

class SmsController(
    context: Context,
    private val gateway: SmsGateway = SmsGateway(
        stateStore = SmsStateRegistry.store,
        sender = AndroidSmsSender(context.applicationContext),
        subscriptionResolver = SmsSubscriptionResolver(context.applicationContext),
    ),
    private val reader: SmsReader = SmsReader.forContext(context.applicationContext),
    private val gatewayEnabled: () -> Boolean = {
        ConfigManager.getInstance(context.applicationContext).smsGatewayEnabled
    },
) {
    fun capabilities(): ApiResponse {
        val result = gateway.capabilities()
        result.json?.put("gateway_enabled", gatewayEnabled())
        return result.toApiResponse()
    }

    fun send(params: JSONObject): ApiResponse = whenEnabled { gateway.send(params) }

    fun status(params: JSONObject): ApiResponse = whenEnabled { gateway.status(params) }

    fun listMessages(params: JSONObject): ApiResponse = whenEnabled { reader.listMessages(params) }

    fun getMessage(params: JSONObject): ApiResponse = whenEnabled { reader.getMessage(params) }

    private fun whenEnabled(block: () -> SmsJsonResult): ApiResponse {
        if (!gatewayEnabled()) return ApiResponse.Error(GATEWAY_DISABLED)
        return block().toApiResponse()
    }

    private fun SmsJsonResult.toApiResponse(): ApiResponse {
        return if (ok && json != null) {
            ApiResponse.RawObject(json)
        } else {
            ApiResponse.Error(error ?: "SMS operation failed")
        }
    }

    companion object {
        const val GATEWAY_DISABLED = "SMS gateway is disabled in Portal settings"
    }
}
