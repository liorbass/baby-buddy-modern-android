package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.DiaperEvent
import com.example.data.model.DiaperType
import com.example.data.model.FeedType
import com.example.data.model.FeedingEvent
import com.example.data.model.GrowthEvent
import com.example.data.model.HistoryDay
import com.example.data.model.NoteEvent
import com.example.data.model.PumpingEvent
import com.example.data.model.SleepEvent
import com.example.data.model.TimeFormat
import com.example.data.model.TrackedItem
import com.example.data.model.TummyTimeEvent
import com.example.ui.HistoryFilter
import com.example.ui.components.EditBathDialog
import com.example.ui.components.EditDiaperDialog
import com.example.ui.components.EditFeedingDialog
import com.example.ui.components.EditGrowthDialog
import com.example.ui.components.EditNoteDialog
import com.example.ui.components.EditPumpingDialog
import com.example.ui.components.EditSleepDialog
import com.example.ui.components.EditTummyTimeDialog
import com.example.ui.theme.ItemColors
import com.example.ui.theme.OnPrimary
import com.example.ui.theme.OnSurface
import com.example.ui.theme.OnSurfaceVariant
import com.example.ui.theme.OutlineVariant
import com.example.ui.theme.PrimaryColor
import com.example.ui.theme.PrimaryFixed
import com.example.ui.theme.SurfaceColor
import com.example.ui.theme.SurfaceContainer
import com.example.ui.theme.SurfaceContainerLowest
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ---- Grid geometry ----
private const val DP_PER_MINUTE = 0.6f
private const val MINUTES_PER_DAY = 24 * 60
private val GRID_HEIGHT: Dp = (MINUTES_PER_DAY * DP_PER_MINUTE).dp // ~864.dp
private val GUTTER_WIDTH: Dp = 40.dp
private val TRAILING_WIDTH: Dp = 32.dp
private val COLUMN_GAP: Dp = 3.dp
private val MIN_TOUCH_HEIGHT: Dp = 12.dp

// ---- Event colors ----
// Item colors (ItemColors.kt, as in the History list); getters so night mode applies
private val NursingColor: Color get() = ItemColors.tint(TrackedItem.BREASTFEEDING)
private val BottleColor: Color get() = ItemColors.tint(TrackedItem.BOTTLE)
private val WetColor: Color get() = ItemColors.tint(TrackedItem.DIAPERS).copy(alpha = 0.4f)
private val DirtyColor: Color get() = ItemColors.tint(TrackedItem.DIAPERS)
private val DryColor: Color get() = OutlineVariant
private val TummyColor: Color get() = ItemColors.tint(TrackedItem.TUMMY_TIME)
private val NightBandColor: Color get() = SurfaceContainer
private val DayBandColor: Color get() = SurfaceContainerLowest
private val TodayTint: Color get() = PrimaryFixed.copy(alpha = 0.35f)

private fun minutesToDp(minutes: Float): Dp = (minutes * DP_PER_MINUTE).dp

private fun minuteOfDay(timeMillis: Long, dayStartMillis: Long): Float = ((timeMillis - dayStartMillis) / 60_000f).coerceIn(0f, MINUTES_PER_DAY.toFloat())

private fun dayEndMillis(dayStartMillis: Long): Long {
    val cal = Calendar.getInstance().apply {
        timeInMillis = dayStartMillis
        add(Calendar.DAY_OF_MONTH, 1)
    }
    return cal.timeInMillis
}

private fun diaperColor(type: DiaperType): Color = when (type) {
    DiaperType.WET -> WetColor
    DiaperType.DIRTY -> DirtyColor
    DiaperType.BOTH -> DirtyColor
    DiaperType.DRY -> DryColor
}

private fun hourLabel(hour: Int): String = if (TimeFormat.use24h) {
    "%02d:00".format(hour % 24)
} else {
    when (hour) {
        0, 24 -> "12 AM"
        6 -> "6 AM"
        12 -> "12 PM"
        18 -> "6 PM"
        else -> (if (hour > 12) hour - 12 else hour).toString()
    }
}

private fun monthLabel(days: List<HistoryDay>): String {
    if (days.isEmpty()) return ""
    val fmt = SimpleDateFormat("MMM", Locale.getDefault())
    val first = fmt.format(Date(days.first().dayStartMillis))
    val last = fmt.format(Date(days.last().dayStartMillis))
    return if (first == last) last else "$first – $last"
}

