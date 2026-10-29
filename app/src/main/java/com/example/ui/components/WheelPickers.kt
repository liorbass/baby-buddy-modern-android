package com.example.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.AppText
import com.example.data.model.TimeFormat
import com.example.ui.theme.OnSurface
import com.example.ui.theme.OnSurfaceVariant
import com.example.ui.theme.PrimaryColor
import com.example.ui.theme.PrimaryFixed
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

private val WHEEL_ROW_HEIGHT = 44.dp
private const val WHEEL_VISIBLE_ROWS = 5

// A cyclic wheel is this many laps of its values long, starting in the middle (far more than anyone scrolls)
private const val WHEEL_LAPS = 1000

/**
 * A vertical scroll wheel of [count] values that snaps to the middle row and shows [value].
 * [onSelected] gets the index in the middle whenever it changes (including once at first show), plus how
 * many times a [cyclic] wheel went round since the last call: +1 when scrolling past the last value back
 * to the first (59 → 00), -1 the other way (00 → 59). Changing [value] from outside (e.g. the hour after
 * the minutes went round) scrolls the wheel there without reporting a lap. Tapping a value scrolls to it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WheelPicker(
    count: Int,
    value: Int,
    label: (Int) -> String,
    onSelected: (index: Int, laps: Int) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 64.dp,
    cyclic: Boolean = false
) {
    val last = (count - 1).coerceAtLeast(0)
    val total = if (cyclic) count * WHEEL_LAPS else count
    val base = if (cyclic) count * (WHEEL_LAPS / 2) else 0
    val state = rememberLazyListState(initialFirstVisibleItemIndex = base + value.coerceIn(0, last))
    val fling = rememberSnapFlingBehavior(lazyListState = state)
    val scope = rememberCoroutineScope()
    val rowPx = with(LocalDensity.current) { WHEEL_ROW_HEIGHT.toPx() }
    // With padding of two rows above and below, the first visible item is the one in the middle
    val centered by remember(count) {
        derivedStateOf {
            val offsetRows = if (state.firstVisibleItemScrollOffset > rowPx / 2) 1 else 0
            (state.firstVisibleItemIndex + offsetRows).coerceIn(0, (total - 1).coerceAtLeast(0))
        }
    }
    // The row last reported (laps are counted from it) and whether we're scrolling to an outside value
    var reported by remember { mutableIntStateOf(centered) }
    var following by remember { mutableStateOf(false) }
    val latestOnSelected by rememberUpdatedState(onSelected)
    LaunchedEffect(centered) {
        if (following || count == 0) return@LaunchedEffect
        val laps = Math.floorDiv(centered, count) - Math.floorDiv(reported, count)
        reported = centered
        latestOnSelected(centered % count, laps)
    }
    // Also once a scroll ends, in case the value was changed (e.g. clamped) while it ran
    LaunchedEffect(value, count, state.isScrollInProgress) {
        if (count == 0 || state.isScrollInProgress || value.coerceIn(0, last) == centered % count) return@LaunchedEffect
        // The nearest row showing the new value (across a lap boundary on a cyclic wheel)
        val target = if (cyclic) {
            val diff = Math.floorMod(value - centered % count, count)
            centered + if (diff > count / 2) diff - count else diff
        } else {
            value.coerceIn(0, last)
        }
        following = true
        try {
            state.animateScrollToItem(target)
        } finally {
            reported = target
            following = false
        }
    }

    Box(
        modifier = modifier
            .width(width)
            .height(WHEEL_ROW_HEIGHT * WHEEL_VISIBLE_ROWS),
        contentAlignment = Alignment.Center
    ) {
        // Selection band behind the middle row
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(WHEEL_ROW_HEIGHT)
                .clip(RoundedCornerShape(12.dp))
                .background(PrimaryFixed)
        )
        LazyColumn(
            state = state,
            flingBehavior = fling,
            contentPadding = PaddingValues(vertical = WHEEL_ROW_HEIGHT * (WHEEL_VISIBLE_ROWS / 2)),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            items(total) { row ->
                val selected = row == centered
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(WHEEL_ROW_HEIGHT)
                        .clickable { scope.launch { state.animateScrollToItem(row) } },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label(row % count),
                        style = if (selected) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) PrimaryColor else OnSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@Composable
private fun WheelSeparator(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = OnSurface,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

/** Switches every time/duration picker between wheels and typing; the choice is remembered. */
@Composable
fun InputModeToggle(modifier: Modifier = Modifier) {
    TextButton(onClick = { TimeFormat.useWheel = !TimeFormat.useWheel }, modifier = modifier) {
        Icon(
            if (TimeFormat.useWheel) Icons.Default.Keyboard else Icons.Default.SwapVert,
            contentDescription = null,
            tint = OnSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Text(
            stringResource(if (TimeFormat.useWheel) R.string.type_instead else R.string.use_wheel),
            color = OnSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

// How far the date wheel reaches back and ahead of today
private const val DATE_DAYS_BACK = 730
private const val DATE_DAYS_AHEAD = 7
private const val DAY_MS = 24 * 60 * 60_000L

/** Days since the epoch in the phone's time zone. */
private fun localDay(millis: Long): Long = Math.floorDiv(millis + TimeZone.getDefault().getOffset(millis), DAY_MS)

/**
 * A moment as date | hour : minute (and AM/PM in 12h) wheels, or the keyboard field, with a switch between them.
 * The wheels are one continuous time: minutes going round move the hour, the hour going round moves the date,
 * and the date wheel runs across months and years (two years back to a week ahead). [onChange] gets the new
 * moment, to the minute. Typing sets just the clock time; [fromTyped] turns it into a moment.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClockTimeEntry(millis: Long, onChange: (Long) -> Unit, fromTyped: (hour: Int, minute: Int) -> Long, modifier: Modifier = Modifier) {
    val cal = Calendar.getInstance().apply { timeInMillis = millis }
    val hour = cal.get(Calendar.HOUR_OF_DAY)
    val minute = cal.get(Calendar.MINUTE)
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (TimeFormat.useWheel) {
            val today = remember { localDay(System.currentTimeMillis()) }
            val dateLabel = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
            val yearLabel = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }
            val thisYear = remember { Calendar.getInstance().get(Calendar.YEAR) }

            // Changes the moment, carrying over into larger units; seconds are dropped once anything changes
            fun change(edit: Calendar.() -> Unit) {
                val next = Calendar.getInstance().apply {
                    timeInMillis = millis
                    edit()
                }
                if (next.get(Calendar.MINUTE) != minute || next.get(Calendar.HOUR_OF_DAY) != hour || localDay(next.timeInMillis) != localDay(millis)) {
                    next.set(Calendar.SECOND, 0)
                    next.set(Calendar.MILLISECOND, 0)
                    onChange(next.timeInMillis)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                WheelPicker(
                    count = DATE_DAYS_BACK + DATE_DAYS_AHEAD + 1,
                    value = (localDay(millis) - today + DATE_DAYS_BACK).toInt(),
                    label = { i ->
                        val offset = i - DATE_DAYS_BACK
                        when (offset) {
                            0 -> AppText.get(R.string.today)

                            -1 -> AppText.get(R.string.yesterday)

                            1 -> AppText.get(R.string.tomorrow)

                            else -> {
                                val day = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offset) }
                                (if (day.get(Calendar.YEAR) == thisYear) dateLabel else yearLabel).format(day.time)
                            }
                        }
                    },
                    onSelected = { i, _ ->
                        change {
                            val day = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, i - DATE_DAYS_BACK) }
                            set(day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH))
                        }
                    },
                    width = 128.dp
                )
                if (TimeFormat.use24h) {
                    WheelPicker(
                        count = 24,
                        value = hour,
                        label = { "%02d".format(it) },
                        onSelected = { h, laps ->
                            change {
                                set(Calendar.HOUR_OF_DAY, h)
                                add(Calendar.DAY_OF_YEAR, laps)
                            }
                        },
                        width = 56.dp,
                        cyclic = true
                    )
                } else {
                    // 12, 1 … 11 and AM/PM; going round from 11 to 12 flips AM/PM (and past midnight, the date)
                    WheelPicker(
                        count = 12,
                        value = hour % 12,
                        label = { if (it == 0) "12" else "$it" },
                        onSelected = { h, laps ->
                            change {
                                set(Calendar.HOUR_OF_DAY, h + if (hour >= 12) 12 else 0)
                                add(Calendar.HOUR_OF_DAY, laps * 12)
                            }
                        },
                        width = 56.dp,
                        cyclic = true
                    )
                }
                WheelSeparator(":")
                WheelPicker(
                    count = 60,
                    value = minute,
                    label = { "%02d".format(it) },
                    onSelected = { m, laps ->
                        change {
                            set(Calendar.MINUTE, m)
                            add(Calendar.HOUR_OF_DAY, laps)
                        }
                    },
                    width = 56.dp,
                    cyclic = true
                )
                if (!TimeFormat.use24h) {
                    WheelPicker(
                        count = 2,
                        value = if (hour >= 12) 1 else 0,
                        label = { if (it == 0) "AM" else "PM" },
                        onSelected = { half, _ -> change { set(Calendar.HOUR_OF_DAY, hour % 12 + half * 12) } },
                        modifier = Modifier.padding(start = 4.dp),
                        width = 56.dp
                    )
                }
            }
        } else {
            val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = TimeFormat.use24h)
            LaunchedEffect(state.hour, state.minute) {
                if (state.hour != hour || state.minute != minute) onChange(fromTyped(state.hour, state.minute))
            }
            TimeInput(state = state)
        }
        InputModeToggle()
    }
}

/**
 * A duration as two wheels ([firstLabel] / [secondLabel], e.g. minutes : seconds or hours : minutes).
 * [firstMax] is the largest first value offered. The second wheel goes round, carrying into the first
 * (1:00 back to 0:59), which stays between 0 and [firstMax].
 */
@Composable
fun DurationWheels(
    first: Int,
    second: Int,
    firstMax: Int,
    firstUnit: String,
    secondUnit: String,
    onChange: (first: Int, second: Int) -> Unit
) {
    val firstCount = maxOf(firstMax, first) + 1
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        WheelPicker(
            count = firstCount,
            value = first,
            label = { "$it" },
            onSelected = { f, _ -> onChange(f, second) }
        )
        WheelSeparator(firstUnit)
        WheelPicker(
            count = 60,
            value = second,
            label = { "%02d".format(it) },
            onSelected = { s, laps ->
                val f = first + laps
                when {
                    f < 0 -> onChange(0, 0)

                    // nothing below zero
                    f >= firstCount -> onChange(firstCount - 1, 59)

                    else -> onChange(f, s)
                }
            },
            cyclic = true
        )
        WheelSeparator(secondUnit)
    }
}
