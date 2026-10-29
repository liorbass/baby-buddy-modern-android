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
 * The wake windows that ended today: the gaps between consecutive [sleeps] (today's, which include last night's sleep
 * when it ended after midnight). Overlapping sleeps and gaps over 12 hours (a missed sleep entry) are left out.
 */
fun completedWakeWindows(sleeps: List<SleepEvent>, dayStartMillis: Long): List<WakeWindow> = sleeps.sortedBy { it.timeMillis }
    .zipWithNext { a, b -> WakeWindow(a.endMillis, b.timeMillis) }
    .filter { it.endMillis >= dayStartMillis && it.endMillis > it.startMillis && it.endMillis - it.startMillis <= MAX_WAKE_WINDOW_MS }

fun List<WakeWindow>.averageMinutes(): Int = if (isEmpty()) 0 else sumOf { it.minutes } / size

private const val MAX_WAKE_WINDOW_MS = 12 * 60 * 60_000L