@Composable
fun HistoryScreen(
    days: List<HistoryDay>?, // null = loading first time; 7 entries oldest→newest
    isLoading: Boolean,
    error: String?,
    isCurrentWeek: Boolean,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onRetry: () -> Unit,
    filter: HistoryFilter,
    onFilterChange: (HistoryFilter) -> Unit,
    onUpdateFeeding: (FeedingEvent) -> Unit,
    onDeleteFeeding: (Long) -> Unit,
    onUpdateDiaper: (DiaperEvent) -> Unit,
    onDeleteDiaper: (Long) -> Unit,
    onUpdateTummyTime: (TummyTimeEvent) -> Unit,
    onDeleteTummyTime: (Long) -> Unit,
    onUpdatePumping: (PumpingEvent) -> Unit,
    onDeletePumping: (Long) -> Unit,
    onUpdateSleep: (SleepEvent) -> Unit = {},
    onDeleteSleep: (Long) -> Unit = {},
    onUpdateNote: (NoteEvent) -> Unit = {}, // notes and baths
    onDeleteNote: (NoteEvent) -> Unit = {},
    growth: List<GrowthEvent> = emptyList(), // every measurement (not loaded per week)
    onUpdateGrowth: (GrowthEvent) -> Unit = {},
    onDeleteGrowth: (GrowthEvent) -> Unit = {},
    hiddenItems: Set<TrackedItem> = emptySet(),
    modifier: Modifier = Modifier
) {
    // Entry types this phone doesn't track are left out of both views
    @Suppress("NAME_SHADOWING")
    val days = remember(days, hiddenItems) { days?.map { it.withoutHidden(hiddenItems) } }
    val filters = historyFiltersFor(hiddenItems)

    @Suppress("NAME_SHADOWING")
    val filter = if (filter in filters) filter else HistoryFilter.ALL
    // The list is the default view; the week calendar is one tap away
    var showCalendar by rememberSaveable { mutableStateOf(false) }
    var editingPumping by remember { mutableStateOf<PumpingEvent?>(null) }
    var editingFeeding by remember { mutableStateOf<FeedingEvent?>(null) }
    var editingDiaper by remember { mutableStateOf<DiaperEvent?>(null) }
    var editingTummy by remember { mutableStateOf<TummyTimeEvent?>(null) }
    var editingSleep by remember { mutableStateOf<SleepEvent?>(null) }
    var editingBath by remember { mutableStateOf<NoteEvent?>(null) }
    var editingNote by remember { mutableStateOf<NoteEvent?>(null) }
    var editingGrowth by remember { mutableStateOf<GrowthEvent?>(null) }

    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            nowMillis = System.currentTimeMillis()
        }
    }

    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    var didInitialScroll by remember { mutableStateOf(false) }
    val hasDays = days != null
    LaunchedEffect(hasDays, showCalendar) {
        if (hasDays && showCalendar && !didInitialScroll) {
            // Put ~6 AM near the top (slightly above so the label is visible).
            val targetPx = with(density) { minutesToDp(6 * 60f - 20f).roundToPx() }
            scrollState.scrollTo(targetPx)
            didInitialScroll = true
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceColor)
    ) {
        // Title row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.nav_history),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = OnSurface,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { showCalendar = !showCalendar }) {
                Icon(
                    imageVector = if (showCalendar) Icons.AutoMirrored.Filled.List else Icons.Default.CalendarMonth,
                    contentDescription = stringResource(if (showCalendar) R.string.history_show_list else R.string.history_show_calendar),
                    tint = OnSurfaceVariant
                )
            }
        }

        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = PrimaryColor,
                trackColor = SurfaceContainer
            )
        } else {
            Spacer(modifier = Modifier.height(2.dp))
        }

        when {
            days == null && error != null -> {
                HistoryErrorState(message = error, onRetry = onRetry)
            }

            days == null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = PrimaryColor)
                }
            }

            !showCalendar -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                ) {
                    HistoryFilterChips(selected = filter, onSelect = onFilterChange, available = filters)
                    // Growth lists every measurement, so weeks don't apply
                    if (filter != HistoryFilter.GROWTH) {
                        HistoryWeekNav(
                            days = days,
                            isCurrentWeek = isCurrentWeek,
                            onPreviousWeek = onPreviousWeek,
                            onNextWeek = onNextWeek
                        )
                    }
                    if (error != null) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onRetry) { Text(stringResource(R.string.retry), color = PrimaryColor) }
                        }
                    }
                    HistoryList(
                        days = days,
                        filter = filter,
                        onFeedingClick = { editingFeeding = it },
                        onDiaperClick = { editingDiaper = it },
                        onTummyClick = { editingTummy = it },
                        onPumpingClick = { editingPumping = it },
                        onSleepClick = { editingSleep = it },
                        onBathClick = { editingBath = it },
                        onNoteClick = { editingNote = it },
                        growth = if (TrackedItem.GROWTH in hiddenItems) emptyList() else growth,
                        onGrowthClick = { editingGrowth = it },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp)
                ) {
                    HistoryLegend()

                    if (error != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onRetry) {
                                Text(stringResource(R.string.retry), color = PrimaryColor)
                            }
                        }
                    }

                    // Month label
                    Text(
                        text = monthLabel(days),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = OnSurfaceVariant,
                        modifier = Modifier.padding(start = GUTTER_WIDTH + 4.dp, top = 4.dp)
                    )

                    WeekHeaderRow(
                        days = days,
                        nowMillis = nowMillis,
                        isCurrentWeek = isCurrentWeek,
                        onPreviousWeek = onPreviousWeek,
                        onNextWeek = onNextWeek
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(scrollState)
                    ) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            HourGutter()
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP)
                            ) {
                                days.forEach { day ->
                                    val isToday = nowMillis >= day.dayStartMillis &&
                                        nowMillis < dayEndMillis(day.dayStartMillis)
                                    DayColumn(
                                        day = day,
                                        isToday = isToday,
                                        nowMillis = nowMillis,
                                        onFeedingClick = { editingFeeding = it },
                                        onDiaperClick = { editingDiaper = it },
                                        onTummyClick = { editingTummy = it },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(TRAILING_WIDTH))
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    editingFeeding?.let { feeding ->
        EditFeedingDialog(
            feeding = feeding,
            onDismiss = { editingFeeding = null },
            onSave = { updated ->
                onUpdateFeeding(updated)
                editingFeeding = null
            },
            onDelete = {
                onDeleteFeeding(feeding.id)
                editingFeeding = null
            }
        )
    }

    editingDiaper?.let { diaper ->
        EditDiaperDialog(
            diaper = diaper,
            onDismiss = { editingDiaper = null },
            onSave = { updated ->
                onUpdateDiaper(updated)
                editingDiaper = null
            },
            onDelete = {
                onDeleteDiaper(diaper.id)
                editingDiaper = null
            }
        )
    }

    editingPumping?.let { pump ->
        EditPumpingDialog(
            pumping = pump,
            onDismiss = { editingPumping = null },
            onSave = {
                onUpdatePumping(it)
                editingPumping = null
            },
            onDelete = {
                onDeletePumping(pump.id)
                editingPumping = null
            }
        )
    }

    editingBath?.let { bath ->
        EditBathDialog(
            bath = bath,
            onDismiss = { editingBath = null },
            onSave = {
                onUpdateNote(it)
                editingBath = null
            },
            onDelete = {
                onDeleteNote(bath)
                editingBath = null
            }
        )
    }
    editingNote?.let { note ->
        EditNoteDialog(
            note = note,
            onDismiss = { editingNote = null },
            onSave = {
                onUpdateNote(it)
                editingNote = null
            },
            onDelete = {
                onDeleteNote(note)
                editingNote = null
            }
        )
    }
    editingGrowth?.let { event ->
        EditGrowthDialog(
            event = event,
            onDismiss = { editingGrowth = null },
            onSave = {
                onUpdateGrowth(it)
                editingGrowth = null
            },
            onDelete = {
                onDeleteGrowth(event)
                editingGrowth = null
            }
        )
    }
    editingSleep?.let { sleep ->
        EditSleepDialog(
            sleep = sleep,
            onDismiss = { editingSleep = null },
            onSave = {
                onUpdateSleep(it)
                editingSleep = null
            },
            onDelete = {
                onDeleteSleep(sleep.id)
                editingSleep = null
            }
        )
    }

    editingTummy?.let { tummy ->
        EditTummyTimeDialog(
            tummyTime = tummy,
            onDismiss = { editingTummy = null },
            onSave = { updated ->
                onUpdateTummyTime(updated)
                editingTummy = null
            },
            onDelete = {
                onDeleteTummyTime(tummy.id)
                editingTummy = null
            }
        )
    }
}

@Composable
private fun HistoryErrorState(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.error_load_history),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = OnSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryColor,
                    contentColor = OnPrimary
                )
            ) {
                Text(stringResource(R.string.retry))
            }
        }
    }
}

