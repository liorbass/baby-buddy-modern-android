package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.AppText
import com.example.data.model.BabySex
import com.example.data.model.DayStats
import com.example.data.model.GrowthEvent
import com.example.data.model.GrowthKind
import com.example.data.model.TimeFormat
import com.example.data.model.TrackedItem
import com.example.data.model.WhoGrowthStandards
import com.example.data.model.ageInDays
import com.example.data.model.formatGrowth
import com.example.data.model.growthPercentile
import com.example.data.model.ordinal
import com.example.data.model.ordinalPercentile
import com.example.ui.components.EditGrowthDialog
import com.example.ui.components.SegmentedToggle
import com.example.ui.components.shortDate
import com.example.ui.theme.ItemColors
import com.example.ui.theme.OnPrimary
import com.example.ui.theme.OnSurface
import com.example.ui.theme.OnSurfaceVariant
import com.example.ui.theme.OutlineColor
import com.example.ui.theme.OutlineVariant
import com.example.ui.theme.PrimaryColor
import com.example.ui.theme.PrimaryContainer
import com.example.ui.theme.SecondaryColor
import com.example.ui.theme.SurfaceContainerLow
import com.example.ui.theme.SurfaceContainerLowest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private const val MINUTES_PER_DAY = 1440f
private const val NIGHT_MORNING_END = 360f // 06:00
private const val NIGHT_EVENING_START = 1140f // 19:00
private const val TABULAR_NUMS = "tnum"

private val SectionShape = RoundedCornerShape(20.dp)

@Composable
fun TrendsScreen(
    days: List<DayStats>?, // null = not loaded yet; oldest first, today last
    isLoading: Boolean,
    error: String?, // non-null when loading failed (e.g. not connected)
    rangeDays: Int, // 7 or 30
    onSelectRange: (Int) -> Unit,
    onRetry: () -> Unit,
    onConnect: () -> Unit, // navigate to server settings
    modifier: Modifier = Modifier,
    hiddenItems: Set<TrackedItem> = emptySet(), // charts for untracked items are left out
    growth: List<GrowthEvent> = emptyList(),
    birthDate: String? = null,
    babySex: BabySex? = null,
    onBabySexChange: (BabySex) -> Unit = {},
    onUpdateGrowth: (GrowthEvent) -> Unit = {},
    onDeleteGrowth: (GrowthEvent) -> Unit = {},
    modelsTab: (@Composable () -> Unit)? = null // the Models tab's content, when turned on in Settings
) {
    var showModels by rememberSaveable { mutableStateOf(false) }
    val feedingShown = TrackedItem.BREASTFEEDING !in hiddenItems || TrackedItem.BOTTLE !in hiddenItems
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = stringResource(R.string.nav_trends),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = OnSurface
                )
                if (modelsTab != null) {
                    Segmented(options = listOf(stringResource(R.string.trends_charts), stringResource(R.string.trends_models)), selected = if (showModels) 1 else 0, onSelect = { showModels = it == 1 })
                }
                if (!showModels || modelsTab == null) {
                    RangeSelector(selected = rangeDays, onSelect = onSelectRange)
                }
            }
        }

        if (showModels && modelsTab != null) {
            item(key = "models") { modelsTab() }
        } else {
            when {
                error != null -> item {
                    ErrorCard(message = error, onRetry = onRetry, onConnect = onConnect)
                }

                isLoading || days == null -> item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = PrimaryColor)
                    }
                }

                days.isEmpty() -> item {
                    Text(
                        text = stringResource(R.string.trends_no_data),
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        textAlign = TextAlign.Center
                    )
                }

                else -> {
                    if (feedingShown) {
                        item { FeedingRhythmSection(days = days, rangeDays = rangeDays) }
                    }
                    if (TrackedItem.SLEEP !in hiddenItems && days.any { it.sleepMarks.isNotEmpty() }) {
                        item { SleepRhythmSection(days = days, rangeDays = rangeDays) }
                    }
                    if (TrackedItem.DIAPERS !in hiddenItems && days.any { it.diaperMarks.isNotEmpty() }) {
                        item { DiaperRhythmSection(days = days) }
                    }
                    item { DailyNumbersSection(days = days, hidden = hiddenItems) }
                    val leftTotal = days.sumOf { it.leftMinutes.coerceAtLeast(0) }
                    val rightTotal = days.sumOf { it.rightMinutes.coerceAtLeast(0) }
                    if (leftTotal + rightTotal > 0 && TrackedItem.BREASTFEEDING !in hiddenItems) {
                        item { LeftRightSection(leftMinutes = leftTotal, rightMinutes = rightTotal) }
                    }
                }
            }

            // By age, not by the selected range, so it shows whatever the range or loading state
            if (TrackedItem.GROWTH !in hiddenItems) {
                item(key = "growth") {
                    GrowthSection(
                        growth = growth,
                        birthDate = birthDate,
                        sex = babySex,
                        onSexChange = onBabySexChange,
                        onUpdate = onUpdateGrowth,
                        onDelete = onDeleteGrowth
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Range selector
// ---------------------------------------------------------------------------

@Composable
private fun RangeSelector(selected: Int, onSelect: (Int) -> Unit) {
    val options = listOf(7, 30)
    Segmented(options = options.map { pluralStringResource(R.plurals.days_count, it, it) }, selected = options.indexOf(selected), onSelect = { onSelect(options[it]) })
}

@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(SurfaceContainerLow)
            .padding(4.dp)
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) PrimaryColor else Color.Transparent)
                    .clickable { if (!isSelected) onSelect(index) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = option,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) OnPrimary else OnSurfaceVariant
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Error state
// ---------------------------------------------------------------------------

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit, onConnect: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurface,
                textAlign = TextAlign.Center
            )
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryColor,
                    contentColor = OnPrimary
                )
            ) {
                Text(stringResource(R.string.retry))
            }
            TextButton(onClick = onConnect) {
                Text(stringResource(R.string.trends_server_settings), color = PrimaryColor)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Shared section chrome
// ---------------------------------------------------------------------------

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = OnSurface
    )
}

