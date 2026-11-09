package com.example.data.repository

import com.example.R
import com.example.data.api.PaginatedResponse
import com.example.data.ml.RhythmEvents
import com.example.data.model.AppText
import com.example.data.model.BATH_TAG
import com.example.data.model.DayHours
import com.example.data.model.DayStats
import com.example.data.model.DiaperEvent
import com.example.data.model.DiaperMark
import com.example.data.model.DiaperType
import com.example.data.model.FeedIntervalStats
import com.example.data.model.FeedMark
import com.example.data.model.FeedType
import com.example.data.model.FeedingEvent
import com.example.data.model.HistoryDay
import com.example.data.model.NoteEvent
import com.example.data.model.PumpingEvent
import com.example.data.model.SleepEvent
import com.example.data.model.SleepMark
import com.example.data.model.SleepTimerState
import com.example.data.model.TrackedItem
import com.example.data.model.TummyTimeEvent
import com.example.data.model.averageMinutes
import com.example.data.model.daytimeWakeWindows
import com.example.data.model.isNapTime
import com.example.data.model.startOfDay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Read-only queries over a range of days, for History, Trends and the rhythm models: from the server while logged
 * in, from the simulated history in demo mode.
 */
internal class ServerQueries(
    private val session: () -> RemoteSession?,
    private val demo: () -> DemoHistory?, // non-null only in demo mode
    private val sleepTimer: () -> SleepTimerState,
    private val hiddenItems: () -> Set<TrackedItem>
) {

    /**
     * Feeds (from the day before [fromMillis], for gaps across it), diaper changes and sleeps (ended since
     * [fromMillis], plus the one being timed, ending now) up to [toMillis].
     */
    suspend fun fetchEvents(fromMillis: Long, toMillis: Long? = null): TimedEvents {
        demo()?.let { d ->
            val to = toMillis ?: Long.MAX_VALUE
            return TimedEvents(
                d.feeds.filter { it.timeMillis < to }.sortedBy { it.timeMillis },
                d.diapers.filter { it.timeMillis < to },
                d.sleeps.filter { it.timeMillis < to }
            )
        }
        val s = session()
        val child = s?.child
        check(s != null && child != null) { AppText.get(R.string.error_connect_for_trends) }
        val until = toMillis?.let(::isoUtc)
        val fetchFrom = fromMillis - DAY_MS
        val feeds = allPages("feedings") { s.api.getFeedings(s.auth, child, since = isoUtc(fetchFrom), until = until, limit = PAGE, offset = it) }
            .map(::mapFeeding)
            .sortedBy { it.timeMillis }
        val changes = allPages("diaper changes") { s.api.getChanges(s.auth, child, since = isoUtc(fromMillis), until = until, limit = PAGE, offset = it) }
            .map(::mapChange)
        val sleeps = runCatching {
            allPages("sleep") { s.api.getSleep(s.auth, child, until = until, endedSince = isoUtc(fromMillis), limit = PAGE, offset = it) }
        }.getOrDefault(emptyList()).map(::mapSleep)
        val running = sleepTimer().takeIf { it.isRunning && toMillis == null }?.let { t ->
            SleepEvent(id = RUNNING_SLEEP_ID, timeMillis = t.startMillis, endMillis = System.currentTimeMillis(), isNap = isNapTime(t.startMillis))
        }
        return TimedEvents(feeds, changes, sleeps + listOfNotNull(running))
    }

    /**
     * Entries for the [days] local days ending with the day containing [lastDayMillis], oldest first. On the server,
     * today's column comes from [today] (the live lists, with unsent entries and pending edits).
     */
    suspend fun loadHistory(lastDayMillis: Long, days: Int, today: (dayStart: Long) -> HistoryDay): Result<List<HistoryDay>> = withContext(Dispatchers.IO) {
        val dayStarts = (days - 1 downTo 0).map { back ->
            Calendar.getInstance().apply {
                timeInMillis = startOfDay(lastDayMillis)
                add(Calendar.DAY_OF_YEAR, -back)
            }.timeInMillis
        }
        demo()?.let { d ->
            val entries = HistoryEntries(d.feeds, d.diapers, d.tummyTimes, d.pumpings, d.sleeps, d.notes)
            return@withContext Result.success(dayStarts.map(entries::day))
        }
        val s = session()
        val child = s?.child
        if (s == null || child == null) {
            return@withContext Result.failure(IllegalStateException(AppText.get(R.string.error_connect_for_history)))
        }
        runCatching {
            val rangeEnd = Calendar.getInstance().apply {
                timeInMillis = dayStarts.last()
                add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
            val since = isoUtc(dayStarts.first())
            val until = isoUtc(rangeEnd - 1000)
            val api = s.api
            val feedResp = api.getFeedings(s.auth, child, since = since, until = until, limit = PAGE)
            val changeResp = api.getChanges(s.auth, child, since = since, until = until, limit = PAGE)
            val tummyResp = api.getTummyTimes(s.auth, child, since = since, until = until, limit = PAGE)
            val pumpResp = api.getPumping(s.auth, child, since = since, until = until, limit = PAGE)
            val sleepResp = runCatching { api.getSleep(s.auth, child, since = since, until = until, limit = PAGE) }.getOrNull()
            val noteResp = runCatching { api.getNotes(s.auth, child, since = since, until = until, limit = PAGE) }.getOrNull()
            if (!feedResp.isSuccessful) error("HTTP ${feedResp.code()} loading feedings")
            if (!changeResp.isSuccessful) error("HTTP ${changeResp.code()} loading diaper changes")

            val entries = HistoryEntries(
                feeds = feedResp.body()?.results.orEmpty().map(::mapFeeding),
                changes = changeResp.body()?.results.orEmpty().map(::mapChange),
                tummies = tummyResp.body()?.results.orEmpty().map(::mapTummyTime),
                pumps = pumpResp.body()?.results.orEmpty().map(::mapPumping),
                sleeps = sleepResp?.body()?.results.orEmpty().map(::mapSleep),
                notes = noteResp?.body()?.results.orEmpty().map(::mapNote)
            )
            val todayStart = startOfDay()
            dayStarts.map { start -> if (start == todayStart) today(start) else entries.day(start) }
        }.rethrowCancellation()
    }

    /**
     * Feeds, changes and finished sleeps from [fromMillis] to [toMillis], for training the rhythm models
     * (Trends → Models). Age counts from the birth date, else from [fromMillis].
     */
    suspend fun loadRhythmEvents(fromMillis: Long, toMillis: Long, birthDate: String?): Result<RhythmEvents> = withContext(Dispatchers.IO) {
        runCatching {
            val now = System.currentTimeMillis()
            val (feeds, changes, sleeps) = fetchEvents(fromMillis, toMillis.takeIf { it < now - 60_000L })
            val running = sleepTimer().takeIf { it.isRunning && toMillis >= now - 60_000L }?.startMillis
            val birth = birthDate?.let(::parseLocalDate)
            RhythmEvents(feeds, changes, sleeps.filter { it.id != RUNNING_SLEEP_ID }, running, birth ?: fromMillis)
        }.rethrowCancellation()
    }

    /** Per-day numbers for the last [days] days (the day running now last); days start at the Trends day start hour. */
    suspend fun loadTrends(days: Int): Result<List<DayStats>> = withContext(Dispatchers.IO) {
        val demoHistory = demo()
        val s = session()
        val child = s?.child
        if (demoHistory == null && (s == null || child == null)) {
            return@withContext Result.failure(IllegalStateException(AppText.get(R.string.error_connect_for_trends)))
        }
        runCatching {
            val now = System.currentTimeMillis()
            val firstDay = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, DayHours.trendsDayStartHour)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis > now) add(Calendar.DAY_OF_YEAR, -1)
                add(Calendar.DAY_OF_YEAR, -(days - 1))
            }
            // From 12 hours earlier, so the first day's morning wake window has the night sleep before it
            val (events, pumps) = coroutineScope {
                val events = async { fetchEvents(firstDay.timeInMillis - 12 * HOUR_MS) }
                val pumps = async {
                    when {
                        TrackedItem.PUMPING in hiddenItems() -> emptyList()

                        demoHistory != null -> demoHistory.pumpings

                        else -> allPages("pumping") { offset ->
                            s!!.api.getPumping(s.auth, child, since = isoUtc(firstDay.timeInMillis), limit = PAGE, offset = offset)
                        }.map(::mapPumping)
                    }
                }
                events.await() to pumps.await()
            }
            dayStats(firstDay, days, events, pumps)
        }.rethrowCancellation()
    }

    /** Average time between feed starts per day for the last 7 days, plus this week vs the week before. */
    suspend fun loadFeedIntervals(): Result<FeedIntervalStats> = withContext(Dispatchers.IO) {
        if (demo() != null) {
            // Sample numbers to match the demo day (a feed about every 3 hours)
            return@withContext Result.success(
                FeedIntervalStats(listOf(185, 178, 192, 181, 175, 188, 180), thisWeekAverageMinutes = 183, lastWeekAverageMinutes = 171)
            )
        }
        val s = session()
        val child = s?.child
        if (s == null || child == null) return@withContext Result.failure(IllegalStateException(AppText.get(R.string.error_not_connected)))
        runCatching {
            val todayStart = startOfDay()
            val response = s.api.getFeedings(s.auth, child, since = isoUtc(todayStart - 14 * DAY_MS), limit = PAGE)
            if (!response.isSuccessful) error("HTTP ${response.code()} loading feedings")
            feedIntervalStats(response.body()?.results.orEmpty().mapNotNull { parseServerTime(it.start)?.time }, todayStart)
        }.rethrowCancellation()
    }

    /** Every page of a list endpoint (up to [MAX_FETCH] entries); [load] gets the offset. */
    private suspend fun <T> allPages(what: String, load: suspend (offset: Int) -> Response<PaginatedResponse<T>>): List<T> {
        val out = mutableListOf<T>()
        while (out.size < MAX_FETCH) {
            val resp = load(out.size)
            if (!resp.isSuccessful) error("HTTP ${resp.code()} loading $what")
            val page = resp.body() ?: break
            out += page.results
            if (page.next == null || page.results.isEmpty()) break
        }
        return out
    }

    companion object {
        /** The id of the made-up entry [fetchEvents] adds for a sleep being timed. */
        const val RUNNING_SLEEP_ID = -1L
        private const val PAGE = 1000
        private const val MAX_FETCH = 20_000
        private const val HOUR_MS = 60 * 60_000L
        private const val DAY_MS = 24 * HOUR_MS
    }
}

