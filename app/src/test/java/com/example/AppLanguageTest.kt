package com.example

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

/** The language picker (Settings → Appearance) offers every translation, by its own name. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppLanguageTest {

    @Test
    fun `every translation is offered`() {
        // Unit tests run in the app module's folder; Hebrew is values-iw
        val translated = File("src/main/res").listFiles()!!
            .filter { it.name.startsWith("values-") && File(it, "strings.xml").exists() }
            .map { it.name.removePrefix("values-").let { code -> if (code == "iw") "he" else code } }
        assertEquals((translated + "en").sorted(), AppLanguage.TAGS.sorted())
    }

    @Test
    fun `languages are named in themselves`() {
        assertEquals("Deutsch", AppLanguage.displayName("de"))
        assertEquals("Français", AppLanguage.displayName("fr"))
        assertEquals("Español", AppLanguage.displayName("es"))
        assertEquals("עברית", AppLanguage.displayName("he"))
    }

    @Test
    fun `locales map to the offered languages`() {
        assertEquals("he", AppLanguage.tagOf(Locale.forLanguageTag("iw")))
        assertEquals("he", AppLanguage.tagOf(Locale.forLanguageTag("he-IL")))
        assertEquals("fr", AppLanguage.tagOf(Locale.forLanguageTag("fr-CA")))
    }
}
