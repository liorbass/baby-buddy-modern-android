package com.example.data.model

import com.example.R

/** The parts of the Wake windows card; which show is chosen in Customize Today. */
enum class WakeWindowPart(private val labelRes: Int) {
    AWAKE_FOR(R.string.wake_part_awake_for),
    NAP_DUE(R.string.wake_part_nap_due),
    TODAY_STATS(R.string.wake_part_today_stats),
    WINDOW_BARS(R.string.wake_part_window_bars);

    val label: String get() = AppText.get(labelRes)
}

/** Time awake between two sleeps (the Wake windows card on Today). */
data class WakeWindow(val startMillis: Long, val endMillis: Long) {
    val minutes: Int get() = ((endMillis - startMillis) / 60_000L).toInt()
}

/**
 * Whether [sleep] counts as night sleep for wake windows: marked as not a nap (the server's nap hours) and at least
 * [MIN_NIGHT_SLEEP_MINUTES] long, so a short evening catnap outside the nap hours still counts as a nap.
 */
fun isNightSleep(sleep: SleepEvent): Boolean = !sleep.isNap && sleep.durationMinutes >= MIN_NIGHT_SLEEP_MINUTES

/** A gap between two night sleeps is a night waking, not a wake window. */
fun isNightWaking(before: SleepEvent, after: SleepEvent): Boolean = isNightSleep(before) && isNightSleep(after)

/**
 * Whether being awake after [lastSleep] is most likely a night waking (the next sleep isn't known yet): it was night
 * sleep and ended during the night hours ([DayHours]). Waking up from it once the day has begun starts the day.
 */
fun isLikelyNightWaking(lastSleep: SleepEvent): Boolean = isNightSleep(lastSleep) && DayHours.isNightAt(lastSleep.endMillis)

/**
 * Daytime wake windows: the gaps between consecutive [sleeps], leaving out night wakings ([isNightWaking]); waking up
 * in the morning until the first nap, and the last nap until bedtime, count. Overlapping sleeps and gaps over 12 hours
 * (a missed sleep entry) are left out too.
 */
fun daytimeWakeWindows(sleeps: List<SleepEvent>): List<WakeWindow> = sleeps.sortedBy { it.timeMillis }
    .zipWithNext { a, b -> if (isNightWaking(a, b)) null else WakeWindow(a.endMillis, b.timeMillis) }
    .filterNotNull()
    .filter { it.endMillis > it.startMillis && it.endMillis - it.startMillis <= MAX_WAKE_WINDOW_MS }

/** The daytime wake windows that ended today ([sleeps] are today's, including last night's when it ended after midnight). */
fun completedWakeWindows(sleeps: List<SleepEvent>, dayStartMillis: Long): List<WakeWindow> = daytimeWakeWindows(sleeps).filter { it.endMillis >= dayStartMillis }

fun List<WakeWindow>.averageMinutes(): Int = if (isEmpty()) 0 else sumOf { it.minutes } / size

private const val MAX_WAKE_WINDOW_MS = 12 * 60 * 60_000L
private const val MIN_NIGHT_SLEEP_MINUTES = 90