/** A range of entries, cut into local days for History. Notes tagged [BATH_TAG] are baths. */
private class HistoryEntries(
    val feeds: List<FeedingEvent>,
    val changes: List<DiaperEvent>,
    val tummies: List<TummyTimeEvent>,
    val pumps: List<PumpingEvent>,
    val sleeps: List<SleepEvent>,
    val notes: List<NoteEvent>
) {
    fun day(start: Long): HistoryDay {
        val end = Calendar.getInstance().apply {
            timeInMillis = start
            add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        val onDay = notes.filter { it.timeMillis in start until end }
        return HistoryDay(
            dayStartMillis = start,
            feedings = feeds.filter { it.timeMillis in start until end },
            diapers = changes.filter { it.timeMillis in start until end },
            tummyTimes = tummies.filter { it.timeMillis in start until end },
            pumpings = pumps.filter { it.timeMillis in start until end },
            sleeps = sleeps.filter { it.timeMillis in start until end },
            baths = onDay.filter { BATH_TAG in it.tags },
            notes = onDay.filterNot { BATH_TAG in it.tags }
        )
    }
}

/** Trends' numbers and chart marks for [days] days from [firstDay] (each day as long as the calendar day). */
internal fun dayStats(firstDay: Calendar, days: Int, events: TimedEvents, pumps: List<PumpingEvent>): List<DayStats> {
    val (feeds, changes, sleeps) = events
    val wakeWindows = daytimeWakeWindows(sleeps.filter { it.id != ServerQueries.RUNNING_SLEEP_ID })
    fun endOf(f: FeedingEvent) = f.timeMillis + (f.leftDurationSec + f.rightDurationSec) * 1000L
    fun minutes(ms: Long) = (ms / 60_000L).toInt()
    fun DiaperEvent.isWet() = type == DiaperType.WET || type == DiaperType.BOTH
    fun DiaperEvent.isDirty() = type == DiaperType.DIRTY || type == DiaperType.BOTH

    // Gaps between consecutive feeds, keyed by the time the gap starts
    val gaps = feeds.zipWithNext { prev, next -> endOf(prev) to (next.timeMillis - endOf(prev)) }.filter { it.second > 0 }
    val labelFormat = SimpleDateFormat("EEE d", Locale.getDefault())
    return (0 until days).map { offset ->
        val dayStart = (firstDay.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset) }.timeInMillis
        val dayEnd = (firstDay.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset + 1) }.timeInMillis
        val day = dayStart until dayEnd
        val dayFeeds = feeds.filter { it.timeMillis in day }
        val dayChanges = changes.filter { it.timeMillis in day }
        val daySleeps = sleeps.filter { it.timeMillis in day }
        val nursing = dayFeeds.filter { it.type == FeedType.NURSING }
        val longestGap = gaps.filter { it.first in day }.maxByOrNull { it.second }
        val sleepMarks = sleeps.mapNotNull { s ->
            val from = maxOf(s.timeMillis, dayStart)
            val to = minOf(s.endMillis, dayEnd)
            if (to <= from) null else SleepMark(minutes(from - dayStart), minutes(to - from), s.isNap)
        }.sortedBy { it.startMinute }
        DayStats(
            label = labelFormat.format(Date(dayStart)),
            feeds = dayFeeds.size,
            nursingMinutes = nursing.sumOf { it.leftDurationSec + it.rightDurationSec } / 60,
            bottleMl = dayFeeds.sumOf { it.amountMl ?: 0 },
            wet = dayChanges.count { it.isWet() },
            dirty = dayChanges.count { it.isDirty() },
            isToday = offset == days - 1,
            leftMinutes = nursing.sumOf { it.leftDurationSec } / 60,
            rightMinutes = nursing.sumOf { it.rightDurationSec } / 60,
            feedMarks = dayFeeds.map { f ->
                FeedMark(minutes(f.timeMillis - dayStart), (f.leftDurationSec + f.rightDurationSec) / 60, isBottle = f.amountMl != null)
            },
            longestGapStartMinute = longestGap?.let { minutes(it.first - dayStart) },
            longestGapMinutes = longestGap?.let { minutes(it.second) } ?: 0,
            diaperMarks = dayChanges.map { c -> DiaperMark(minutes(c.timeMillis - dayStart), wet = c.isWet(), dirty = c.isDirty()) },
            sleepMarks = sleepMarks,
            sleepMinutes = sleepMarks.sumOf { it.durationMinutes },
            naps = daySleeps.count { it.isNap },
            longestSleepMinutes = daySleeps.maxOfOrNull { it.durationMinutes } ?: 0,
            wakeWindowMinutes = wakeWindows.filter { it.endMillis in day }.averageMinutes(),
            pumpedMl = pumps.filter { it.timeMillis in day }.sumOf { it.amountMl }
        )
    }
}