@Composable
private fun HistoryLegend() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LegendItem(color = NursingColor, label = stringResource(R.string.history_legend_nursing))
        LegendItem(color = BottleColor, label = stringResource(R.string.log_bottle))
        LegendItem(color = WetColor, label = stringResource(R.string.log_diaper))
        LegendItem(color = TummyColor, label = stringResource(R.string.widget_quick_tummy))
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = OnSurfaceVariant
        )
    }
}

@Composable
private fun WeekHeaderRow(
    days: List<HistoryDay>,
    nowMillis: Long,
    isCurrentWeek: Boolean,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit
) {
    val weekdayFormat = remember { SimpleDateFormat("EE", Locale.getDefault()) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.width(GUTTER_WIDTH),
            contentAlignment = Alignment.CenterStart
        ) {
            IconButton(onClick = onPreviousWeek, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.previous_week),
                    tint = OnSurfaceVariant
                )
            }
        }
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP)
        ) {
            days.forEach { day ->
                val isToday = nowMillis >= day.dayStartMillis &&
                    nowMillis < dayEndMillis(day.dayStartMillis)
                val cal = Calendar.getInstance().apply { timeInMillis = day.dayStartMillis }
                val weekday = weekdayFormat.format(Date(day.dayStartMillis)).take(2)
                val dateNumber = cal.get(Calendar.DAY_OF_MONTH).toString()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isToday) PrimaryFixed else Color.Transparent)
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = weekday,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isToday) PrimaryColor else OnSurfaceVariant,
                        maxLines = 1
                    )
                    Text(
                        text = dateNumber,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isToday) PrimaryColor else OnSurface,
                        maxLines = 1
                    )
                }
            }
        }
        Box(
            modifier = Modifier.width(TRAILING_WIDTH),
            contentAlignment = Alignment.CenterEnd
        ) {
            IconButton(
                onClick = onNextWeek,
                enabled = !isCurrentWeek,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.next_week),
                    tint = if (isCurrentWeek) OutlineVariant else OnSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun HourGutter() {
    Box(
        modifier = Modifier
            .width(GUTTER_WIDTH)
            .height(GRID_HEIGHT)
    ) {
        for (hour in 0..24 step 3) {
            val lineY = minutesToDp(hour * 60f)
            val labelY = when (hour) {
                0 -> 0.dp
                24 -> lineY - 12.dp
                else -> lineY - 6.dp
            }
            Text(
                text = hourLabel(hour),
                fontSize = 9.sp,
                lineHeight = 12.sp,
                color = OnSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = labelY)
                    .padding(end = 5.dp)
            )
        }
    }
}

