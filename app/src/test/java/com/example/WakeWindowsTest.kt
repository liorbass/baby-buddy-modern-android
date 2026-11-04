package com.example

import com.example.data.model.SleepEvent
import com.example.data.model.WakeWindow
import com.example.data.model.completedWakeWindows
import com.example.data.model.isNightHour
import com.example.data.model.nightRanges
import org.junit.Assert.assertEquals
import org.junit.Test

/** Today's wake windows are the gaps between consecutive sleeps. */
class WakeWindowsTest {
    private val hour = 60 * 60_000L
    private val midnight = 100 * 24 * hour

    private fun sleep(fromHour: Double, toHour: Double, nap: Boolean = true) = SleepEvent(
        timeMillis = midnight + (fromHour * hour).toLong(),
        endMillis = midnight + (toHour * hour).toLong(),
        isNap = nap
    )

    @Test
    fun gapsBetweenSleepsInAnyOrder() {
        val sleeps = listOf(sleep(12.0, 13.5), sleep(-4.0, 6.0), sleep(8.0, 9.0))
        assertEquals(
            listOf(
                WakeWindow(midnight + 6 * hour, midnight + 8 * hour),
                WakeWindow(midnight + 9 * hour, midnight + 12 * hour)
            ),
            completedWakeWindows(sleeps, midnight)
        )
        assertEquals(listOf(120, 180), completedWakeWindows(sleeps, midnight).map { it.minutes })
    }

    @Test
    fun leavesOutOverlapsYesterdayAndMissedSleeps() {
        val overlapping = listOf(sleep(8.0, 10.0), sleep(9.5, 11.0))
        assertEquals(emptyList<WakeWindow>(), completedWakeWindows(overlapping, midnight))
        // A window that ended before midnight belongs to yesterday
        assertEquals(emptyList<WakeWindow>(), completedWakeWindows(listOf(sleep(-6.0, -5.0), sleep(-3.0, 1.0)), midnight))
        // Over 12 hours awake means a sleep wasn't logged
        assertEquals(emptyList<WakeWindow>(), completedWakeWindows(listOf(sleep(-4.0, 1.0), sleep(14.0, 15.0)), midnight))
    }

    @Test
    fun nightWakingsAreNotWakeWindows() {
        val sleeps = listOf(
            sleep(-4.0, 2.0, nap = false), // night sleep, woke at 2:00
            sleep(2.5, 6.0, nap = false), // back to sleep: the 30 minutes awake at night don't count
            sleep(8.0, 9.0), // morning nap: 6:00 to 8:00 counts
            sleep(21.5, 23.0, nap = false) // bedtime: 9:00 to 21:30 is over 12 hours, so left out
        )
        assertEquals(listOf(120), completedWakeWindows(sleeps, midnight).map { it.minutes })
        // The last nap until bedtime counts
        assertEquals(listOf(180), completedWakeWindows(listOf(sleep(14.0, 16.0), sleep(19.0, 23.0, nap = false)), midnight).map { it.minutes })
    }

    @Test
    fun aShortEveningCatnapStillCountsAsANap() {
        // 18:30 catnap outside the nap hours (not a nap on the server), then bed at 21:00: 19:10 to 21:00 is a wake window
        val sleeps = listOf(sleep(18.5, 19.1666, nap = false), sleep(21.0, 30.0, nap = false))
        assertEquals(listOf(110), completedWakeWindows(sleeps, midnight).map { it.minutes })
    }

    @Test
    fun nightHoursAndChartRanges() {
        assertEquals(true, isNightHour(23, dayStart = 7, nightStart = 19))
        assertEquals(false, isNightHour(7, dayStart = 7, nightStart = 19))
        assertEquals(true, isNightHour(6, dayStart = 7, nightStart = 19))
        // Night shift: night from 08:00 to 16:00
        assertEquals(true, isNightHour(10, dayStart = 16, nightStart = 8))
        // Rows from midnight: night 00:00-07:00 and 19:00-24:00
        assertEquals(listOf(0 to 420, 1140 to 1440), nightRanges(0, dayStart = 7, nightStart = 19))
        // Rows from 18:00: one night block from 19:00 (60) to 07:00 (780)
        assertEquals(listOf(60 to 780), nightRanges(18, dayStart = 7, nightStart = 19))
    }
}