/**
 * Average minutes between feed [starts] for each of the 7 days up to the one starting at [todayStart], and this
 * week's and last week's averages. Feeds within 20 minutes of the one before (a bottle top-up) count as one, and
 * gaps under 30 minutes or over 10 hours (cluster feeds, missing entries) are left out.
 */
internal fun feedIntervalStats(starts: List<Long>, todayStart: Long): FeedIntervalStats {
    val day = 86_400_000L
    val feeds = starts.sorted().fold(mutableListOf<Long>()) { acc, t ->
        if (acc.isEmpty() || t - acc.last() > 20 * 60_000L) acc.add(t)
        acc
    }
    val gaps = feeds.zipWithNext { a, b -> b to (b - a) / 60_000L }.filter { it.second in 30..(10 * 60) }

    fun averageBetween(start: Long, end: Long): Int? = gaps.filter { it.first in start until end }.map { it.second }.takeIf { it.isNotEmpty() }?.average()?.toInt()

    return FeedIntervalStats(
        dailyAverageMinutes = (6 downTo 0).map { back -> (todayStart - back * day).let { averageBetween(it, it + day) } },
        thisWeekAverageMinutes = averageBetween(todayStart - 6 * day, todayStart + day),
        lastWeekAverageMinutes = averageBetween(todayStart - 13 * day, todayStart - 6 * day)
    )
}

/** Like [runCatching]'s result, but a cancelled coroutine stays cancelled instead of turning into a failure. */
private fun <T> Result<T>.rethrowCancellation(): Result<T> = onFailure { if (it is CancellationException) throw it }