@Composable
private fun SectionSurface(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(SectionShape)
            .background(SurfaceContainerLowest)
            .border(1.dp, OutlineVariant.copy(alpha = 0.5f), SectionShape),
        content = content
    )
}

// ---------------------------------------------------------------------------
// 1. Feeding rhythm (actogram)
// ---------------------------------------------------------------------------

@Composable
private fun FeedingRhythmSection(days: List<DayStats>, rangeDays: Int) {
    val hasFeeds = days.any { it.feedMarks.isNotEmpty() }
    Column {
        SectionTitle(stringResource(R.string.trends_feeding_rhythm))
        Spacer(modifier = Modifier.height(12.dp))
        SectionSurface {
            Column(modifier = Modifier.padding(16.dp)) {
                if (!hasFeeds) {
                    Text(
                        text = stringResource(R.string.trends_no_feeds),
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceVariant
                    )
                } else {
                    RhythmLegend()
                    Spacer(modifier = Modifier.height(12.dp))
                    RhythmChart(days = days)
                    val caption = rhythmCaption(days, rangeDays)
                    if (caption != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = caption,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFeatureSettings = TABULAR_NUMS
                            ),
                            color = OnSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RhythmLegend() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LegendCapsule(color = NursingColor, label = stringResource(R.string.history_legend_nursing))
        Spacer(modifier = Modifier.width(16.dp))
        LegendCapsule(color = PrimaryColor, label = stringResource(R.string.log_bottle))
        Spacer(modifier = Modifier.width(16.dp))
        Box(
            modifier = Modifier
                .width(14.dp)
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(SecondaryColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.trends_longest_gap),
            style = MaterialTheme.typography.labelSmall,
            color = OnSurfaceVariant
        )
    }
}

@Composable
private fun LegendCapsule(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(width = 12.dp, height = 8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = OnSurfaceVariant
        )
    }
}

/** Horizontal pixel extent of one feed capsule within a row. */
private class CapsuleSpan(val left: Float, val right: Float, val color: Color)

@Composable
private fun RhythmChart(days: List<DayStats>) {
    val dense = days.size > 10
    val rowHeight = if (dense) 9.dp else 14.dp
    val rowGap = if (dense) 2.dp else 3.dp
    val gutter = 44.dp
    val axisGap = 6.dp
    val axisHeight = 16.dp
    // Gap labels need a row tall enough to hold text; on the dense 30-day view the
    // caption under the chart carries the trend instead.
    val showGapLabels = rowHeight >= 12.dp

    val rows = days.asReversed() // newest at top
    val rowCount = rows.size
    val chartHeight = rowHeight * rowCount +
        rowGap * (rowCount - 1).coerceAtLeast(0) +
        axisGap + axisHeight

    val textMeasurer = rememberTextMeasurer()
    val labelSmall = MaterialTheme.typography.labelSmall
    val dayLabelStyle = labelSmall.copy(color = OnSurfaceVariant)
    val todayLabelStyle = labelSmall.copy(color = OnSurface, fontWeight = FontWeight.SemiBold)
    val gapLabelStyle = labelSmall.copy(
        color = SecondaryColor,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = TABULAR_NUMS
    )
    val axisStyle = labelSmall.copy(color = OnSurfaceVariant)
    val hairlineColor = OutlineVariant.copy(alpha = 0.6f)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(chartHeight)
    ) {
        val areaLeft = gutter.toPx()
        val areaWidth = (size.width - areaLeft).coerceAtLeast(1f)
        val areaRight = areaLeft + areaWidth
        val rowH = rowHeight.toPx()
        val gapPx = rowGap.toPx()
        val rowsBottom = rowCount * rowH + (rowCount - 1).coerceAtLeast(0) * gapPx

        fun xOf(minute: Float): Float = areaLeft + areaWidth * (minute.coerceIn(0f, MINUTES_PER_DAY) / MINUTES_PER_DAY)

        fun isNight(minute: Float): Boolean = minute < NIGHT_MORNING_END || minute >= NIGHT_EVENING_START

        // Night shading (00:00-06:00 and 19:00-24:00) behind all rows.
        drawRect(
            color = SurfaceContainerLow,
            topLeft = Offset(areaLeft, 0f),
            size = Size(xOf(NIGHT_MORNING_END) - areaLeft, rowsBottom)
        )
        drawRect(
            color = SurfaceContainerLow,
            topLeft = Offset(xOf(NIGHT_EVENING_START), 0f),
            size = Size(areaRight - xOf(NIGHT_EVENING_START), rowsBottom)
        )

        // Grid lines every 3 h.
        (1..7).map { it * 180f }.forEach { minute ->
            val x = xOf(minute)
            drawLine(
                color = hairlineColor,
                start = Offset(x, 0f),
                end = Offset(x, rowsBottom),
                strokeWidth = 1f
            )
        }

        val minCapsule = 3.dp.toPx()
        val labelPad = 4.dp.toPx()
        val gapStroke = 2.dp.toPx()

        rows.forEachIndexed { index, day ->
            val top = index * (rowH + gapPx)
            val centerY = top + rowH / 2f

            // Day label in the gutter (every row for 7 days; today + weekly for 30 days).
            if (!dense || index % 7 == 0) {
                val text = if (day.isToday) AppText.get(R.string.trends_today_chart) else day.label
                val layout = textMeasurer.measure(
                    text = text,
                    style = if (day.isToday) todayLabelStyle else dayLabelStyle,
                    maxLines = 1,
                    softWrap = false
                )
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(0f, centerY - layout.size.height / 2f)
                )
            }

            // Feed capsules.
            val capsules = day.feedMarks
                .map { mark ->
                    val start = mark.startMinute.toFloat().coerceIn(0f, MINUTES_PER_DAY)
                    val width = (areaWidth * mark.durationMinutes.coerceAtLeast(0) / MINUTES_PER_DAY)
                        .coerceAtLeast(minCapsule)
                    val right = (xOf(start) + width).coerceAtMost(areaRight)
                    val left = (right - width).coerceAtLeast(areaLeft)
                    CapsuleSpan(
                        left = left,
                        right = right,
                        color = if (mark.isBottle) BottleColor else NursingColor
                    )
                }
                .sortedBy { it.left }

            capsules.forEach { c ->
                val w = (c.right - c.left).coerceAtLeast(1f)
                drawRoundRect(
                    color = c.color,
                    topLeft = Offset(c.left, top),
                    size = Size(w, rowH),
                    cornerRadius = CornerRadius(minOf(w, rowH) / 2f)
                )
            }

            // Longest gap line + label.
            val gapStart = day.longestGapStartMinute
            if (gapStart != null && day.longestGapMinutes > 0) {
                val gapStartMin = gapStart.toFloat()
                val gapEndMin = (gapStart + day.longestGapMinutes).toFloat()
                val reachesEnd = gapEndMin >= MINUTES_PER_DAY
                val lineStart = xOf(gapStartMin)
                val lineEnd = xOf(gapEndMin)
                drawLine(
                    color = SecondaryColor,
                    start = Offset(lineStart, centerY),
                    end = Offset(lineEnd, centerY),
                    strokeWidth = gapStroke,
                    cap = StrokeCap.Round
                )

                if (showGapLabels) {
                    val layout = textMeasurer.measure(
                        text = formatDuration(day.longestGapMinutes),
                        style = gapLabelStyle,
                        maxLines = 1,
                        softWrap = false
                    )
                    val textWidth = layout.size.width.toFloat()
                    val textHeight = layout.size.height.toFloat()

                    var textX = Float.NaN
                    var needsBackdrop = false

                    if (!reachesEnd) {
                        // Right after the line end, skipping the feed(s) that close the gap.
                        var after = lineEnd
                        capsules.forEach { c ->
                            if (c.left <= after + 1f && c.right > after) after = c.right
                        }
                        val candidate = after + labelPad
                        val fits = candidate + textWidth <= areaRight &&
                            capsules.none { c -> c.right > candidate && c.left < candidate + textWidth }
                        if (fits) {
                            textX = candidate
                        } else if (lineEnd - lineStart >= textWidth + 4f * labelPad) {
                            // Centered on the line itself.
                            textX = (lineStart + lineEnd) / 2f - textWidth / 2f
                            needsBackdrop = true
                        }
                    }
                    if (textX.isNaN()) {
                        // Right-aligned at the row end.
                        textX = areaRight - textWidth
                        needsBackdrop = true
                    }

                    if (needsBackdrop) {
                        val midMinute =
                            ((textX + textWidth / 2f - areaLeft) / areaWidth) * MINUTES_PER_DAY
                        val backdrop =
                            if (isNight(midMinute)) SurfaceContainerLow else SurfaceContainerLowest
                        drawRoundRect(
                            color = backdrop,
                            topLeft = Offset(textX - labelPad, top),
                            size = Size(textWidth + labelPad * 2f, rowH),
                            cornerRadius = CornerRadius(rowH / 2f)
                        )
                    }
                    drawText(
                        textLayoutResult = layout,
                        topLeft = Offset(textX, centerY - textHeight / 2f)
                    )
                }
            }
        }

        // Hour axis aligned to the row area.
        val axisTop = rowsBottom + axisGap.toPx()
        val axisLabels = (0..8).map { TimeFormat.hour(it * 3) } // 00, 03 … 24 or 12a, 3a … 12a
        axisLabels.forEachIndexed { i, label ->
            val layout = textMeasurer.measure(
                text = label,
                style = axisStyle,
                maxLines = 1,
                softWrap = false
            )
            val x = areaLeft + areaWidth * i / (axisLabels.size - 1).toFloat()
            val labelX = when (i) {
                0 -> x
                axisLabels.lastIndex -> x - layout.size.width
                else -> x - layout.size.width / 2f
            }
            drawText(textLayoutResult = layout, topLeft = Offset(labelX, axisTop))
        }
    }
}

