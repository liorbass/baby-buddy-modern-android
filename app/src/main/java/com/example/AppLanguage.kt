package com.example

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The app's language (Settings → Appearance); "" is the phone's language. Android 13+ keeps it as the per-app language
 * (LocaleManager, the same choice as the phone's App languages screen), which the system applies everywhere. Older
 * phones save it in prefs, and [apply] puts it into the app's, the activity's and the services' resources.
 */
object AppLanguage {

    /** The translated languages (the values-* folders; Hebrew is packaged as values-iw). Checked by AppLanguageTest. */
    val TAGS = listOf("en", "fr", "de", "nl", "es", "he")

    private const val PREFS = "baby_buddy"
    private const val PREF_LANGUAGE = "app_language"

    /** The picked language's tag, or "" for the phone's language. */
    fun current(context: Context): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
        if (locales.isEmpty) "" else tagOf(locales[0])
    } else {
        saved(context)
    }

    /** Switches to [tag] ("" = the phone's language) and redraws [activity] in it. */
    fun set(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The system recreates the activity in the new language
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PREF_LANGUAGE, tag).apply()
            apply(activity.applicationContext)
            activity.recreate()
        }
    }

    /** Before Android 13: puts the saved language into [context]'s resources and the default locale (dates, numbers). */
    fun apply(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        val tag = saved(context)
        val locale = if (tag.isEmpty()) Resources.getSystem().configuration.locales[0] else Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val resources = context.resources
        // setLocale also sets the layout direction (right to left for Hebrew)
        val config = Configuration(resources.configuration).apply { setLocale(locale) }
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    /** The language's name in itself: "Deutsch", "Français", "עברית". */
    fun displayName(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
    }

    /** One of [TAGS] for [locale] ("iw" and "he" are both Hebrew; "fr-CA" is French). */
    fun tagOf(locale: Locale): String {
        val language = if (locale.language == "iw") "he" else locale.language
        return TAGS.firstOrNull { it == language } ?: locale.toLanguageTag()
    }

    private fun saved(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_LANGUAGE, "").orEmpty()
}
