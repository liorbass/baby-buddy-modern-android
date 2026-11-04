package com.example.data.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The app-wide 12h/24h clock choice (Settings → This phone). Snapshot state, so composables that
 * format times redraw when it changes; the repository reformats stored `timeFormatted` strings.
 */
object TimeFormat {
    var use24h by mutableStateOf(true)

    /** Time and duration pickers show scroll wheels (default) or keyboard fields; saved by the repository. */
    var useWheel by mutableStateOf(false)

    /** "19:06" or "7:06 PM". */
    fun clock(millis: Long): String = clock(Date(millis))

    fun clock(date: Date): String = SimpleDateFormat(if (use24h) "HH:mm" else "h:mm a", Locale.getDefault()).format(date)

    /**
     * The latest moment at [hour]:[minute] that isn't in the future: today, or yesterday when
     * that time hasn't come yet today. Used for times picked on a clock (a feed start, a diaper change).
     */
    fun latestAt(hour: Int, minute: Int, now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis > now) cal.add(Calendar.DAY_OF_YEAR, -1)
        return cal.timeInMillis
    }

    /** Short hour label for chart axes: "03" or "3a". */
    fun hour(hour: Int): String = if (use24h) {
        "%02d".format(hour)
    } else {
        when (val h = hour % 24) {
            0 -> "12a"
            12 -> "12p"
            else -> if (h < 12) "${h}a" else "${h - 12}p"
        }
    }
}

/** Local midnight at the start of the day containing [millis] (today by default). */
fun startOfDay(millis: Long = System.currentTimeMillis()): Long = java.util.Calendar.getInstance().apply {
    timeInMillis = millis
    set(java.util.Calendar.HOUR_OF_DAY, 0)
    set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0)
    set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis
