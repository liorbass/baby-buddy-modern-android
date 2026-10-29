package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.ml.AutoMl
import com.example.data.ml.RhythmModel
import com.example.data.ml.RhythmTarget
import com.example.data.ml.SavedRhythmModel
import com.example.data.ml.Score
import com.example.data.model.TimeFormat
import com.example.data.model.TrackedItem
import com.example.data.model.formatMinutes
import com.example.data.model.localDatePattern
import com.example.ui.MainViewModel.ModelsState
import com.example.ui.theme.ItemColors
import com.example.ui.theme.OnPrimary
import com.example.ui.theme.OnSurface
import com.example.ui.theme.OnSurfaceVariant
import com.example.ui.theme.OutlineVariant
import com.example.ui.theme.PrimaryColor
import com.example.ui.theme.SecondaryColor
import com.example.ui.theme.SurfaceContainerLow
import com.example.ui.theme.SurfaceContainerLowest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

private val CardShape = RoundedCornerShape(20.dp)

/** The tracked item whose colour a rhythm uses, and whether it's tracked at all on this phone. */
private fun RhythmTarget.item(): TrackedItem = when (this) {
    RhythmTarget.FEED_GAP -> TrackedItem.BREASTFEEDING
    RhythmTarget.DIAPER_GAP -> TrackedItem.DIAPERS
    RhythmTarget.SLEEP_LENGTH, RhythmTarget.AWAKE_WINDOW -> TrackedItem.SLEEP
}

private fun RhythmTarget.hidden(hidden: Set<TrackedItem>) = when (this) {
    RhythmTarget.FEED_GAP -> TrackedItem.BREASTFEEDING in hidden && TrackedItem.BOTTLE in hidden
    else -> item() in hidden
}

/** Midnight (local) of the calendar day a date picker's UTC [utcMillis] stands for. */
private fun localDayStart(utcMillis: Long): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
    return Calendar.getInstance().apply {
        clear()
        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

/** "17 Oct", or "17 Oct 2025" outside this year. */
private fun dayLabel(millis: Long): String {
    val sameYear = Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR)
    return SimpleDateFormat(localDatePattern(if (sameYear) "dMMM" else "dMMMyyyy"), Locale.getDefault()).format(Date(millis))
}

/**
 * Trends → Models: for each rhythm (feed gaps, diaper gaps, sleep length, awake windows) AutoML fits linear
 * regression, boosted trees, a small neural net and nearest neighbours on the phone and scores them, and the
 * app's default estimate, on how well they predicted the latest entries. A model can be saved per rhythm (any of
 * the leaderboard, the best by default) to estimate reminders, Today and the summary widget; Reset goes back
 * to the default estimate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RhythmModelsTab(
    state: ModelsState,
    used: Map<RhythmTarget, SavedRhythmModel>,
    onTrain: (fromMillis: Long, toMillis: Long) -> Unit,
    onSave: (RhythmModel, Score) -> Unit,
    onReset: (RhythmTarget?) -> Unit,
    hiddenItems: Set<TrackedItem>
) {
    val day = 24 * 60 * 60_000L
    // Days back from now, or a custom from–to range (null days)
    var days by rememberSaveable { mutableStateOf<Int?>(60) }
    var customFrom by rememberSaveable { mutableStateOf<Long?>(null) }
    var customTo by rememberSaveable { mutableStateOf<Long?>(null) }
    var picking by remember { mutableStateOf(false) }
    fun range(): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        val d = days
        return if (d != null || customFrom == null || customTo == null) {
            now - (d ?: 60) * day to now
        } else {
            customFrom!! to minOf(customTo!!, now)
        }
    }
    if (picking) {
        val pickerState = rememberDateRangePickerState(
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= System.currentTimeMillis()
            }
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    enabled = pickerState.selectedStartDateMillis != null && pickerState.selectedEndDateMillis != null,
                    onClick = {
                        customFrom = localDayStart(pickerState.selectedStartDateMillis!!)
                        customTo = localDayStart(pickerState.selectedEndDateMillis!!) + day // to the end of that day
                        days = null
                        picking = false
                    }
                ) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) } }
        ) {
            DateRangePicker(state = pickerState, modifier = Modifier.weight(1f))
        }
    }
    val shown = RhythmTarget.entries.filterNot { it.hidden(hiddenItems) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            stringResource(R.string.models_intro),
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceVariant
        )
        EstimatesInUse(shown, used, onReset)
        Text(stringResource(R.string.models_train_on), style = MaterialTheme.typography.labelLarge, color = OnSurface)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(30, 60, 90, 180).forEach { option ->
                Chip(pluralStringResource(R.plurals.days_count, option, option), selected = days == option) { days = option }
            }
            Chip(
                if (days == null && customFrom != null) "${dayLabel(customFrom!!)} – ${dayLabel(customTo!! - day)}" else stringResource(R.string.models_custom_range),
                selected = days == null
            ) { picking = true }
        }
        Button(
            onClick = { range().let { (from, to) -> onTrain(from, to) } },
            enabled = state !is ModelsState.Training,
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor, contentColor = OnPrimary),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(if (state is ModelsState.Ready) R.string.models_fit_again else R.string.models_fit))
        }
        when (state) {
            ModelsState.Idle -> Text(
                stringResource(R.string.models_pick_history),
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceVariant
            )

            is ModelsState.Training -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.step, style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth(), color = PrimaryColor)
            }

            is ModelsState.Failed -> Text(state.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)

            is ModelsState.Ready -> {
                Text(
                    stringResource(R.string.models_fitted_on, dayLabel(state.fromMillis), dayLabel(state.toMillis - 1), TimeFormat.clock(state.trainedAt)),
                    style = MaterialTheme.typography.labelMedium,
                    color = OnSurfaceVariant
                )
                state.models.filter { it.target in shown }.forEach { model ->
                    ModelCard(model, used[model.target], state.trainedAt, onSave = { onSave(model, it) }, onReset = { onReset(model.target) })
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) PrimaryColor else SurfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) OnPrimary else OnSurfaceVariant)
    }
}

/** What estimates each rhythm now: the default, or a saved model (with Reset); Reset all when any is saved. */
@Composable
private fun EstimatesInUse(targets: List<RhythmTarget>, used: Map<RhythmTarget, SavedRhythmModel>, onReset: (RhythmTarget?) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(SurfaceContainerLow)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.models_in_use_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = OnSurface, modifier = Modifier.weight(1f))
            if (targets.any { it in used }) {
                TextButton(onClick = { onReset(null) }) { Text(stringResource(R.string.reset_all), color = PrimaryColor) }
            }
        }
        targets.forEach { target ->
            val saved = used[target]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(ItemColors.tint(target.item())))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(target.title, style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                    Text(
                        if (saved == null) {
                            stringResource(R.string.models_default_estimate)
                        } else {
                            stringResource(R.string.models_saved_summary, saved.family, saved.settings, formatMinutes(saved.mae.roundToInt()), dayLabel(saved.trainedAt))
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = OnSurfaceVariant
                    )
                }
                if (saved != null) {
                    TextButton(onClick = { onReset(target) }) { Text(stringResource(R.string.reset), color = PrimaryColor) }
                }
            }
        }
    }
}

