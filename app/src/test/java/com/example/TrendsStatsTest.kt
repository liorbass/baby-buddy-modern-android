package com.example

import com.example.data.model.DiaperEvent
import com.example.data.model.DiaperType
import com.example.data.model.FeedType
import com.example.data.model.FeedingEvent
import com.example.data.model.PumpingEvent
import com.example.data.model.SleepEvent
import com.example.data.repository.TimedEvents
import com.example.data.repository.dayStats
import com.example.data.repository.feedIntervalStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

/** Trends' per-day numbers ([dayStats]) and the feed-interval averages ([feedIntervalStats]) from plain entries. */
class TrendsStatsTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour
    private val firstDay = Calendar.getInstance().apply {
        set(2026, Calendar.MARCH, 2, 0, 0, 0) // no daylight-saving change in the two days
        set(Calendar.MILLISECOND, 0)
    }
    private val start = firstDay.timeInMillis

    private fun nursing(at: Long, left: Int, right: Int) = FeedingEvent(timeMillis = at, type = FeedType.NURSING, leftDurationSec = left, rightDurationSec = right)

    private fun bottle(at: Long, ml: Int) = FeedingEvent(timeMillis = at, type = FeedType.BOTTLE, side = null, leftDurationSec = 0, amountMl = ml)

    @Test
    fun countsFeedsDiapersAndPumpingPerDay() {
        val feeds = listOf(nursing(start + 6 * hour, 600, 300), bottle(start + 9 * hour, 90), nursing(start + day + 7 * hour, 0, 900))
        val changes = listOf(
            DiaperEvent(timeMillis = start + 7 * hour, type = DiaperType.WET),
            DiaperEvent(timeMillis = start + 10 * hour, type = DiaperType.BOTH),
            DiaperEvent(timeMillis = start + day + 8 * hour, type = DiaperType.DIRTY)
        )
        val pumps = listOf(PumpingEvent(timeMillis = start + 11 * hour, amountMl = 120))

        val (first, second) = dayStats(firstDay, 2, TimedEvents(feeds, changes, emptyList()), pumps)

        assertEquals(2, first.feeds)
        assertEquals(15, first.nursingMinutes)
        assertEquals(10, first.leftMinutes)
        assertEquals(5, first.rightMinutes)
        assertEquals(90, first.bottleMl)
        assertEquals(2, first.wet)
        assertEquals(1, first.dirty)
        assertEquals(120, first.pumpedMl)
        assertEquals(listOf(360, 540), first.feedMarks.map { it.startMinute })
        assertEquals(listOf(false, true), first.feedMarks.map { it.isBottle })
        assertEquals(false, first.isToday)

        assertEquals(1, second.feeds)
        assertEquals(0, second.wet)
        assertEquals(1, second.dirty)
        assertEquals(true, second.isToday)
    }

    @Test
    fun longestGapRunsFromTheEndOfOneFeedToTheNext() {
        val feeds = listOf(nursing(start + 2 * hour, 600, 0), nursing(start + 5 * hour, 600, 0), nursing(start + 6 * hour, 600, 0))
        val stats = dayStats(firstDay, 1, TimedEvents(feeds = feeds), emptyList()).single()
        // 02:10 → 05:00 is longer than 05:10 → 06:00
        assertEquals(130, stats.longestGapStartMinute)
        assertEquals(170, stats.longestGapMinutes)
    }

    @Test
    fun sleepAcrossMidnightIsSplitButCountedOnTheDayItStarted() {
        val night = SleepEvent(timeMillis = start + 20 * hour, endMillis = start + day + 6 * hour, isNap = false)
        val nap = SleepEvent(timeMillis = start + day + 9 * hour, endMillis = start + day + 10 * hour, isNap = true)

        val (first, second) = dayStats(firstDay, 2, TimedEvents(sleeps = listOf(night, nap)), emptyList())

        assertEquals(240, first.sleepMinutes)
        assertEquals(600, first.longestSleepMinutes)
        assertEquals(0, first.naps)
        assertEquals(360 + 60, second.sleepMinutes)
        assertEquals(listOf(0, 540), second.sleepMarks.map { it.startMinute })
        assertEquals(1, second.naps)
        assertEquals(60, second.longestSleepMinutes)
    }

    @Test
    fun feedIntervalsMergeTopUpsAndSkipImplausibleGaps() {
        val todayStart = start + 13 * day
        val starts = listOf(
            todayStart + 1 * hour,
            todayStart + 1 * hour + 10 * minute, // a top-up: same feed
            todayStart + 4 * hour, // 3 h after
            todayStart + 4 * hour + 25 * minute, // 25 min: a cluster feed, left out
            todayStart + 7 * hour + 25 * minute // 3 h after
        )

        val stats = feedIntervalStats(starts, todayStart)

        assertEquals(7, stats.dailyAverageMinutes.size)
        assertEquals(180, stats.dailyAverageMinutes.last())
        assertNull(stats.dailyAverageMinutes.first())
        assertEquals(180, stats.thisWeekAverageMinutes)
        assertNull(stats.lastWeekAverageMinutes)
    }
}
