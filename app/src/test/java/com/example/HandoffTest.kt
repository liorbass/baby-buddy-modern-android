package com.example

import com.example.data.model.DiaperEvent
import com.example.data.model.FeedType
import com.example.data.model.FeedingEvent
import com.example.data.model.SleepEvent
import com.example.data.model.TrackedItem
import com.example.data.repository.TimedEvents
import com.example.data.repository.handoffSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** "While you were away": what someone else logged since this phone left the app. */
class HandoffTest {
    private val events = TimedEvents(
        feeds = listOf(
            FeedingEvent(id = 1, timeMillis = 300, caregiver = "Alex"),
            FeedingEvent(id = 2, timeMillis = 200, caregiver = "sam"), // this phone, any case
            FeedingEvent(id = 3, timeMillis = 50, caregiver = "Alex"), // before leaving
            FeedingEvent(id = 4, timeMillis = 250, type = FeedType.BOTTLE)
        ),
        changes = listOf(DiaperEvent(id = 5, timeMillis = 400, caregiver = "Alex")),
        sleeps = listOf(
            SleepEvent(id = 6, timeMillis = 10, endMillis = 150, caregiver = "Alex"), // started before, ended after
            SleepEvent(id = -1, timeMillis = 120, endMillis = 500) // the one being timed here
        )
    )

    @Test
    fun withANameEverythingNotCarryingItCounts() {
        val s = handoffSummary(events, fromMillis = 100, myName = "Sam", hidden = emptySet())
        assertEquals(listOf(4L, 1L), s.feedings.map { it.id })
        assertEquals(listOf(5L), s.diapers.map { it.id })
        assertEquals(listOf(6L), s.sleeps.map { it.id })
    }

    @Test
    fun withoutANameOnlyNamedEntriesCount() {
        val s = handoffSummary(events, fromMillis = 100, myName = null, hidden = emptySet())
        assertEquals(listOf(2L, 1L), s.feedings.map { it.id })
    }

    @Test
    fun hiddenItemsAreLeftOut() {
        val s = handoffSummary(events, fromMillis = 100, myName = "Sam", hidden = setOf(TrackedItem.BOTTLE, TrackedItem.DIAPERS, TrackedItem.SLEEP))
        assertEquals(listOf(1L), s.feedings.map { it.id })
        assertTrue(s.diapers.isEmpty() && s.sleeps.isEmpty())
    }
}
