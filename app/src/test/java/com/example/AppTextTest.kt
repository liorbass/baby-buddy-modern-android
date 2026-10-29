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

/** The English text from string resources matches what the app showed before it was moved to strings.xml. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppTextTest {

    @Test
    fun `durations and percentiles read the same as before`() {
        assertEquals("45m", formatMinutes(45))
        assertEquals("2h 05m", formatMinutes(125))
        assertEquals("52nd", ordinalPercentile(52.4))
        assertEquals("<1st", ordinalPercentile(0.5))
        assertEquals(">99th", ordinalPercentile(99.5))
    }

    @Test
    fun `enum labels come from resources`() {
        assertEquals("Tummy time", TrackedItem.TUMMY_TIME.label)
        assertEquals("Both", diaperTypeLabel(DiaperType.BOTH))
    }

    @Test
    fun `stored diaper values are shown by name, unknown ones as they are`() {
        assertEquals("Loose / Liquid", diaperValueLabel("Loose / Liquid"))
        assertEquals("Barrier", diaperValueLabel("Barrier"))
        assertEquals("Lavender", diaperValueLabel("Lavender"))
    }
}