/** "Longest stretch: 5h 18m on average this week, up 22 min from the start of the week." */
private fun rhythmCaption(days: List<DayStats>, rangeDays: Int): String? {
    val basis = averageBasis(days)
    val gaps = basis.map { it.longestGapMinutes }.filter { it > 0 }
    if (gaps.isEmpty()) return null
    val week = rangeDays <= 7
    val average = gaps.average().roundToInt()
    val caption = StringBuilder(AppText.get(if (week) R.string.trends_longest_week else R.string.trends_longest_month, formatDuration(average)))
    if (gaps.size >= 6) {
        val half = gaps.size / 2
        val firstHalf = gaps.take(half).average()
        val secondHalf = gaps.takeLast(half).average()
        val diff = (secondHalf - firstHalf).roundToInt()
        if (abs(diff) >= 5) {
            val change = when {
                diff > 0 && week -> R.string.trends_up_week
                diff > 0 -> R.string.trends_up_month
                week -> R.string.trends_down_week
                else -> R.string.trends_down_month
            }
            caption.append(AppText.get(change, formatDiff(abs(diff))))
        }
    }
    caption.append('.')
    return caption.toString()
}

// ---------------------------------------------------------------------------
// 1b. Sleep and diaper rhythm (same day-by-hour grid as the feeding chart)
// ---------------------------------------------------------------------------

