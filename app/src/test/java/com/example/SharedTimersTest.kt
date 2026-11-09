package com.example

import com.example.data.model.BreastSide
import com.example.data.repository.FeedSnapshot
import com.example.data.repository.SharedTimers
import com.example.data.repository.SleepSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A running feed or sleep survives the trip through a Baby Buddy timer's name; other timers are ignored. */
class SharedTimersTest {
    private val feed = FeedSnapshot(
        feedingServerId = 123,
        side = BreastSide.RIGHT,
        paused = true,
        leftSeconds = 192,
        rightSeconds = 300,
        firstSide = BreastSide.LEFT,
        startMillis = 1_700_000_000_000,
        pausedAtMillis = 1_700_000_600_000,
        lastActiveMillis = 0,
        changedAtMillis = 1_700_000_600_123
    )

    @Test
    fun feedRoundTripsWithAReadableName() {
        val name = SharedTimers.encodeFeed(feed)
        assertTrue(name, name.startsWith("Breastfeeding · Right (paused) #bb1 "))
        assertEquals(feed, SharedTimers.decodeFeed(name))
        assertNull(SharedTimers.decodeSleep(name))
    }

    @Test
    fun feedWithoutARecordOrFirstSideRoundTrips() {
        val running = feed.copy(feedingServerId = null, firstSide = null, paused = false, pausedAtMillis = 0)
        assertEquals(running, SharedTimers.decodeFeed(SharedTimers.encodeFeed(running)))
    }

    @Test
    fun sleepRoundTrips() {
        val sleep = SleepSnapshot(startMillis = 1_700_000_000_000, changedAtMillis = 1_700_000_000_456)
        val name = SharedTimers.encodeSleep(sleep)
        assertEquals(sleep, SharedTimers.decodeSleep(name))
        assertNull(SharedTimers.decodeFeed(name))
    }

    @Test
    fun timersMadeElsewhereOrIncompleteAreIgnored() {
        assertNull(SharedTimers.decodeFeed("Tummy time"))
        assertNull(SharedTimers.decodeFeed(null))
        assertNull(SharedTimers.decodeFeed("Breastfeeding #bb1 k=feed;a=X;ss=1;t=2")) // unknown side
        assertNull(SharedTimers.decodeSleep("Sleep #bb1 k=sleep;s=1")) // no change time
    }
}
