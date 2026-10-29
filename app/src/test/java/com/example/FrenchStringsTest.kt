package com.example

import com.example.data.model.DiaperType
import com.example.data.model.TrackedItem
import com.example.data.model.diaperTypeLabel
import com.example.data.model.diaperValueLabel
import com.example.data.model.formatMinutes
import com.example.data.model.ordinalPercentile
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The French translation (values-fr) is picked up, with French durations and ordinals. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "fr")
class FrenchStringsTest {

    @Test
    fun `durations and percentiles read in French`() {
        assertEquals("45 min", formatMinutes(45))
        assertEquals("2 h 05", formatMinutes(125))
        assertEquals("52e", ordinalPercentile(52.4))
    }

    @Test
    fun `labels and stored diaper values show in French`() {
        assertEquals("Temps sur le ventre", TrackedItem.TUMMY_TIME.label)
        assertEquals("Les deux", diaperTypeLabel(DiaperType.BOTH))
        assertEquals("Liquide", diaperValueLabel("Loose / Liquid"))
    }
}
