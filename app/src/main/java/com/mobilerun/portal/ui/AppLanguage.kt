package com.mobilerun.portal.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** Portal UI language: English unless the user picks another one in Settings. */
object AppLanguage {
    const val ENGLISH = "en"
    const val KOREAN = "ko"

    fun current(): String =
        AppCompatDelegate.getApplicationLocales()[0]?.language
            ?.takeIf { it == KOREAN } ?: ENGLISH

    fun set(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    /** Call after super.onCreate so the saved choice is loaded on API < 33. */
    fun applyDefault() {
        if (AppCompatDelegate.getApplicationLocales().isEmpty) set(ENGLISH)
    }
}