@Composable
private fun DayColumn(
    day: HistoryDay,
    isToday: Boolean,
    nowMillis: Long,
    onFeedingClick: (FeedingEvent) -> Unit,
    onDiaperClick: (DiaperEvent) -> Unit,
    onTummyClick: (TummyTimeEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    val start = day.dayStartMillis
    Box(
        modifier = modifier
            .height(GRID_HEIGHT)
            .clip(RoundedCornerShape(6.dp))
            .drawBehind {
                val w = size.width
                val h = size.height
                // Day background
                drawRect(color = DayBandColor)
                // Night bands: before 6 AM and after 6 PM
                drawRect(color = NightBandColor, topLeft = Offset(0f, 0f), size = Size(w, h * 0.25f))
                drawRect(color = NightBandColor, topLeft = Offset(0f, h * 0.75f), size = Size(w, h * 0.25f))
                if (isToday) {
                    drawRect(color = TodayTint)
                }
                // Dashed hour lines every 3 hours
                val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()), 0f)
                val stroke = 1.dp.toPx()
                for (i in 1 until 8) {
                    val y = h * (i / 8f)
                    drawLine(
                        color = OutlineVariant.copy(alpha = 0.7f),
                        start = Offset(0f, y),
                        end = Offset(w, y),
                        strokeWidth = stroke,
                        pathEffect = dash
                    )
                }
            }
    ) {
        // Feedings
        day.feedings.forEach { feeding ->
            val startMin = minuteOfDay(feeding.timeMillis, start)
            val isBottle = feeding.amountMl != null || feeding.type != FeedType.NURSING
            if (isBottle) {
                EventBlock(
                    centerMinute = startMin,
                    visualHeight = 8.dp,
                    color = BottleColor,
                    shape = RoundedCornerShape(3.dp),
                    horizontalPadding = 2.dp,
                    onClick = { onFeedingClick(feeding) }
                )
            } else {
                val durMin = (feeding.leftDurationSec + feeding.rightDurationSec) / 60f
                val endMin = (startMin + durMin).coerceAtMost(MINUTES_PER_DAY.toFloat())
                DurationBlock(
                    startMinute = startMin,
                    endMinute = endMin,
                    color = NursingColor,
                    onClick = { onFeedingClick(feeding) }
                )
            }
        }

        // Tummy time
        day.tummyTimes.forEach { tummy ->
            val startMin = minuteOfDay(tummy.timeMillis, start)
            val endMin = (startMin + tummy.durationMinutes).coerceAtMost(MINUTES_PER_DAY.toFloat())
            DurationBlock(
                startMinute = startMin,
                endMinute = endMin,
                color = TummyColor,
                onClick = { onTummyClick(tummy) }
            )
        }

        // Diapers (drawn last so thin bars stay tappable on top)
        day.diapers.forEach { diaper ->
            EventBlock(
                centerMinute = minuteOfDay(diaper.timeMillis, start),
                visualHeight = 4.dp,
                color = diaperColor(diaper.type),
                shape = RoundedCornerShape(2.dp),
                horizontalPadding = 0.dp,
                onClick = { onDiaperClick(diaper) }
            )
        }

        // "Now" indicator
        if (isToday) {
            val nowY = minutesToDp(minuteOfDay(nowMillis, start))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = nowY - 1.dp)
                    .height(2.dp)
                    .background(PrimaryColor)
            )
            Box(
                modifier = Modifier
                    .offset(y = nowY - 4.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(PrimaryColor)
            )
        }
    }
}