@Composable
private fun ModelCard(model: RhythmModel, saved: SavedRhythmModel?, trainedAt: Long, onSave: (Score) -> Unit, onReset: () -> Unit) {
    val tint = ItemColors.tint(model.target.item())
    val soft = ItemColors.soft(model.target.item())
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(SurfaceContainerLowest)
            .border(1.dp, OutlineVariant.copy(alpha = 0.5f), CardShape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(tint))
            Spacer(Modifier.width(8.dp))
            Text(model.target.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = OnSurface)
            Spacer(Modifier.weight(1f))
            Text(pluralStringResource(R.plurals.models_examples, model.samples, model.samples), style = MaterialTheme.typography.labelMedium, color = OnSurfaceVariant)
        }
        val result = model.result
        if (result == null) {
            Text(
                stringResource(R.string.models_not_enough, model.samples, AutoMl.MIN_ROWS),
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceVariant
            )
            return@Column
        }
        var selected by remember(result) { mutableStateOf(result.best) }
        Prediction(model)
        val reference = result.default ?: result.baseline
        Text(
            stringResource(R.string.models_best, result.best.family, result.best.settings, formatMinutes(result.best.mae.roundToInt())) + " " +
                if (result.best.mae < reference.mae) {
                    stringResource(R.string.models_default_off_better, reference.settings, formatMinutes(reference.mae.roundToInt()), ((1 - result.best.mae / reference.mae) * 100).roundToInt())
                } else {
                    stringResource(R.string.models_default_off_same, reference.settings, formatMinutes(reference.mae.roundToInt()))
                },
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceVariant
        )
        // Leaderboard: each family's best settings; tap one to save it instead of the best
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.models_tried), style = MaterialTheme.typography.labelLarge, color = OnSurface)
            val worst = result.leaderboard.maxOf { it.mae }.coerceAtLeast(1.0)
            result.leaderboard.forEach { score ->
                val isSelected = score == selected
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) soft.copy(alpha = 0.5f) else Color.Transparent)
                        .clickable(enabled = !score.reference) { selected = score }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = { selected = score },
                        enabled = !score.reference,
                        modifier = Modifier.size(28.dp)
                    )
                    Column(Modifier.weight(1f).padding(start = 6.dp)) {
                        Text(
                            if (score.reference) stringResource(R.string.models_default_estimate) else score.family,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (score == result.best) FontWeight.Bold else FontWeight.Normal,
                            color = OnSurface
                        )
                        Text(score.settings, style = MaterialTheme.typography.labelSmall, color = OnSurfaceVariant)
                    }
                    Bar(
                        fraction = (score.mae / worst).toFloat(),
                        color = if (score.reference) {
                            OutlineVariant
                        } else if (isSelected) {
                            tint
                        } else {
                            soft
                        },
                        modifier = Modifier.width(72.dp)
                    )
                    Text(
                        "±${formatMinutes(score.mae.roundToInt())}",
                        style = MaterialTheme.typography.labelMedium,
                        color = OnSurfaceVariant,
                        modifier = Modifier.width(60.dp).padding(start = 8.dp)
                    )
                }
            }
        }
        // Saving: this fit's selected model becomes the rhythm's estimate (replacing a saved one)
        val isSaved = saved != null && saved.trainedAt == trainedAt && saved.family == selected.family && saved.settings == selected.settings
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (saved != null && !isSaved) {
                Text(
                    stringResource(R.string.models_in_use_now, saved.family, formatMinutes(saved.mae.roundToInt()), dayLabel(saved.trainedAt), selected.family.lowercase(), formatMinutes(selected.mae.roundToInt())),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { onSave(selected) },
                    enabled = !isSaved,
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor, contentColor = OnPrimary)
                ) {
                    Text(
                        when {
                            isSaved -> stringResource(R.string.models_in_use)
                            saved != null -> stringResource(R.string.models_save_version)
                            else -> stringResource(R.string.models_use, selected.family.lowercase())
                        }
                    )
                }
                if (saved != null) {
                    TextButton(onClick = onReset) { Text(stringResource(R.string.models_reset_default), color = PrimaryColor) }
                }
            }
        }
        // What the winner relies on
        val important = result.importance.filter { it.second > 0.05 }.take(5)
        if (important.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.models_what_matters, result.best.family.lowercase()), style = MaterialTheme.typography.labelLarge, color = OnSurface)
                val top = important.first().second
                important.forEach { (name, gain) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, style = MaterialTheme.typography.bodySmall, color = OnSurface, modifier = Modifier.weight(1f))
                        Bar(fraction = (gain / top).toFloat(), color = tint, modifier = Modifier.width(96.dp))
                        Text(
                            "+${formatMinutes(gain.roundToInt().coerceAtLeast(1))}",
                            style = MaterialTheme.typography.labelSmall,
                            color = OnSurfaceVariant,
                            modifier = Modifier.width(64.dp).padding(start = 8.dp)
                        )
                    }
                }
                Text(
                    stringResource(R.string.models_importance_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceVariant
                )
            }
        }
        if (result.lastFold.size >= 3) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.models_actual_vs_predicted), style = MaterialTheme.typography.labelLarge, color = OnSurface)
                ActualVsPredicted(result.lastFold, actualColor = soft, predictedColor = tint)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LegendSwatch(soft, stringResource(R.string.models_actual))
                    LegendSwatch(tint, stringResource(R.string.models_predicted))
                }
            }
        }
    }
}

