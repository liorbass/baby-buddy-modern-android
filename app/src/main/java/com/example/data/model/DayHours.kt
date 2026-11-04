package com.example.data.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.util.Calendar

/**
 * When the day and the night begin (Settings → Baby → Day and night), in whole hours, and the hour Trends'
 * days start at. Set by the repository from prefs; Compose state, so screens redraw when they change.
 *
 * Night is used for the "by time of day" theme, the nap / night sleep default for a new sleep, the night shading
 * on Trends charts and telling night wakings from wake windows.
 */
object DayHours {
    var dayStartHour by mutableIntStateOf(DEFAULT_DAY_START)
    var nightStartHour by mutableIntStateOf(DEFAULT_NIGHT_START)
    var trendsDayStartHour by mutableIntStateOf(0)

    fun isNightHour(hour: Int): Boolean = isNightHour(hour, dayStartHour, nightStartHour)

    fun isNightAt(millis: Long): Boolean = isNightHour(Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.HOUR_OF_DAY))

    /** "07:00" / "7:00 AM" for [hour], in the chosen clock format. */
    fun label(hour: Int): String = TimeFormat.clock(
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
        }.time
    )

    const val DEFAULT_DAY_START = 7
    const val DEFAULT_NIGHT_START = 19
}

/** Whether [hour] is night when the day starts at [dayStart] and the night at [nightStart] (the two must differ). */
fun isNightHour(hour: Int, dayStart: Int, nightStart: Int): Boolean = if (nightStart > dayStart) {
    hour >= nightStart || hour < dayStart
} else {
    hour in nightStart until dayStart
}

/**
 * The night as minute ranges within a 24-hour chart row that starts at [rowStartHour] (Trends): one range, or two
 * when the night wraps past the row's edge.
 */
fun nightRanges(rowStartHour: Int, dayStart: Int, nightStart: Int): List<Pair<Int, Int>> {
    val from = Math.floorMod(nightStart - rowStartHour, 24) * 60
    val length = Math.floorMod(dayStart - nightStart, 24) * 60
    return if (from + length <= 1440) listOf(from to from + length) else listOf(0 to from + length - 1440, from to 1440)
}
