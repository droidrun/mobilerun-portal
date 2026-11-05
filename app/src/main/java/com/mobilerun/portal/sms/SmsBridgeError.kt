package com.mobilerun.portal.sms

import org.json.JSONObject

/** An SMS bridge failure, rendered "sms: <code>[: <detail>]". Details never carry a body or an address. */
data class SmsBridgeError(val code: String, val detail: String? = null) {
    val wire: String
        get() = if (detail.isNullOrEmpty()) "sms: $code" else "sms: $code: $detail"

    companion object {
        const val INVALID_PARAMS = "invalid_params"
        const val UNSUPPORTED_PARAM = "unsupported_param"
        const val NOT_FOUND = "not_found"
        const val PERMISSION_DENIED = "permission_denied"
        const val TELEPHONY_UNAVAILABLE = "telephony_unavailable"
        const val DEVICE_SIDE_FAILURE = "device_side_failure"

        val CLOUD_SMS_DISABLED = SmsBridgeError(PERMISSION_DENIED, "cloud_sms_disabled")
        val READ_SMS_DENIED = SmsBridgeError(PERMISSION_DENIED, "read_sms")
        val SEND_SMS_DENIED = SmsBridgeError(PERMISSION_DENIED, "send_sms")
        val READ_PHONE_STATE_DENIED = SmsBridgeError(PERMISSION_DENIED, "read_phone_state")
        val NOT_CLOUD_SIM = SmsBridgeError(PERMISSION_DENIED, "not_cloud_sim")
        val NO_SUBSCRIPTION = SmsBridgeError(TELEPHONY_UNAVAILABLE, "no_subscription")
        val NO_CLOUD_SIM = SmsBridgeError(TELEPHONY_UNAVAILABLE, "no_cloud_sim")
        val RATE_LIMITED = SmsBridgeError(DEVICE_SIDE_FAILURE, "rate_limited")
        val STORE_FULL = SmsBridgeError(DEVICE_SIDE_FAILURE, "store_full")
        val NOT_FOUND_ERROR = SmsBridgeError(NOT_FOUND)

        fun invalid(detail: String) = SmsBridgeError(INVALID_PARAMS, detail)

        /** Quotes a param name like the bridge's %q, capped so a junk key cannot flood logs. */
        fun unsupported(key: String) = SmsBridgeError(UNSUPPORTED_PARAM, JSONObject.quote(key.take(64)))
    }
}

sealed class SmsBridgeResult {
    data class Ok(val json: JSONObject) : SmsBridgeResult()
    data class Failure(val error: SmsBridgeError) : SmsBridgeResult()

    /** The device does not announce this method's token. */
    data class UnknownMethod(val method: String) : SmsBridgeResult()
}

internal fun SmsBridgeError.asResult(): SmsBridgeResult = SmsBridgeResult.Failure(this)
