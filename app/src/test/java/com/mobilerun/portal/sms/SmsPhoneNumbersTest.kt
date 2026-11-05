package com.mobilerun.portal.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmsPhoneNumbersTest {
    private val calls = mutableListOf<Pair<String, String>>()
    private val formatter: (String, String) -> String? = { number, iso ->
        calls += number to iso
        if (iso == "US" && number.filter(Char::isDigit).length == 10) "+1" + number.filter(Char::isDigit) else null
    }

    @Test
    fun keepsE164AndStripsSeparators() {
        assertEquals("+15555215554", SmsPhoneNumbers.normalizeE164("+15555215554", "us", formatter))
        assertEquals("+15555215554", SmsPhoneNumbers.normalizeE164(" +1 (555) 521-5554 ", null, formatter))
        assertEquals(emptyList<Pair<String, String>>(), calls)
    }

    @Test
    fun formatsNationalNumbersWithUpperCaseCountry() {
        assertEquals("+15555215554", SmsPhoneNumbers.normalizeE164("5555215554", "us", formatter))
        assertEquals(listOf("5555215554" to "US"), calls)
    }

    @Test
    fun blankUnknownOrInvalidIsOmitted() {
        assertNull(SmsPhoneNumbers.normalizeE164(null, "us", formatter))
        assertNull(SmsPhoneNumbers.normalizeE164("   ", "us", formatter))
        assertNull(SmsPhoneNumbers.normalizeE164("5555215554", null, formatter))
        assertNull(SmsPhoneNumbers.normalizeE164("5555215554", "", formatter))
        assertNull(SmsPhoneNumbers.normalizeE164("123", "us", formatter))
        assertNull(SmsPhoneNumbers.normalizeE164("5555215554", "us") { _, _ -> "5555215554" })
        assertNull(SmsPhoneNumbers.normalizeE164("5555215554", "us") { _, _ -> throw IllegalStateException() })
        assertNull(SmsPhoneNumbers.normalizeE164("+0123", "us", formatter))
    }
}