/** A block whose height is proportional to its duration (min 6.dp), cut at the end of the day. */
@Composable
private fun DurationBlock(
    startMinute: Float,
    endMinute: Float,
    color: Color,
    onClick: () -> Unit
) {
    val visualHeight = maxOf(minutesToDp(endMinute - startMinute), 6.dp)
    var top = minutesToDp(startMinute)
    if (top + visualHeight > GRID_HEIGHT) top = GRID_HEIGHT - visualHeight
    PositionedEvent(
        visualTop = top,
        visualHeight = visualHeight,
        color = color,
        shape = RoundedCornerShape(4.dp),
        horizontalPadding = 2.dp,
        onClick = onClick
    )
}

/** A fixed-height marker centered on a point in time. */
@Composable
private fun EventBlock(
    centerMinute: Float,
    visualHeight: Dp,
    color: Color,
    shape: Shape,
    horizontalPadding: Dp,
    onClick: () -> Unit
) {
    var top = minutesToDp(centerMinute) - visualHeight / 2
    if (top < 0.dp) top = 0.dp
    if (top + visualHeight > GRID_HEIGHT) top = GRID_HEIGHT - visualHeight
    PositionedEvent(
        visualTop = top,
        visualHeight = visualHeight,
        color = color,
        shape = shape,
        horizontalPadding = horizontalPadding,
        onClick = onClick
    )
}

/**
 * Draws the visible block at [visualTop], wrapped in an invisible touch target that is at least
 * [MIN_TOUCH_HEIGHT] tall and centered on the block, so the visuals don't shift.
 */
@Composable
private fun PositionedEvent(
    visualTop: Dp,
    visualHeight: Dp,
    color: Color,
    shape: Shape,
    horizontalPadding: Dp,
    onClick: () -> Unit
) {
    val touchHeight = maxOf(visualHeight, MIN_TOUCH_HEIGHT)
    val extra = (touchHeight - visualHeight) / 2
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .offset(y = visualTop - extra)
            .height(touchHeight)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding)
                .height(visualHeight)
                .clip(shape)
                .background(color)
        )
    }
}
