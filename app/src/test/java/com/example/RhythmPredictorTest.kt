package com.example

import com.example.data.ml.RhythmTarget
import com.example.data.model.DiaperEvent
import com.example.data.model.ReminderSettings
import com.example.data.model.SleepTimerState
import com.example.data.model.TrackedItem
import com.example.data.repository.RhythmAnchors
import com.example.data.repository.RhythmPredictor
import com.example.data.repository.SettingsStore
import com.example.data.repository.TimedEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Without a saved model, the next-up times come from the reminder intervals; untracked and running items are left out. */
class RhythmPredictorTest {
    private val settings = SettingsStore(onClockFormatChanged = {})
    private val lastFeed = MutableStateFlow<Long?>(1_000_000L)
    private val lastDiaper = MutableStateFlow<DiaperEvent?>(DiaperEvent(timeMillis = 2_000_000L))
    private val feedRunning = MutableStateFlow(false)
    private var fetches = 0
    private val predictor = RhythmPredictor(
        scope = CoroutineScope(Dispatchers.Unconfined),
        settings = settings,
        anchors = RhythmAnchors(lastFeed, lastDiaper, MutableStateFlow(null), feedRunning, MutableStateFlow(SleepTimerState())),
        today = { TimedEvents() },
        fetchEvents = {
            fetches++
            TimedEvents()
        }
    )

    @Test
    fun reminderIntervalsFillInForMissingModels() {
        settings.setReminders(ReminderSettings(feedAfterMinutes = 180, diaperAfterMinutes = 120))
        assertEquals(
            mapOf(RhythmTarget.FEED_GAP to 1_000_000L + 180 * 60_000L, RhythmTarget.DIAPER_GAP to 2_000_000L + 120 * 60_000L),
            predictor.nextUpTimes.value
        )
        assertTrue(predictor.nextTimes.value.isEmpty())
        assertNull(predictor.due(RhythmTarget.FEED_GAP, 1_000_000L))
    }

    @Test
    fun noNextFeedWhileOneRunsAndNothingForUntrackedItems() {
        settings.setReminders(ReminderSettings(feedAfterMinutes = 180, diaperAfterMinutes = 120))
        feedRunning.value = true
        settings.setItemTracked(TrackedItem.DIAPERS, false)
        assertTrue(predictor.nextUpTimes.value.isEmpty())
    }

    @Test
    fun withoutModelsAnUpdateNeitherFetchesNorPredicts() = runBlocking {
        predictor.update()
        assertEquals(0, fetches)
        assertTrue(predictor.predictions.value.isEmpty())
    }
}
