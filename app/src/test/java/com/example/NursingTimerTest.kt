package com.example

import com.example.data.model.BreastSide
import com.example.data.model.FeedingEvent
import com.example.data.repository.FeedRecords
import com.example.data.repository.NursingTimer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The nursing timer: taps start, pause and switch one feed record; edits and the end update it. */
class NursingTimerTest {
    private val feedings = MutableStateFlow<List<FeedingEvent>>(emptyList())
    private val updates = mutableListOf<FeedingEvent>()
    private var nextId = 1L
    private var changes = 0
    private var ends = 0
    private val timer = NursingTimer(
        records = FeedRecords(
            feedings = feedings,
            nextId = { nextId++ },
            add = { e ->
                feedings.update { listOf(e) + it }
                e.id
            },
            queueUpdate = { updates += it }
        ),
        onChanged = { changes++ },
        onEnded = { ends++ }
    )

    private fun record() = feedings.value.single()

    @Test
    fun firstTapCreatesTheFeedAndTheSameSidePausesIt() {
        timer.tapSide(BreastSide.LEFT)
        val t = timer.state.value
        assertTrue(t.isRunning)
        assertFalse(t.isPaused)
        assertEquals(BreastSide.LEFT, t.activeBreast)
        assertEquals(record().id, t.feedingLocalId)
        assertEquals("LEFT", record().sides)

        timer.tapSide(BreastSide.LEFT)
        assertTrue(timer.state.value.isPaused)
        assertEquals(2, changes)
    }

    @Test
    fun otherSideSwitchesAndRecordsTheOrder() {
        timer.tapSide(BreastSide.LEFT)
        timer.setSideSeconds(BreastSide.LEFT, 300)
        timer.switchSide()

        assertEquals(BreastSide.RIGHT, timer.state.value.activeBreast)
        assertEquals("LEFT -> RIGHT", record().sides)
        assertEquals(300, record().leftDurationSec)
        assertEquals(BreastSide.LEFT, record().side) // only the left side has time so far
        assertEquals(record(), updates.last())
    }

    @Test
    fun movingTheStartEarlierCreditsTheFirstSide() {
        timer.tapSide(BreastSide.RIGHT)
        val start = timer.state.value.sessionStartMillis
        timer.setFeedStart(start - 120_000L)

        assertEquals(start - 120_000L, timer.state.value.sessionStartMillis)
        assertEquals(120, timer.state.value.rightSeconds)
        assertEquals(start - 120_000L, record().timeMillis)
        assertEquals(1, timer.state.value.editCount)
    }

    @Test
    fun endFeedKeepsTheRecordAndTheNextTapStartsANewOne() {
        timer.tapSide(BreastSide.LEFT)
        timer.setNotes("  Sleepy  ")
        assertEquals("Sleepy", timer.notes())

        timer.endFeed()
        assertFalse(timer.state.value.isRunning)
        assertNull(timer.state.value.feedingLocalId)
        assertEquals(1, ends)
        assertEquals("", timer.notes())

        timer.tapSide(BreastSide.RIGHT)
        assertEquals(2, feedings.value.size)
        assertNotNull(timer.state.value.feedingLocalId)
    }

    @Test
    fun editsWithoutARunningFeedDoNothing() {
        timer.setSideSeconds(BreastSide.LEFT, 60)
        timer.setFeedStart(0L)
        timer.setNotes("x")
        assertFalse(timer.state.value.isRunning)
        assertTrue(feedings.value.isEmpty())
        assertEquals(0, changes)
    }
}
