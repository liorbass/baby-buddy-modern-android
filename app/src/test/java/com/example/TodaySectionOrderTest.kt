package com.example

import com.example.data.model.TodaySection
import org.junit.Assert.assertEquals
import org.junit.Test

/** Today's section order: saved names made whole, and moving one section. */
class TodaySectionOrderTest {
    @Test
    fun emptyOrUnknownSavedOrderIsTheDefault() {
        assertEquals(TodaySection.entries, TodaySection.order(emptyList()))
        assertEquals(TodaySection.entries, TodaySection.order(listOf("", "NOT_A_SECTION")))
    }

    @Test
    fun missingSectionsGoInAfterTheirDefaultNeighbour() {
        val saved = TodaySection.entries.filter { it != TodaySection.NEXT_UP }.reversed().map { it.name }
        val order = TodaySection.order(saved)
        assertEquals(TodaySection.entries.toSet(), order.toSet())
        // NEXT_UP follows WAKE_WINDOWS by default, so it goes right after it
        assertEquals(order.indexOf(TodaySection.WAKE_WINDOWS) + 1, order.indexOf(TodaySection.NEXT_UP))
        // The first section by default, when missing, goes first
        assertEquals(TodaySection.QUICK_ACTIONS, TodaySection.order(listOf("SUMMARY_TILES")).first())
    }

    @Test
    fun moveIsClampedToTheEnds() {
        val default = TodaySection.entries.toList()
        val down = TodaySection.move(default, TodaySection.QUICK_ACTIONS, 1)
        assertEquals(listOf(TodaySection.LAST_ENTRIES, TodaySection.QUICK_ACTIONS), down.take(2))
        assertEquals(default, TodaySection.move(default, TodaySection.QUICK_ACTIONS, -1))
        assertEquals(TodaySection.QUICK_ACTIONS, TodaySection.move(default, TodaySection.QUICK_ACTIONS, 99).last())
    }

    @Test
    fun moveSkipsSectionsThatAreNotShown() {
        val default = TodaySection.entries.toList()
        // Temperature is off: moving Notes up passes it and lands above Tummy time
        val hiddenTemperature = { s: TodaySection -> s != TodaySection.TEMPERATURE }
        val moved = TodaySection.move(default, TodaySection.NOTES, -1, hiddenTemperature)
        assertEquals(listOf(TodaySection.NOTES, TodaySection.TUMMY_TIME, TodaySection.TEMPERATURE), moved.subList(8, 11))
        // Everything else keeps its place
        assertEquals(default - TodaySection.NOTES, moved - TodaySection.NOTES)
    }
}