// Item colors (ItemColors.kt), as getters so night mode applies
private val NursingColor: Color get() = ItemColors.tint(TrackedItem.BREASTFEEDING)
private val BottleColor: Color get() = ItemColors.tint(TrackedItem.BOTTLE)
private val NightSleepColor: Color get() = ItemColors.tint(TrackedItem.SLEEP)
private val NapColor: Color get() = ItemColors.tint(TrackedItem.SLEEP).copy(alpha = 0.45f)
private val DirtyColor: Color get() = ItemColors.tint(TrackedItem.DIAPERS)
private val WetColor: Color get() = ItemColors.tint(TrackedItem.DIAPERS).copy(alpha = 0.4f)

/** Where one day's row sits in a [DayTimeChart]. */
private class RowGeometry(val top: Float, val height: Float, val areaLeft: Float, val areaRight: Float) {
    val centerY: Float get() = top + height / 2f
    fun xOf(minute: Float): Float = areaLeft + (areaRight - areaLeft) * (minute.coerceIn(0f, MINUTES_PER_DAY) / MINUTES_PER_DAY)
}

/**
 * One row per day (newest at top) across 24 hours, with night shading, 3-hour grid lines,
 * day labels and the hour axis. [drawRow] draws a day's marks.
 */
@Composable
private fun DayTimeChart(days: List<DayStats>, drawRow: DrawScope.(DayStats, RowGeometry) -> Unit) {
    val dense = days.size > 10
    val rowHeight = if (dense) 9.dp else 14.dp
    val rowGap = if (dense) 2.dp else 3.dp
    val gutter = 44.dp
    val axisGap = 6.dp
    val axisHeight = 16.dp

    val rows = days.asReversed()
    val rowCount = rows.size
    val chartHeight = rowHeight * rowCount + rowGap * (rowCount - 1).coerceAtLeast(0) + axisGap + axisHeight

    val textMeasurer = rememberTextMeasurer()
    val labelSmall = MaterialTheme.typography.labelSmall
    val dayLabelStyle = labelSmall.copy(color = OnSurfaceVariant)
    val todayLabelStyle = labelSmall.copy(color = OnSurface, fontWeight = FontWeight.SemiBold)
    val axisStyle = labelSmall.copy(color = OnSurfaceVariant)
    val hairlineColor = OutlineVariant.copy(alpha = 0.6f)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(chartHeight)
    ) {
        val areaLeft = gutter.toPx()
        val areaRight = size.width.coerceAtLeast(areaLeft + 1f)
        val rowH = rowHeight.toPx()
        val gapPx = rowGap.toPx()
        val rowsBottom = rowCount * rowH + (rowCount - 1).coerceAtLeast(0) * gapPx
        val axis = RowGeometry(0f, rowsBottom, areaLeft, areaRight)

        drawRect(
            color = SurfaceContainerLow,
            topLeft = Offset(areaLeft, 0f),
            size = Size(axis.xOf(NIGHT_MORNING_END) - areaLeft, rowsBottom)
        )
        drawRect(
            color = SurfaceContainerLow,
            topLeft = Offset(axis.xOf(NIGHT_EVENING_START), 0f),
            size = Size(areaRight - axis.xOf(NIGHT_EVENING_START), rowsBottom)
        )
        (1..7).map { it * 180f }.forEach { minute ->
            val x = axis.xOf(minute)
            drawLine(color = hairlineColor, start = Offset(x, 0f), end = Offset(x, rowsBottom), strokeWidth = 1f)
        }

        rows.forEachIndexed { index, day ->
            val row = RowGeometry(index * (rowH + gapPx), rowH, areaLeft, areaRight)
            if (!dense || index % 7 == 0) {
                val layout = textMeasurer.measure(
                    text = if (day.isToday) AppText.get(R.string.trends_today_chart) else day.label,
                    style = if (day.isToday) todayLabelStyle else dayLabelStyle,
                    maxLines = 1,
                    softWrap = false
                )
                drawText(textLayoutResult = layout, topLeft = Offset(0f, row.centerY - layout.size.height / 2f))
            }
            drawRow(day, row)
        }

        val axisTop = rowsBottom + axisGap.toPx()
        val axisLabels = (0..8).map { TimeFormat.hour(it * 3) }
        axisLabels.forEachIndexed { i, label ->
            val layout = textMeasurer.measure(text = label, style = axisStyle, maxLines = 1, softWrap = false)
            val x = areaLeft + (areaRight - areaLeft) * i / (axisLabels.size - 1).toFloat()
            val labelX = when (i) {
                0 -> x
                axisLabels.lastIndex -> x - layout.size.width
                else -> x - layout.size.width / 2f
            }
            drawText(textLayoutResult = layout, topLeft = Offset(labelX, axisTop))
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String, ring: Color? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(ring ?: color)
                .padding(if (ring != null) 2.dp else 0.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = OnSurfaceVariant)
    }
}

@Composable
private fun SleepRhythmSection(days: List<DayStats>, rangeDays: Int) {
    Column {
        SectionTitle(stringResource(R.string.trends_sleep_rhythm))
        Spacer(modifier = Modifier.height(12.dp))
        SectionSurface {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LegendCapsule(color = NightSleepColor, label = stringResource(R.string.dialog_night_sleep))
                    Spacer(modifier = Modifier.width(16.dp))
                    LegendCapsule(color = NapColor, label = stringResource(R.string.nap))
                }
                Spacer(modifier = Modifier.height(12.dp))
                DayTimeChart(days = days) { day, row ->
                    val minWidth = 3.dp.toPx()
                    day.sleepMarks.forEach { mark ->
                        val left = row.xOf(mark.startMinute.toFloat())
                        val right = row.xOf((mark.startMinute + mark.durationMinutes).toFloat())
                        val w = (right - left).coerceAtLeast(minWidth)
                        drawRoundRect(
                            color = if (mark.isNap) NapColor else NightSleepColor,
                            topLeft = Offset(left.coerceAtMost(row.areaRight - w), row.top),
                            size = Size(w, row.height),
                            cornerRadius = CornerRadius(minOf(w, row.height) / 2f)
                        )
                    }
                }
                sleepCaption(days, rangeDays)?.let { caption ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = TABULAR_NUMS),
                        color = OnSurface
                    )
                }
            }
        }
    }
}

