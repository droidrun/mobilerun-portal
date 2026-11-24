package com.mobilerun.portal.ui

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.mobilerun.portal.R

/** Portal UI language: English unless the user picks another one in Settings. */
object AppLanguage {
    /** [nativeName] is shown as is; [name] is translated into the current UI language. */
    data class Option(val tag: String, @StringRes val nativeName: Int, @StringRes val name: Int)

    val OPTIONS = listOf(
        Option("en", R.string.language_english, R.string.language_name_english),
        Option("ko", R.string.language_korean, R.string.language_name_korean),
    )

    fun current(): Option {
        val tag = AppCompatDelegate.getApplicationLocales()[0]?.language
        return OPTIONS.firstOrNull { it.tag == tag } ?: OPTIONS.first()
    }

    fun set(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    /** Call after super.onCreate so the saved choice is loaded on API < 33. */
    fun applyDefault() {
        if (AppCompatDelegate.getApplicationLocales().isEmpty) set(OPTIONS.first().tag)
    }
}
