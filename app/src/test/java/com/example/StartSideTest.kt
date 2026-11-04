package com.example

import com.example.data.model.BreastSide
import com.example.data.model.StartSide
import org.junit.Assert.assertEquals
import org.junit.Test

/** One-tap feeds (timer widget, Quick Settings tile) start on the side chosen in Settings → Tracking. */
class StartSideTest {

    @Test
    fun oppositeAlternatesAndStartsLeftWithoutHistory() {
        assertEquals(BreastSide.RIGHT, StartSide.OPPOSITE.after(BreastSide.LEFT))
        assertEquals(BreastSide.LEFT, StartSide.OPPOSITE.after(BreastSide.RIGHT))
        assertEquals(BreastSide.LEFT, StartSide.OPPOSITE.after(null))
    }

    @Test
    fun sameContinuesOnTheLastSide() {
        assertEquals(BreastSide.LEFT, StartSide.SAME.after(BreastSide.LEFT))
        assertEquals(BreastSide.RIGHT, StartSide.SAME.after(BreastSide.RIGHT))
        assertEquals(BreastSide.LEFT, StartSide.SAME.after(null))
    }

    @Test
    fun fixedSidesIgnoreTheLastFeed() {
        BreastSide.entries.plus(null).forEach { last ->
            assertEquals(BreastSide.LEFT, StartSide.LEFT.after(last))
            assertEquals(BreastSide.RIGHT, StartSide.RIGHT.after(last))
        }
    }
}