/** "Sleep: 14h 10m a day on average this week, longest stretch 5h 20m." */
private fun sleepCaption(days: List<DayStats>, rangeDays: Int): String? {
    val basis = averageBasis(days).filter { it.sleepMinutes > 0 }
    if (basis.isEmpty()) return null
    val week = rangeDays <= 7
    val average = basis.map { it.sleepMinutes }.average().roundToInt()
    val longest = basis.map { it.longestSleepMinutes }.filter { it > 0 }
    val caption = StringBuilder(AppText.get(if (week) R.string.trends_sleep_week else R.string.trends_sleep_month, formatDuration(average)))
    if (longest.isNotEmpty()) caption.append(AppText.get(R.string.trends_sleep_longest, formatDuration(longest.average().roundToInt())))
    return caption.append('.').toString()
}

@Composable
private fun DiaperRhythmSection(days: List<DayStats>) {
    Column {
        SectionTitle(stringResource(R.string.trends_diaper_rhythm))
        Spacer(modifier = Modifier.height(12.dp))
        SectionSurface {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LegendDot(color = WetColor, label = stringResource(R.string.diaper_wet))
                    Spacer(modifier = Modifier.width(16.dp))
                    LegendDot(color = DirtyColor, label = stringResource(R.string.diaper_dirty))
                    Spacer(modifier = Modifier.width(16.dp))
                    LegendDot(color = DirtyColor, label = stringResource(R.string.diaper_both), ring = WetColor)
                }
                Spacer(modifier = Modifier.height(12.dp))
                DayTimeChart(days = days) { day, row ->
                    val radius = minOf(row.height / 2f, 4.5.dp.toPx())
                    val ring = 1.5.dp.toPx()
                    day.diaperMarks.forEach { mark ->
                        val center = Offset(
                            row.xOf(mark.minute.toFloat()).coerceIn(row.areaLeft + radius, row.areaRight - radius),
                            row.centerY
                        )
                        when {
                            mark.wet && mark.dirty -> {
                                drawCircle(color = WetColor, radius = radius, center = center)
                                drawCircle(color = DirtyColor, radius = (radius - ring).coerceAtLeast(1f), center = center)
                            }

                            mark.dirty -> drawCircle(color = DirtyColor, radius = radius, center = center)

                            mark.wet -> drawCircle(color = WetColor, radius = radius, center = center)

                            else -> drawCircle(
                                color = OutlineColor,
                                radius = radius - ring / 2f,
                                center = center,
                                style = Stroke(width = ring)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 2. Daily numbers
// ---------------------------------------------------------------------------

private class DailyMetric(
    val name: String,
    val unit: String,
    val decimals: Int,
    val color: Color,
    val reference: Float?,
    val value: (DayStats) -> Float
)

@Composable
private fun DailyNumbersSection(days: List<DayStats>, hidden: Set<TrackedItem> = emptySet()) {
    val showBottle = days.any { it.bottleMl > 0 } && TrackedItem.BOTTLE !in hidden
    val metrics = buildList {
        if (TrackedItem.BREASTFEEDING !in hidden || TrackedItem.BOTTLE !in hidden) {
            add(DailyMetric(stringResource(R.string.trends_metric_feeds), stringResource(R.string.trends_unit_per_day), 1, PrimaryColor, null) { it.feeds.toFloat() })
        }
        if (TrackedItem.BREASTFEEDING !in hidden) {
            add(DailyMetric(stringResource(R.string.history_legend_nursing), stringResource(R.string.trends_unit_min_day), 0, PrimaryColor, null) { it.nursingMinutes.toFloat() })
        }
        if (showBottle) {
            add(DailyMetric(stringResource(R.string.log_bottle), stringResource(R.string.trends_unit_ml_day), 0, BottleColor, null) { it.bottleMl.toFloat() })
        }
        if (TrackedItem.DIAPERS !in hidden) {
            add(DailyMetric(stringResource(R.string.trends_metric_wet), stringResource(R.string.trends_unit_per_day), 1, DirtyColor, 6f) { it.wet.toFloat() })
            add(DailyMetric(stringResource(R.string.trends_metric_dirty), stringResource(R.string.trends_unit_per_day), 1, DirtyColor, null) { it.dirty.toFloat() })
        }
        if (TrackedItem.SLEEP !in hidden && days.any { it.sleepMarks.isNotEmpty() }) {
            add(DailyMetric(stringResource(R.string.item_sleep), stringResource(R.string.trends_unit_h_day), 1, NightSleepColor, null) { it.sleepMinutes / 60f })
            add(DailyMetric(stringResource(R.string.trends_metric_naps), stringResource(R.string.trends_unit_per_day), 1, NightSleepColor, null) { it.naps.toFloat() })
        }
    }

    if (metrics.isEmpty()) return

    Column {
        SectionTitle(stringResource(R.string.trends_daily_numbers))
        Spacer(modifier = Modifier.height(12.dp))
        SectionSurface {
            metrics.forEachIndexed { index, metric ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        thickness = 1.dp,
                        color = OutlineVariant.copy(alpha = 0.4f)
                    )
                }
                MetricRow(metric = metric, days = days)
            }
        }
    }
}

@Composable
private fun MetricRow(metric: DailyMetric, days: List<DayStats>) {
    val values = days.map { metric.value(it) }
    val basisValues = averageBasis(days).map { metric.value(it) }
    val average = if (basisValues.isEmpty()) 0f else basisValues.average().toFloat()
    val lastIsPartial = days.lastOrNull()?.isToday == true

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = metric.name,
                style = MaterialTheme.typography.bodyLarge,
                color = OnSurface,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .alignByBaseline()
            )
            Text(
                text = formatNumber(average, metric.decimals),
                style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = TABULAR_NUMS),
                fontWeight = FontWeight.Bold,
                color = OnSurface,
                maxLines = 1,
                modifier = Modifier.alignByBaseline()
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = metric.unit,
                style = MaterialTheme.typography.labelMedium,
                color = OnSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.alignByBaseline()
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Sparkline(
            values = values,
            average = average,
            lastIsPartial = lastIsPartial,
            color = metric.color,
            reference = metric.reference,
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
        )
    }
}

@Composable
private fun Sparkline(
    values: List<Float>,
    average: Float,
    lastIsPartial: Boolean,
    color: Color,
    reference: Float?,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val referenceStyle = MaterialTheme.typography.labelSmall.copy(
        color = color,
        fontFeatureSettings = TABULAR_NUMS
    )
    val dense = values.size > 10

    Canvas(modifier = modifier) {
        if (values.isEmpty()) return@Canvas

        val dotRadius = (if (dense) 1.75.dp else 2.5.dp).toPx()
        val inset = dotRadius + 1.dp.toPx()
        val left = inset
        // Room on the right for the reference label; kept on every row so x positions line up.
        val right = (size.width - 16.dp.toPx()).coerceAtLeast(left + 1f)
        val top = inset
        val bottom = (size.height - inset).coerceAtLeast(top + 1f)
        val maxValue = maxOf(values.maxOrNull() ?: 0f, average, reference ?: 0f).coerceAtLeast(1f)

        fun yOf(v: Float): Float = bottom - (bottom - top) * (v.coerceAtLeast(0f) / maxValue)
        fun xOf(i: Int): Float = if (values.size == 1) {
            (left + right) / 2f
        } else {
            left + (right - left) * i / (values.size - 1).toFloat()
        }

        val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()), 0f)
        val thin = 1.dp.toPx()

        // Faint dashed average.
        if (average > 0f) {
            val y = yOf(average)
            drawLine(
                color = OutlineColor.copy(alpha = 0.45f),
                start = Offset(left, y),
                end = Offset(right, y),
                strokeWidth = thin,
                pathEffect = dash
            )
        }

        // Reference line (e.g. 6 wet diapers) labeled at the right edge.
        if (reference != null) {
            val y = yOf(reference)
            drawLine(
                color = color.copy(alpha = 0.6f),
                start = Offset(left, y),
                end = Offset(right, y),
                strokeWidth = thin,
                pathEffect = dash
            )
            val layout = textMeasurer.measure(
                text = formatNumber(reference, 0),
                style = referenceStyle,
                maxLines = 1,
                softWrap = false
            )
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(size.width - layout.size.width, y - layout.size.height / 2f)
            )
        }

        // Value line.
        if (values.size > 1) {
            val path = Path()
            values.forEachIndexed { i, v ->
                val x = xOf(i)
                val y = yOf(v)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(
                    width = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }

        // Dots; today's (partial day) is hollow.
        values.forEachIndexed { i, v ->
            val center = Offset(xOf(i), yOf(v))
            if (lastIsPartial && i == values.lastIndex) {
                drawCircle(color = SurfaceContainerLowest, radius = dotRadius, center = center)
                drawCircle(
                    color = color,
                    radius = dotRadius,
                    center = center,
                    style = Stroke(width = 1.25.dp.toPx())
                )
            } else {
                drawCircle(color = color, radius = dotRadius, center = center)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 3. Left and right
// ---------------------------------------------------------------------------

@Composable
private fun LeftRightSection(leftMinutes: Int, rightMinutes: Int) {
    val total = (leftMinutes + rightMinutes).coerceAtLeast(1)
    val leftPct = (leftMinutes * 100f / total).roundToInt()
    val rightPct = 100 - leftPct
    val shareStyle = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = TABULAR_NUMS)
    val minutesStyle = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = TABULAR_NUMS)

    Column {
        SectionTitle(stringResource(R.string.trends_left_right))
        Spacer(modifier = Modifier.height(12.dp))
        SectionSurface {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                ) {
                    if (leftMinutes > 0) {
                        Box(
                            modifier = Modifier
                                .weight(leftMinutes.toFloat())
                                .fillMaxHeight()
                                .background(PrimaryColor)
                        )
                    }
                    if (leftMinutes > 0 && rightMinutes > 0) {
                        Spacer(modifier = Modifier.width(2.dp))
                    }
                    if (rightMinutes > 0) {
                        Box(
                            modifier = Modifier
                                .weight(rightMinutes.toFloat())
                                .fillMaxHeight()
                                .background(PrimaryContainer)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.trends_left_pct, leftPct),
                            style = shareStyle,
                            fontWeight = FontWeight.SemiBold,
                            color = OnSurface
                        )
                        Text(
                            text = formatDuration(leftMinutes),
                            style = minutesStyle,
                            color = OnSurfaceVariant
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = stringResource(R.string.trends_right_pct, rightPct),
                            style = shareStyle,
                            fontWeight = FontWeight.SemiBold,
                            color = OnSurface
                        )
                        Text(
                            text = formatDuration(rightMinutes),
                            style = minutesStyle,
                            color = OnSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Days used for averages: today (a partial day) is excluded when there is more than one day. */
private fun averageBasis(days: List<DayStats>): List<DayStats> = if (days.size > 1) days.filter { !it.isToday } else days

private fun formatNumber(value: Float, decimals: Int): String = if (decimals <= 0) {
    value.roundToInt().toString()
} else {
    String.format(Locale.getDefault(), "%.${decimals}f", value)
}

/** 318 -> "5h 18m", 45 -> "45m", 300 -> "5h". */
private fun formatDuration(minutes: Int): String {
    val m = minutes.coerceAtLeast(0)
    val h = m / 60
    val r = m % 60
    return when {
        h == 0 -> AppText.get(R.string.duration_minutes, r)
        r == 0 -> AppText.get(R.string.duration_hours, h)
        else -> AppText.get(R.string.duration_hours_minutes_short, h, r)
    }
}

/** Size of a change: 22 -> "22 min", 75 -> "1h 15m". */
private fun formatDiff(minutes: Int): String = if (minutes < 60) AppText.get(R.string.amount_minutes, minutes) else formatDuration(minutes)

// ---------------------------------------------------------------------------
// Growth against the WHO Child Growth Standards
// ---------------------------------------------------------------------------

private const val DAYS_PER_MONTH = 30.4375

@Composable
private fun GrowthSection(
    growth: List<GrowthEvent>,
    birthDate: String?,
    sex: BabySex?,
    onSexChange: (BabySex) -> Unit,
    onUpdate: (GrowthEvent) -> Unit,
    onDelete: (GrowthEvent) -> Unit
) {
    var kindIndex by rememberSaveable { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<GrowthEvent?>(null) }
    editing?.let { event ->
        EditGrowthDialog(
            event = event,
            onDismiss = { editing = null },
            onSave = {
                onUpdate(it)
                editing = null
            },
            onDelete = {
                onDelete(event)
                editing = null
            }
        )
    }
    val kind = GrowthKind.entries[kindIndex]
    val points = growth.filter { it.kind == kind }.sortedBy { it.dateMillis }
    val ageNow = birthDate?.let { ageInDays(it, System.currentTimeMillis()) }
    Column {
        SectionTitle(stringResource(R.string.item_growth))
        Spacer(modifier = Modifier.height(12.dp))
        SectionSurface {
            Column(modifier = Modifier.padding(16.dp)) {
                SegmentedToggle(
                    options = GrowthKind.entries.map { it.shortLabel },
                    selectedIndex = kindIndex,
                    onSelect = { kindIndex = it }
                )
                Spacer(modifier = Modifier.height(16.dp))
                when {
                    sex == null -> {
                        Text(
                            text = stringResource(R.string.trends_pick_sex),
                            style = MaterialTheme.typography.bodyMedium,
                            color = OnSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        SegmentedToggle(
                            options = BabySex.entries.map { it.label },
                            selectedIndex = -1,
                            onSelect = { onSexChange(BabySex.entries[it]) }
                        )
                    }

                    birthDate == null || ageNow == null -> Text(
                        text = stringResource(R.string.trends_need_birth_date),
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceVariant
                    )

                    else -> {
                        GrowthChart(kind = kind, sex = sex, birthDate = birthDate, ageNow = ageNow, points = points, onPointClick = { editing = it })
                        Spacer(modifier = Modifier.height(12.dp))
                        GrowthLegend()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = growthCaption(kind, points, birthDate, sex),
                            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = TABULAR_NUMS),
                            color = OnSurface
                        )
                    }
                }
            }
        }
    }
}

private fun growthCaption(kind: GrowthKind, points: List<GrowthEvent>, birthDate: String, sex: BabySex): String {
    val latest = points.lastOrNull()
        ?: return AppText.get(R.string.trends_growth_none, kind.label.lowercase())
    val p = growthPercentile(latest, birthDate, sex)
        ?: return AppText.get(R.string.trends_growth_latest_no_percentile, formatGrowth(kind, latest.value), shortDate(latest.dateMillis))
    val previous = points.dropLast(1).lastOrNull()?.let { prev ->
        growthPercentile(prev, birthDate, sex)?.let { AppText.get(R.string.trends_growth_previous, ordinalPercentile(it), shortDate(prev.dateMillis)) }
    }.orEmpty()
    return AppText.get(R.string.trends_growth_latest, formatGrowth(kind, latest.value), shortDate(latest.dateMillis), ordinalPercentile(p), previous)
}

@Composable
private fun GrowthLegend() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LegendDot(color = PrimaryColor, label = stringResource(R.string.profile_baby))
        Spacer(modifier = Modifier.width(16.dp))
        LegendCapsule(color = ItemColors.soft(TrackedItem.GROWTH), label = "15th–85th")
        Spacer(modifier = Modifier.width(16.dp))
        LegendCapsule(color = ItemColors.soft(TrackedItem.GROWTH).copy(alpha = 0.45f), label = "3rd–97th")
    }
    Text(
        text = stringResource(R.string.trends_who_median, ordinal(50)),
        style = MaterialTheme.typography.labelSmall,
        color = OnSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )
}

/** WHO percentile curves (3rd, 15th, 50th, 85th, 97th) by age in months, with the baby's measurements on top. */
@Composable
private fun GrowthChart(
    kind: GrowthKind,
    sex: BabySex,
    birthDate: String,
    ageNow: Double,
    points: List<GrowthEvent>,
    onPointClick: (GrowthEvent) -> Unit
) {
    val charted = points.mapNotNull { e ->
        ageInDays(birthDate, e.dateMillis)?.takeIf { it <= WhoGrowthStandards.MAX_DAYS }?.let { e to it }
    }
    val dataPts = charted.map { (e, day) -> day to e.value }
    // Birth to a little past now (at least 3 months, at most 2 years), in whole months
    val furthest = maxOf(ageNow, dataPts.maxOfOrNull { it.first } ?: 0.0)
    val months = ceil(maxOf(furthest * 1.25, 91.0) / DAYS_PER_MONTH).coerceAtMost(24.0)
    val xMax = minOf(months * DAYS_PER_MONTH, WhoGrowthStandards.MAX_DAYS.toDouble())
    val samples = (0..60).map { xMax * it / 60 }
    val curves = WhoGrowthStandards.CURVES.map { (pct, z) ->
        pct to samples.map { WhoGrowthStandards.valueAt(kind, sex, it, z) }
    }
    val yStep = when (kind) {
        GrowthKind.WEIGHT -> if (curves.last().second.last() > 10) 2.0 else 1.0
        GrowthKind.LENGTH -> 5.0
        GrowthKind.HEAD -> 2.0
    }
    val yMin = floor(minOf(curves.first().second.min(), dataPts.minOfOrNull { it.second } ?: Double.MAX_VALUE) / yStep) * yStep
    val yMax = ceil(maxOf(curves.last().second.max(), dataPts.maxOfOrNull { it.second } ?: 0.0) / yStep) * yStep

    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = OnSurfaceVariant)
    val grid = OutlineVariant.copy(alpha = 0.5f)
    val inner = ItemColors.soft(TrackedItem.GROWTH)
    val outer = inner.copy(alpha = 0.45f)
    val curveColor = OutlineColor
    val medianColor = OnSurfaceVariant
    val babyColor = PrimaryColor
    val ringColor = SurfaceContainerLowest

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            // Tap a measurement (the nearest one within reach) to edit it
            .pointerInput(charted, xMax, yMin, yMax) {
                detectTapGestures { tap ->
                    // Same insets as the drawing below
                    val w = size.width - 34.dp.toPx() - 26.dp.toPx()
                    val h = size.height - 8.dp.toPx() - 20.dp.toPx()
                    fun at(day: Double, v: Double) = Offset(34.dp.toPx() + (day / xMax * w).toFloat(), 8.dp.toPx() + ((yMax - v) / (yMax - yMin) * h).toFloat())
                    charted.map { (e, day) -> e to (at(day, e.value) - tap).getDistance() }
                        .filter { it.second <= 28.dp.toPx() }
                        .minByOrNull { it.second }
                        ?.let { onPointClick(it.first) }
                }
            }
    ) {
        val left = 34.dp.toPx()
        val right = 26.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = 20.dp.toPx()
        val w = size.width - left - right
        val h = size.height - top - bottom
        fun px(day: Double) = left + (day / xMax * w).toFloat()
        fun py(v: Double) = top + ((yMax - v) / (yMax - yMin) * h).toFloat()
        fun label(text: String, x: Float, yCenter: Float, alignEnd: Boolean = false, centered: Boolean = false) {
            val r = measurer.measure(text, labelStyle)
            val lx = when {
                alignEnd -> x - r.size.width
                centered -> x - r.size.width / 2f
                else -> x
            }
            drawText(r, topLeft = Offset(lx, yCenter - r.size.height / 2f))
        }

        // Grid: values on the left, months along the bottom
        var v = yMin
        while (v <= yMax + 1e-9) {
            drawLine(grid, Offset(left, py(v)), Offset(left + w, py(v)), strokeWidth = 1f)
            label(if (yStep >= 1) "${v.roundToInt()}" else "$v", left - 6.dp.toPx(), py(v), alignEnd = true)
            v += yStep
        }
        val every = when {
            months > 12 -> 3
            months > 6 -> 2
            else -> 1
        }
        for (m in 0..months.toInt() step every) {
            val x = px(m * DAYS_PER_MONTH)
            drawLine(grid, Offset(x, top), Offset(x, top + h), strokeWidth = 1f)
            label(if (m == 0) AppText.get(R.string.trends_birth) else AppText.get(R.string.trends_months_short, m), x, top + h + bottom / 2f, centered = true)
        }

        // Bands between the outer and inner percentile pairs
        fun band(lo: List<Double>, hi: List<Double>, color: Color) {
            val path = Path()
            samples.forEachIndexed { i, d -> if (i == 0) path.moveTo(px(d), py(hi[i])) else path.lineTo(px(d), py(hi[i])) }
            for (i in samples.indices.reversed()) path.lineTo(px(samples[i]), py(lo[i]))
            path.close()
            drawPath(path, color)
        }
        band(curves[0].second, curves[4].second, outer)
        band(curves[1].second, curves[3].second, inner)

        curves.forEachIndexed { index, (pct, values) ->
            val path = Path()
            samples.forEachIndexed { i, d -> if (i == 0) path.moveTo(px(d), py(values[i])) else path.lineTo(px(d), py(values[i])) }
            val median = pct == 50
            drawPath(
                path,
                color = if (median) medianColor else curveColor,
                style = Stroke(
                    width = if (median) 2.dp.toPx() else 1.dp.toPx(),
                    pathEffect = if (index == 0 || index == curves.lastIndex) PathEffect.dashPathEffect(floatArrayOf(8f, 6f)) else null
                )
            )
            label("$pct", left + w + 4.dp.toPx(), py(values.last()))
        }

        // The baby's measurements
        if (dataPts.size > 1) {
            val path = Path()
            dataPts.forEachIndexed { i, (d, value) -> if (i == 0) path.moveTo(px(d), py(value)) else path.lineTo(px(d), py(value)) }
            drawPath(path, babyColor, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        dataPts.forEach { (d, value) ->
            drawCircle(ringColor, radius = 5.5.dp.toPx(), center = Offset(px(d), py(value)))
            drawCircle(babyColor, radius = 4.dp.toPx(), center = Offset(px(d), py(value)))
        }
    }
}