@Composable
private fun Prediction(model: RhythmModel) {
    val minutes = model.predictedMinutes ?: return
    val anchor = model.anchorMillis ?: return
    val at = anchor + (minutes * 60_000).toLong()
    val untilMinutes = ((at - System.currentTimeMillis()) / 60_000L).toInt()
    val headline = when (model.target) {
        RhythmTarget.FEED_GAP -> stringResource(R.string.models_next_feed_around, TimeFormat.clock(at))
        RhythmTarget.DIAPER_GAP -> stringResource(R.string.models_next_change_around, TimeFormat.clock(at))
        RhythmTarget.SLEEP_LENGTH -> stringResource(R.string.models_wake_up_around, TimeFormat.clock(at))
        RhythmTarget.AWAKE_WINDOW -> stringResource(R.string.models_ready_to_sleep_around, TimeFormat.clock(at))
    }
    Column {
        Text(headline, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = OnSurface)
        Text(
            if (untilMinutes >= 0) stringResource(R.string.in_duration, formatMinutes(untilMinutes)) else stringResource(R.string.duration_ago, formatMinutes(-untilMinutes)),
            style = MaterialTheme.typography.bodyMedium,
            color = if (untilMinutes >= 0) SecondaryColor else OnSurfaceVariant
        )
    }
}

@Composable
private fun Bar(fraction: Float, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Box(modifier.height(8.dp).clip(RoundedCornerShape(50)).background(SurfaceContainerLow)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(8.dp).clip(RoundedCornerShape(50)).background(color))
    }
}

@Composable
private fun LegendSwatch(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = OnSurfaceVariant)
    }
}

/** Actual (dots) and predicted (line) intervals for the held-out entries, oldest left. */
@Composable
private fun ActualVsPredicted(points: List<Pair<Double, Double>>, actualColor: androidx.compose.ui.graphics.Color, predictedColor: androidx.compose.ui.graphics.Color) {
    val max = points.maxOf { maxOf(it.first, it.second) }.coerceAtLeast(1.0)
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val step = if (points.size > 1) size.width / (points.size - 1) else 0f
        fun y(v: Double) = size.height - (v / max).toFloat() * size.height
        points.forEachIndexed { i, (actual, _) -> drawCircle(actualColor, radius = 3.dp.toPx(), center = Offset(i * step, y(actual))) }
        val path = Path()
        points.forEachIndexed { i, (_, predicted) ->
            if (i == 0) path.moveTo(0f, y(predicted)) else path.lineTo(i * step, y(predicted))
        }
        drawPath(path, predictedColor, style = Stroke(width = 2.dp.toPx()))
    }
}
