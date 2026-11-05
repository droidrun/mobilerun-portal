package com.mobilerun.portal.sms

object SmsPhoneNumbers {
    private val E164 = Regex("^\\+[1-9][0-9]{6,14}$")

    /**
     * E.164 for a SIM's own number, or null when blank or not normalizable. [formatter] is
     * PhoneNumberUtils.formatNumberToE164 on device; it gets the upper-case ISO country code.
     */
    fun normalizeE164(
        raw: String?,
        countryIso: String?,
        formatter: (number: String, countryIso: String) -> String?,
    ): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("+")) {
            val compact = "+" + trimmed.filter { it.isDigit() }
            if (E164.matches(compact) && trimmed.all { it.isDigit() || it in "+ -().".toSet() }) return compact
        }
        val iso = countryIso?.trim()?.uppercase()?.takeIf { it.length == 2 } ?: return null
        val formatted = try {
            formatter(trimmed, iso)?.trim()
        } catch (_: Exception) {
            null
        }
        return formatted?.takeIf { E164.matches(it) }
    }

    /** Digits plus a leading '+', for comparing a provider address with a send's 'to'. */
    fun compactAddress(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        val digits = trimmed.filter { it.isDigit() }
        return if (trimmed.startsWith("+")) "+$digits" else digits.ifEmpty { trimmed }
    }
}
