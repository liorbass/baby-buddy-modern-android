package com.example

import com.example.data.model.BabySex
import com.example.data.model.DiaperType
import com.example.data.model.TrackedItem
import com.example.data.model.diaperTypeLabel
import com.example.data.model.diaperValueLabel
import com.example.data.model.formatMinutes
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The German, Dutch, Spanish and Hebrew translations are picked up for their locales (French: FrenchStringsTest). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TranslatedStringsTest {

    private fun assertLocale(duration: String, tummyTime: String, both: String, loose: String, boy: String) {
        assertEquals(duration, formatMinutes(125))
        assertEquals(tummyTime, TrackedItem.TUMMY_TIME.label)
        assertEquals(both, diaperTypeLabel(DiaperType.BOTH))
        assertEquals(loose, diaperValueLabel("Loose / Liquid"))
        assertEquals(boy, BabySex.BOY.label)
    }

    @Test
    @Config(qualifiers = "de")
    fun german() = assertLocale("2 h 05 min", "Bauchzeit", "Beides", "Dünn / flüssig", "Junge")

    @Test
    @Config(qualifiers = "nl")
    fun dutch() = assertLocale("2 u 05", "Buikliggen", "Beide", "Dun / vloeibaar", "Jongen")

    @Test
    @Config(qualifiers = "es")
    fun spanish() = assertLocale("2 h 05", "Tiempo boca abajo", "Ambos", "Suelta / Líquida", "Niño")

    // Hebrew is in values-iw: java.util.Locale turns "he" into the legacy "iw", so values-he would never be used
    @Test
    @Config(qualifiers = "iw")
    fun hebrew() = assertLocale("2 ש׳ 05 דק׳", "זמן בטן", "שניהם", "רך מאוד / נוזלי", "בן")
}
