package com.example

import com.example.data.model.DiaperEvent
import com.example.data.model.DiaperType
import com.example.data.model.FeedType
import com.example.data.model.FeedingEvent
import com.example.data.repository.composeDiaperNotes
import com.example.data.repository.composeFeedingNotes
import com.example.data.repository.parseDiaperNotes
import com.example.data.repository.parseDurationSeconds
import com.example.data.repository.parseSideSplit
import com.example.data.repository.splitCaregiver
import com.example.data.repository.splitFeedingMeta
import com.example.data.repository.withCaregiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The generated segments in server notes round-trip, leaving only what people wrote in the notes. */
class ServerMappingTest {
    @Test
    fun caregiverRoundTrips() {
        val notes = withCaregiver("Fussy at the start", "Alex")
        assertEquals("Fussy at the start\nCaregiver: Alex", notes)
        assertEquals("Alex" to "Fussy at the start", splitCaregiver(notes))
        assertEquals(null to "Just a note", splitCaregiver("Just a note"))
    }

    @Test
    fun breastfeedNotesRoundTrip() {
        val feed = FeedingEvent(type = FeedType.NURSING, leftDurationSec = 605, rightDurationSec = 300, sides = "LEFT -> RIGHT", caregiver = "Sam", notes = "Sleepy")
        val notes = composeFeedingNotes(feed)
        assertEquals("Left: 10m 5s | Right: 5m 0s | Sides: LEFT -> RIGHT | Caregiver: Sam\nSleepy", notes)
        assertEquals(605 to 300, parseSideSplit(notes))
        val (caregiver, rest) = splitCaregiver(notes)
        assertEquals("Sam", caregiver)
        assertEquals("LEFT -> RIGHT" to "Sleepy", splitFeedingMeta(rest))
    }

    @Test
    fun diaperExtrasRoundTrip() {
        val change = DiaperEvent(
            type = DiaperType.BOTH,
            amount = "Heavy",
            rashPresent = true,
            skinBarrierApplied = listOf("Zinc"),
            consistency = "Seedy",
            notes = "Blowout"
        )
        val notes = composeDiaperNotes(change)
        assertEquals("Blowout\nAmount: Heavy | Rash | Cream: Zinc | Consistency: Seedy", notes)
        val extras = parseDiaperNotes(notes)
        assertTrue(extras.rash)
        assertEquals(listOf("Zinc"), extras.creams)
        assertEquals("Seedy", extras.consistency)
        assertEquals("Heavy", extras.amount)
        assertEquals("Blowout", extras.notes)
        // A wet diaper has no consistency; a plain note stays a note
        assertEquals("Amount: Light", composeDiaperNotes(change.copy(type = DiaperType.WET, amount = "Light", rashPresent = false, skinBarrierApplied = emptyList(), notes = "")))
        assertFalse(parseDiaperNotes("Rash cream ran out").rash)
        assertEquals("Rash cream ran out", parseDiaperNotes("Rash cream ran out").notes)
    }

    @Test
    fun serverDurations() {
        assertEquals(15 * 60 + 30, parseDurationSeconds("00:15:30"))
        assertEquals(86400 + 3600, parseDurationSeconds("1 01:00:00.123456"))
        assertEquals(0, parseDurationSeconds(null))
    }
}
