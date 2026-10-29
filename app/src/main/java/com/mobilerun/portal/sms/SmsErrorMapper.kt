package com.mobilerun.portal.sms

import android.app.Activity
import android.telephony.SmsManager

object SmsErrorMapper {
    fun codeName(resultCode: Int): String {
        return when (resultCode) {
            Activity.RESULT_OK -> "RESULT_OK"
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "RESULT_ERROR_GENERIC_FAILURE"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "RESULT_ERROR_RADIO_OFF"
            SmsManager.RESULT_ERROR_NULL_PDU -> "RESULT_ERROR_NULL_PDU"
            SmsManager.RESULT_ERROR_NO_SERVICE -> "RESULT_ERROR_NO_SERVICE"
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "RESULT_ERROR_LIMIT_EXCEEDED"
            SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE -> "RESULT_ERROR_FDN_CHECK_FAILURE"
            SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED -> "RESULT_ERROR_SHORT_CODE_NOT_ALLOWED"
            SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> "RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED"
            else -> "RESULT_CODE_$resultCode"
        }
    }

    fun message(resultCode: Int): String {
        return when (resultCode) {
            Activity.RESULT_OK -> "OK"
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Generic SMS send failure"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "Radio is off"
            SmsManager.RESULT_ERROR_NULL_PDU -> "Null SMS PDU"
            SmsManager.RESULT_ERROR_NO_SERVICE -> "No cellular service"
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "SMS send limit exceeded"
            SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE -> "Fixed dialing number check failed"
            SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED -> "Short code SMS not allowed"
            SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> "Short code SMS denied permanently"
            else -> "SMS result code $resultCode"
        }
    }
}
