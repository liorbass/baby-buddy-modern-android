package com.example

import com.example.data.repository.SleepTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sleep timer: start, correct the start, and stop (which saves the sleep) or cancel. */
class SleepTimerTest {
    private data class Logged(val start: Long, val end: Long, val nap: Boolean, val notes: String)

    private val logged = mutableListOf<Logged>()
    private var changes = 0
    private var ends = 0
    private val timer = SleepTimer(
        logSleep = { start, end, nap, notes ->
            logged += Logged(start, end, nap, notes)
            42L
        },
        onChanged = { changes++ },
        onEnded = { ends++ }
    )

    @Test
    fun stopSavesTheSleepWithItsNotes() {
        timer.start(1_000L)
        timer.setNotes("Fussy")
        assertEquals(42L, timer.stop(endMillis = 5_000L, isNap = true))
        assertEquals(listOf(Logged(1_000L, 5_000L, true, "Fussy")), logged)
        assertFalse(timer.state.value.isRunning)
        assertEquals(1, changes)
        assertEquals(1, ends)
    }

    @Test
    fun aRunningSleepIsNotRestartedAndItsStartIsNeverInTheFuture() {
        timer.start(1_000L)
        timer.start(2_000L)
        assertEquals(1_000L, timer.state.value.startMillis)

        timer.setStart(Long.MAX_VALUE)
        assertTrue(timer.state.value.startMillis <= System.currentTimeMillis())
        assertTrue(timer.state.value.changedAtMillis > 0)
    }

    @Test
    fun cancelSavesNothingAndStopWithoutASleepDoesNothing() {
        timer.start(1_000L)
        timer.cancel()
        assertTrue(logged.isEmpty())
        assertNull(timer.stop())
        timer.setNotes("ignored")
        assertEquals("", timer.state.value.notes)
    }
}
