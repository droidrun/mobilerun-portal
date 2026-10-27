package com.mobilerun.portal.service

import android.net.Uri
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder

class FormDecodingTest {
    @Before
    fun setUp() {
        mockkStatic(Uri::class)
        // Stand-in for Android's percent-decoder, which leaves '+' untouched.
        every { Uri.decode(any()) } answers {
            URLDecoder.decode(firstArg<String>().replace("+", "%2B"), "UTF-8")
        }
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun plusDecodesToSpaceAndEscapedPlusStaysPlus() {
        assertEquals("hello world", decodeFormComponent("hello+world"))
        assertEquals("+15550001111", decodeFormComponent("%2B15550001111"))
        assertEquals("a b+c", decodeFormComponent("a%20b%2Bc"))
        assertEquals("  https://example.com/a", decodeFormComponent("++https%3A%2F%2Fexample.com%2Fa"))
        assertEquals("SGk+Lw==", decodeFormComponent("SGk%2BLw%3D%3D"))
    }
}
