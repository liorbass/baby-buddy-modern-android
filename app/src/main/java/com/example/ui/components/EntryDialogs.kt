package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.data.model.BATH_NOTE_TEXT
import com.example.data.model.BreastSide
import com.example.data.model.DIAPER_AMOUNTS
import com.example.data.model.DiaperEvent
import com.example.data.model.DiaperType
import com.example.data.model.FeedType
import com.example.data.model.FeedingEvent
import com.example.data.model.GrowthEvent
import com.example.data.model.GrowthKind
import com.example.data.model.NoteEvent
import com.example.data.model.PumpingEvent
import com.example.data.model.QuickAction
import com.example.data.model.SleepEvent
import com.example.data.model.TimeFormat
import com.example.data.model.TummyTimeEvent
import com.example.data.model.dayAndTime
import com.example.data.model.diaperValueLabel
import com.example.data.model.formatGrowth
import com.example.data.model.formatMinutes
import com.example.data.model.localDatePattern
import com.example.data.model.parseGrowth
import com.example.ui.theme.OnPrimary
import com.example.ui.theme.OnSurface
import com.example.ui.theme.OnSurfaceVariant
import com.example.ui.theme.PrimaryColor
import com.example.ui.theme.PrimaryFixed
import com.example.ui.theme.SurfaceColor
import com.example.ui.theme.SurfaceContainer
import com.example.ui.theme.SurfaceContainerHigh
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val ML_STEP = 10
private const val ML_MIN = 10
private const val ML_MAX = 400
private val ML_PRESETS = listOf(60, 90, 120, 150, 180)

private const val TUMMY_MIN = 1
private const val TUMMY_MAX = 120

private const val PUMP_ML_MAX = 500
private const val PUMP_MIN_MINUTES = 1
private const val PUMP_MAX_MINUTES = 90

/** Display label to stored value. */
private val BARRIER_OPTIONS = listOf(
    "Barrier" to "Aquaphor / Barrier",
    "Zinc" to "Zinc Oxide Cream",
    "Powder" to "Baby Powder"
)
private val CONSISTENCY_OPTIONS = listOf(
    "Loose" to "Loose / Liquid",
    "Seedy" to "Seedy / Mustard",
    "Paste" to "Paste / Normal",
    "Hard" to "Hard / Dry"
)
private val COLOR_OPTIONS = listOf("Mustard", "Tan/Brown", "Greenish", "Dark")

// ---------------------------------------------------------------------------
// Public dialogs
// ---------------------------------------------------------------------------

/** "Feeding" quick action: choose breastfeeding (opens timer) or bottle. */
@Composable
fun FeedTypeChooserDialog(onDismiss: () -> Unit, onBreastfeed: () -> Unit, onBottle: () -> Unit) {
    DialogFrame(onDismiss = onDismiss) {
        DialogTitle(stringResource(R.string.action_feeding))
        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ChoiceTile(emoji = "🤱", label = stringResource(R.string.tile_feed_name), onClick = onBreastfeed, modifier = Modifier.weight(1f))
            ChoiceTile(emoji = "🍼", label = stringResource(R.string.log_bottle), onClick = onBottle, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.cancel), color = OnSurfaceVariant)
        }
    }
}

/** Log a bottle: amount in ml + formula/breast milk. */
@Composable
fun BottleDialog(initialMl: Int, initialFormula: Boolean, onDismiss: () -> Unit, onSave: (ml: Int, formula: Boolean, notes: String) -> Unit) {
    DialogFrame(onDismiss = onDismiss) {
        DialogTitle(stringResource(R.string.log_bottle))
        Spacer(Modifier.height(20.dp))
        BottleForm(initialMl = initialMl, initialFormula = initialFormula, onCancel = onDismiss, onSave = { ml, formula, notes, _ -> onSave(ml, formula, notes) }, showTime = false)
    }
}

/** Bottle fields (milk type, amount, optional time, note) with Cancel / Save; used in a dialog and inline on the Log screen. */
@Composable
fun BottleForm(
    initialMl: Int,
    initialFormula: Boolean,
    onCancel: () -> Unit,
    onSave: (ml: Int, formula: Boolean, notes: String, timeMillis: Long?) -> Unit,
    showTime: Boolean = true
) {
    var ml by remember { mutableIntStateOf(initialMl.coerceIn(ML_MIN, ML_MAX)) }
    var formula by remember { mutableStateOf(initialFormula) }
    var notes by remember { mutableStateOf("") }
    var pickedTime by remember { mutableStateOf<Long?>(null) } // null = now
    Column {
        if (showTime) {
            NowTimeField(label = stringResource(R.string.time), picked = pickedTime, onChange = { pickedTime = it })
            Spacer(Modifier.height(16.dp))
        }
        MilkToggle(formula = formula, onChange = { formula = it })
        Spacer(Modifier.height(20.dp))
        AmountStepper(ml = ml, onChange = { ml = it })
        Spacer(Modifier.height(20.dp))
        NotesField(value = notes, onChange = { notes = it }, placeholderText = stringResource(R.string.note_optional))
        Spacer(Modifier.height(24.dp))
        CancelSaveRow(onCancel = onCancel, onSave = { onSave(ml, formula, notes.trim(), pickedTime) })
    }
}

/** Log a pumping session: start time, amount in ml, duration, optional note. Start defaults to "now minus duration". */
@Composable
fun PumpingDialog(initialMl: Int, initialMinutes: Int, onDismiss: () -> Unit, onSave: (ml: Int, minutes: Int, notes: String, startMillis: Long) -> Unit) {
    DialogFrame(onDismiss = onDismiss) {
        DialogTitle(stringResource(R.string.log_pumping))
        Spacer(Modifier.height(16.dp))
        PumpingForm(initialMl = initialMl, initialMinutes = initialMinutes, onCancel = onDismiss, onSave = onSave)
    }
}

/** Pumping fields with Cancel / Save; used in a dialog and inline on the Log screen. */
@Composable
fun PumpingForm(initialMl: Int, initialMinutes: Int, onCancel: () -> Unit, onSave: (ml: Int, minutes: Int, notes: String, startMillis: Long) -> Unit) {
    var ml by remember { mutableIntStateOf(initialMl.coerceIn(ML_MIN, PUMP_ML_MAX)) }
    var minutes by remember { mutableIntStateOf(initialMinutes.coerceIn(PUMP_MIN_MINUTES, PUMP_MAX_MINUTES)) }
    var notes by remember { mutableStateOf("") }
    val openedAt = remember { System.currentTimeMillis() }
    var pickedStart by remember { mutableStateOf<Long?>(null) } // null = follows the duration
    val start = pickedStart ?: (openedAt - minutes * 60_000L)

    Column {
        TimeField(label = stringResource(R.string.started), millis = start, onChange = { pickedStart = it })
        Spacer(Modifier.height(16.dp))
        AmountStepper(ml = ml, onChange = { ml = it }, max = PUMP_ML_MAX)
        Spacer(Modifier.height(20.dp))
        MinuteStepper(
            label = stringResource(R.string.duration),
            minutes = minutes,
            onChange = { minutes = it },
            min = PUMP_MIN_MINUTES,
            max = PUMP_MAX_MINUTES
        )
        Spacer(Modifier.height(20.dp))
        NotesField(value = notes, onChange = { notes = it }, placeholderText = stringResource(R.string.note_optional))
        Spacer(Modifier.height(24.dp))
        CancelSaveRow(onCancel = onCancel, onSave = { onSave(ml, minutes, notes.trim(), pickedStart ?: (System.currentTimeMillis() - minutes * 60_000L)) })
    }
}

/** Bath entry: time (default now) and an optional note. */
@Composable
fun BathForm(onCancel: () -> Unit, onSave: (notes: String, timeMillis: Long?) -> Unit) {
    var notes by remember { mutableStateOf("") }
    var pickedTime by remember { mutableStateOf<Long?>(null) } // null = now
    Column {
        NowTimeField(label = stringResource(R.string.time), picked = pickedTime, onChange = { pickedTime = it })
        Spacer(Modifier.height(16.dp))
        NotesField(value = notes, onChange = { notes = it }, placeholderText = stringResource(R.string.note_optional))
        Spacer(Modifier.height(24.dp))
        CancelSaveRow(onCancel = onCancel, onSave = { onSave(notes.trim(), pickedTime) })
    }
}

@Composable
fun BathDialog(onDismiss: () -> Unit, onSave: (notes: String, timeMillis: Long?) -> Unit) {
    DialogFrame(onDismiss = onDismiss) {
        DialogTitle(stringResource(R.string.log_bath))
        Spacer(Modifier.height(16.dp))
        BathForm(onCancel = onDismiss, onSave = onSave)
    }
}

/**
 * Growth measurements taken together: weight, length and head circumference (fill in any of them),
 * which day (the server keeps only the date) and an optional note. [latest] = the newest entry of each kind.
 */
@Composable
fun GrowthForm(
    latest: Map<GrowthKind, GrowthEvent>,
    onCancel: () -> Unit,
    onSave: (values: Map<GrowthKind, Double>, dateMillis: Long, notes: String) -> Unit
) {
    val texts = remember { mutableStateMapOf<GrowthKind, String>() }
    var daysAgo by remember { mutableIntStateOf(0) }
    var notes by remember { mutableStateOf("") }
    val parsed = GrowthKind.entries.associateWith { parseGrowth(it, texts[it].orEmpty()) }
    val invalid = GrowthKind.entries.any { texts[it].orEmpty().isNotBlank() && parsed[it] == null }
    Column {
        GrowthKind.entries.forEach { kind ->
            val text = texts[kind].orEmpty()
            val last = latest[kind]
            OutlinedTextField(
                value = text,
                onValueChange = { v -> texts[kind] = v.filter { it.isDigit() || it == '.' || it == ',' }.take(6) },
                label = { Text(kind.label) },
                suffix = { Text(kind.unit) },
                placeholder = { Text(last?.let { formatGrowth(kind, it.value).removeSuffix(" ${kind.unit}") } ?: kind.example) },
                isError = text.isNotBlank() && parsed[kind] == null,
                supportingText = if (last != null) {
                    { Text(stringResource(R.string.dialog_growth_last, formatGrowth(kind, last.value), shortDate(last.dateMillis))) }
                } else {
                    null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(8.dp))
        SegmentedToggle(options = listOf(stringResource(R.string.today), stringResource(R.string.yesterday)), selectedIndex = daysAgo, onSelect = { daysAgo = it })
        Spacer(Modifier.height(20.dp))
        NotesField(value = notes, onChange = { notes = it }, placeholderText = stringResource(R.string.note_optional))
        Spacer(Modifier.height(24.dp))
        CancelSaveRow(
            onCancel = onCancel,
            onSave = {
                val values = parsed.filterValues { it != null }.mapValues { it.value!! }
                if (values.isNotEmpty() && !invalid) onSave(values, System.currentTimeMillis() - daysAgo * 86_400_000L, notes.trim())
            }
        )
    }
}

@Composable
fun GrowthDialog(
    latest: Map<GrowthKind, GrowthEvent>,
    onDismiss: () -> Unit,
    onSave: (values: Map<GrowthKind, Double>, dateMillis: Long, notes: String) -> Unit
) {
    DialogFrame(onDismiss = onDismiss) {
        DialogTitle(stringResource(R.string.log_growth))
        Spacer(Modifier.height(16.dp))
        GrowthForm(latest = latest, onCancel = onDismiss, onSave = onSave)
    }
}

/** Edit or delete one measurement: its value, day (the server keeps only the date) and note. */
@Composable
fun EditGrowthDialog(event: GrowthEvent, onDismiss: () -> Unit, onSave: (GrowthEvent) -> Unit, onDelete: () -> Unit) {
    val kind = event.kind
    var text by remember(event.id) { mutableStateOf(formatGrowth(kind, event.value).removeSuffix(" ${kind.unit}")) }
    var date by remember(event.id) { mutableLongStateOf(event.dateMillis) }
    var notes by remember(event.id) { mutableStateOf(event.notes) }
    val parsed = parseGrowth(kind, text)
    DialogFrame(onDismiss = onDismiss) {
        DialogTitle(stringResource(R.string.dialog_edit_named, kind.label.lowercase()))
        event.caregiver?.let {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.dialog_logged_by, it), style = MaterialTheme.typography.bodyMedium, color = OnSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { v -> text = v.filter { it.isDigit() || it == '.' || it == ',' }.take(6) },
            label = { Text(kind.label) },
            suffix = { Text(kind.unit) },
            isError = parsed == null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        DateField(label = stringResource(R.string.date), millis = date, onChange = { date = it })
        Spacer(Modifier.height(16.dp))
        NotesField(value = notes, onChange = { notes = it })
        Spacer(Modifier.height(20.dp))
        DeleteSaveRow(
            onDelete = onDelete,
            onSave = { if (parsed != null) onSave(event.copy(value = parsed, dateMillis = date, notes = notes.trim())) }
        )
    }
}

/** "Date    30 Sep ✎": tap to pick a day (not in the future). [millis] and the result are local noon of that day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, millis: Long, onChange: (Long) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = localToUtcDate(millis),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= localToUtcDate(System.currentTimeMillis())
            }
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedDateMillis != null,
                    onClick = {
                        picking = false
                        onChange(utcDateToLocalNoon(state.selectedDateMillis!!))
                    }
                ) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) } }
        ) {
            DatePicker(state = state)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceContainer)
            .clickable { picking = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = OnSurface, modifier = Modifier.weight(1f))
        Text(
            SimpleDateFormat(localDatePattern("dMMMyyyy"), Locale.getDefault()).format(Date(millis)),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = PrimaryColor
        )
        Icon(
            Icons.Default.Edit,
            contentDescription = stringResource(R.string.dialog_change_label, label),
            tint = OnSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp).size(18.dp)
        )
    }
}

/** The local calendar day of [millis] as UTC midnight (what the Material date picker uses). */
private fun localToUtcDate(millis: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = millis }
    return Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

/** A date picker's UTC midnight as local noon of the same calendar day. */
private fun utcDateToLocalNoon(utcMillis: Long): Long {
    val utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
    return Calendar.getInstance().apply {
        clear()
        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 12, 0)
    }.timeInMillis
}

/** The newest measurement of each kind. */
fun latestGrowth(growth: List<GrowthEvent>): Map<GrowthKind, GrowthEvent> = growth.groupBy { it.kind }.mapValues { (_, list) -> list.maxBy { it.dateMillis } }

/** "30 Sep" */
internal fun shortDate(millis: Long): String = java.text.SimpleDateFormat(localDatePattern("dMMM"), java.util.Locale.getDefault()).format(java.util.Date(millis))

/** Edit or delete a bath (a note tagged "bath"). */
@Composable
fun EditBathDialog(bath: NoteEvent, onDismiss: () -> Unit, onSave: (NoteEvent) -> Unit, onDelete: () -> Unit) {
    var time by remember(bath.id) { mutableStateOf(bath.timeMillis) }
    var notes by remember(bath.id) { mutableStateOf(if (bath.text == BATH_NOTE_TEXT) "" else bath.text) }
    DialogFrame(onDismiss = onDismiss) {
        DialogTitle(stringResource(R.string.dialog_edit_bath))
        Spacer(Modifier.height(16.dp))
        TimeField(label = stringResource(R.string.time), millis = time, onChange = { time = it }, keepDay = true)
        Spacer(Modifier.height(16.dp))
        NotesField(value = notes, onChange = { notes = it })
        Spacer(Modifier.height(20.dp))
        DeleteSaveRow(
            onDelete = onDelete,
            onSave = { onSave(bath.copy(timeMillis = time, timeFormatted = formatTime(time), text = notes.trim().ifBlank { BATH_NOTE_TEXT })) }
        )
    }
}

/** Edit or delete a note: time and text; its tags stay as they are. */
@Composable
fun EditNoteDialog(note: NoteEvent, onDismiss: () -> Unit, onSave: (NoteEvent) -> Unit, onDelete: () -> Unit) {
    var time by remember(note.id) { mutableStateOf(note.timeMillis) }
    var text by remember(note.id) { mutableStateOf(note.text) }
    DialogFrame(onDismiss = onDismiss) {
        DialogTitle(stringResource(R.string.dialog_edit_note))
        if (note.tags.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(note.tags.joinToString("  ") { "#$it" }, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
        TimeField(label = stringResource(R.string.time), millis = time, onChange = { time = it }, keepDay = true)
        Spacer(Modifier.height(16.dp))
        NotesField(value = text, onChange = { text = it })
        Spacer(Modifier.height(20.dp))
        DeleteSaveRow(
            onDelete = onDelete,
            onSave = { if (text.isNotBlank()) onSave(note.copy(timeMillis = time, timeFormatted = formatTime(time), text = text.trim())) }
        )
    }
}

/** A [TimeField] that shows "Now · 19:06" until a time is picked ([picked] null = now). */
@Composable
private fun NowTimeField(label: String, picked: Long?, onChange: (Long) -> Unit) {
    val openedAt = remember { System.currentTimeMillis() }
    TimeField(
        label = label,
        millis = picked ?: openedAt,
        onChange = onChange,
        valueText = if (picked == null) stringResource(R.string.now_at, TimeFormat.clock(openedAt)) else null
    )
}

/** Correct one breast's time on the running feed, as mm:ss. */
@Composable
fun SideDurationDialog(sideLabel: String, initialSeconds: Int, onDismiss: () -> Unit, onSave: (seconds: Int) -> Unit) {
    var seconds by remember { mutableIntStateOf(initialSeconds) }
    DialogFrame(onDismiss = onDismiss) {
        DialogTitle(stringResource(R.string.dialog_side_title, sideLabel))
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.dialog_side_note),
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        DurationInput(label = stringResource(R.string.time), seconds = seconds, onChange = { seconds = it })
        Spacer(Modifier.height(24.dp))
        CancelSaveRow(onCancel = onDismiss, onSave = { onSave(seconds) })
    }
}

/**
 * Pick a moment. The wheels set date and time together (minutes going round move the hour, and so on into the
 * date). A typed time is the latest such moment that isn't in the future, or with [keepDay] stays on the day
 * already picked (editing an entry).
 */
@Composable
fun TimePickDialog(
    title: String,
    initialMillis: Long,
    onDismiss: () -> Unit,
    onSave: (Long) -> Unit,
    note: String? = null,
    keepDay: Boolean = false
) {
    var picked by remember { mutableLongStateOf(initialMillis) }
    DialogFrame(onDismiss = onDismiss) {
        DialogTitle(title)
        if (note != null) {
            Spacer(Modifier.height(4.dp))
            Text(note, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
        ClockTimeEntry(
            millis = picked,
            onChange = { picked = it },
            fromTyped = { h, m -> if (keepDay) withTime(picked, h, m) else TimeFormat.latestAt(h, m) }
        )
        // Typing has no date wheel: say which day it lands on, once that isn't today
        val day = dayAndTime(picked, TimeFormat.clock(picked))
        if (!TimeFormat.useWheel && day != TimeFormat.clock(picked)) {
            Text(
                day,
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(8.dp))
        CancelSaveRow(onCancel = onDismiss, onSave = { onSave(picked) })
    }
}

/** "Label    19:06 ✎": tap to change the time. [valueText] replaces the time shown (e.g. "Now · 19:06"). */
@Composable
fun TimeField(
    label: String,
    millis: Long,
    onChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
    valueText: String? = null,
    dialogNote: String? = null,
    keepDay: Boolean = false
) {
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        TimePickDialog(
            title = label,
            initialMillis = millis,
            note = dialogNote,
            keepDay = keepDay,
            onDismiss = { picking = false },
            onSave = {
                picking = false
                onChange(it)
            }
        )
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceContainer)
            .clickable { picking = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = OnSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            valueText ?: TimeFormat.clock(millis),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = PrimaryColor
        )
        Icon(
            Icons.Default.Edit,
            contentDescription = stringResource(R.string.dialog_change_label, label),
            tint = OnSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp).size(18.dp)
        )
    }
}

/** Pick a length of time as hours : minutes (wheels or typed). */
@Composable
fun DurationPickDialog(title: String, minutes: Int, onDismiss: () -> Unit, onSave: (minutes: Int) -> Unit, note: String? = null) {
    var h by remember { mutableIntStateOf(minutes / 60) }
    var m by remember { mutableIntStateOf(minutes % 60) }
    DialogFrame(onDismiss = onDismiss) {
        DialogTitle(title)
        if (note != null) {
            Spacer(Modifier.height(4.dp))
            Text(note, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (TimeFormat.useWheel) {
                DurationWheels(first = h, second = m, firstMax = 23, firstUnit = "h", secondUnit = "m", onChange = { a, b ->
                    h = a
                    m = b
                })
            } else {
                var hText by remember { mutableStateOf(h.toString()) }
                var mText by remember { mutableStateOf("%02d".format(m)) }
                val numberStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = hText,
                        onValueChange = { v ->
                            hText = v.filter(Char::isDigit).take(2)
                            h = (hText.toIntOrNull() ?: 0).coerceAtMost(23)
                        },
                        label = { Text("hh") },
                        singleLine = true,
                        textStyle = numberStyle,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.width(84.dp)
                    )
                    Text(":", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp))
                    OutlinedTextField(
                        value = mText,
                        onValueChange = { v ->
                            mText = v.filter(Char::isDigit).take(2)
                            m = (mText.toIntOrNull() ?: 0).coerceAtMost(59)
                        },
                        label = { Text("mm") },
                        singleLine = true,
                        textStyle = numberStyle,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.width(84.dp)
                    )
                }
            }
            InputModeToggle()
        }
        Spacer(Modifier.height(8.dp))
        CancelSaveRow(onCancel = onDismiss, onSave = { if (h * 60 + m > 0) onSave(h * 60 + m) })
    }
}

/** "Label    2h 30m ✎": tap to set a length of time as hh:mm. */
@Composable
fun DurationField(label: String, minutes: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier, dialogNote: String? = null) {
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        DurationPickDialog(
            title = label,
            minutes = minutes,
            note = dialogNote,
            onDismiss = { picking = false },
            onSave = {
                picking = false
                onChange(it)
            }
        )
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceContainer)
            .clickable { picking = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = OnSurface, modifier = Modifier.weight(1f))
        Text(
            "%d:%02d".format(minutes / 60, minutes % 60),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = PrimaryColor
        )
        Icon(
            Icons.Default.Edit,
            contentDescription = stringResource(R.string.dialog_change_label, label),
            tint = OnSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp).size(18.dp)
        )
    }
}

/** "Label    12:30 ✎": tap to set a length of time as mm:ss in its own dialog. */
@Composable
fun SecondsField(label: String, seconds: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        var picked by remember { mutableIntStateOf(seconds) }
        DialogFrame(onDismiss = { picking = false }) {
            DialogTitle(label)
            Spacer(Modifier.height(16.dp))
            DurationInput(label = "mm:ss", seconds = picked, onChange = { picked = it })
            Spacer(Modifier.height(8.dp))
            CancelSaveRow(
                onCancel = { picking = false },
                onSave = {
                    picking = false
                    onChange(picked)
                }
            )
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceContainer)
            .clickable { picking = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = OnSurface, modifier = Modifier.weight(1f))
        Text(
            "%02d:%02d".format(seconds / 60, seconds % 60),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = PrimaryColor
        )
        Icon(
            Icons.Default.Edit,
            contentDescription = stringResource(R.string.dialog_change_label, label),
            tint = OnSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp).size(18.dp)
        )
    }
}

/** A duration as minutes : seconds, on wheels or typed (see [InputModeToggle]). */
@Composable
fun DurationInput(label: String, seconds: Int, onChange: (Int) -> Unit, showToggle: Boolean = true) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (TimeFormat.useWheel) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = OnSurface,
                    modifier = Modifier.weight(1f)
                )
                DurationWheels(
                    first = seconds / 60,
                    second = seconds % 60,
                    firstMax = 180,
                    firstUnit = "m",
                    secondUnit = "s",
                    onChange = { m, s -> onChange(m * 60 + s) }
                )
            }
        } else {
            DurationTyped(label, seconds, onChange)
        }
        if (showToggle) InputModeToggle()
    }
}

@Composable
private fun DurationTyped(label: String, seconds: Int, onChange: (Int) -> Unit) {
    var mm by remember { mutableStateOf((seconds / 60).toString()) }
    var ss by remember { mutableStateOf("%02d".format(seconds % 60)) }
    fun emit() = onChange((mm.toIntOrNull() ?: 0) * 60 + (ss.toIntOrNull() ?: 0).coerceAtMost(59))
    val numberStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = OnSurface,
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = mm,
            onValueChange = { v ->
                mm = v.filter(Char::isDigit).take(3)
                emit()
            },
            label = { Text("mm") },
            singleLine = true,
            textStyle = numberStyle,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.width(84.dp)
        )
        Text(
            ":",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = OnSurface,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
        OutlinedTextField(
            value = ss,
            onValueChange = { v ->
                val digits = v.filter(Char::isDigit).take(2)
                ss = if ((digits.toIntOrNull() ?: 0) > 59) "59" else digits
                emit()
            },
            label = { Text("ss") },
            singleLine = true,
            textStyle = numberStyle,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.width(84.dp)
        )
    }
}

/** Set how much breast milk is stored now; later pumping adds to it and breast-milk bottles take from it. */
@Composable
fun MilkStashDialog(initialMl: Int?, onDismiss: () -> Unit, onSave: (ml: Int) -> Unit) {
    var text by remember { mutableStateOf(initialMl?.toString().orEmpty()) }
    DialogFrame(onDismiss = onDismiss) {
        DialogTitle(stringResource(R.string.item_milk_stash))
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.dialog_milk_stash_note),
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { v -> text = v.filter(Char::isDigit).take(5) },
            label = { Text(stringResource(R.string.dialog_stored_milk)) },
            suffix = { Text("ml") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(20.dp))
        CancelSaveRow(onCancel = onDismiss, onSave = { text.toIntOrNull()?.let(onSave) })
    }
}

/** A note with optional tags (the server's tags, plus the ones picked here). */
@Composable
fun NoteDialog(tags: List<String>, onDismiss: () -> Unit, onSave: (text: String, tags: List<String>) -> Unit) {
    var text by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<String>()) }
    DialogFrame(onDismiss = onDismiss) {
        DialogTitle(stringResource(R.string.note))
        Spacer(Modifier.height(16.dp))
        NotesField(value = text, onChange = { text = it }, placeholderText = stringResource(R.string.dialog_note_placeholder))
        if (tags.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SectionLabel(stringResource(R.string.dialog_tags))
            ChipGroup(
                options = tags.map { it.replaceFirstChar(Char::uppercase) to it },
                isSelected = { it in picked },
                onToggle = { tag -> picked = if (tag in picked) picked - tag else picked + tag }
            )
        }
        Spacer(Modifier.height(20.dp))
        CancelSaveRow(
            onCancel = onDismiss,
            onSave = {
                val note = text.trim().ifBlank { picked.firstOrNull()?.replaceFirstChar(Char::uppercase).orEmpty() }
                if (note.isNotBlank()) onSave(note, picked.toList())
            }
        )
    }
}

/** Nap (daytime) or night sleep. */
@Composable
fun NapToggle(isNap: Boolean, onChange: (Boolean) -> Unit) {
    SegmentedToggle(
        options = listOf(stringResource(R.string.nap), stringResource(R.string.dialog_night_sleep)),
        selectedIndex = if (isNap) 0 else 1,
        onSelect = { onChange(it == 0) }
    )
}

/** Edit or delete a sleep. */
@Composable
fun EditSleepDialog(sleep: SleepEvent, onDismiss: () -> Unit, onSave: (SleepEvent) -> Unit, onDelete: () -> Unit) {
    var start by remember { mutableStateOf(sleep.timeMillis) }
    var end by remember { mutableStateOf(sleep.endMillis) }
    var isNap by remember { mutableStateOf(sleep.isNap) }
    var notes by remember { mutableStateOf(sleep.notes) }
    // Picked times are the latest past moment; a start "after" the end means the evening before
    val fixedStart = if (start > end) start - 24 * 60 * 60_000L else start

    DialogFrame(onDismiss = onDismiss) {
        DialogTitle(stringResource(R.string.dialog_edit_sleep))
        Spacer(Modifier.height(16.dp))
        // Keep the entry's own dates (it may be from earlier in the week)
        TimeField(label = stringResource(R.string.fell_asleep), millis = start, onChange = { start = it }, keepDay = true)
        Spacer(Modifier.height(8.dp))
        TimeField(label = stringResource(R.string.woke_up), millis = end, onChange = { end = it }, keepDay = true)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.sleep_slept, formatMinutes(((end - fixedStart) / 60_000L).toInt())),
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp)
        )
        Spacer(Modifier.height(16.dp))
        NapToggle(isNap = isNap, onChange = { isNap = it })
        Spacer(Modifier.height(16.dp))
        NotesField(value = notes, onChange = { notes = it })
        Spacer(Modifier.height(20.dp))
        DeleteSaveRow(
            onDelete = onDelete,
            onSave = {
                onSave(
                    sleep.copy(
                        timeMillis = fixedStart,
                        endMillis = end,
                        timeFormatted = formatTime(fixedStart),
                        isNap = isNap,
                        notes = notes.trim()
                    )
                )
            }
        )
    }
}

/** Log a tummy time session that just ended. */
@Composable
fun TummyTimeDialog(initialMinutes: Int, onDismiss: () -> Unit, onSave: (minutes: Int) -> Unit) {
    var minutes by remember { mutableIntStateOf(initialMinutes.coerceIn(TUMMY_MIN, TUMMY_MAX)) }
    DialogFrame(onDismiss = onDismiss) {
        DialogTitle(stringResource(R.string.item_tummy_time))
        Spacer(Modifier.height(20.dp))
        MinuteStepper(label = stringResource(R.string.dialog_minutes), minutes = minutes, onChange = { minutes = it }, min = TUMMY_MIN, max = TUMMY_MAX)
        Spacer(Modifier.height(24.dp))
        CancelSaveRow(onCancel = onDismiss, onSave = { onSave(minutes) })
    }
}

/** Choose which shortcut buttons Today shows, and their order. */
@Composable
fun QuickActionsEditorDialog(
    current: List<QuickAction>,
    onDismiss: () -> Unit,
    onSave: (List<QuickAction>) -> Unit,
    available: List<QuickAction> = QuickAction.entries // items not tracked on this phone are left out
) {
    // Shown ones first in their order, then the hidden ones
    var order by remember { mutableStateOf(current + available.filterNot { it in current }) }
    var shown by remember { mutableStateOf(current.toSet()) }

    fun move(index: Int, by: Int) {
        val target = index + by
        if (target !in order.indices) return
        order = order.toMutableList().apply { add(target, removeAt(index)) }
    }

    DialogFrame(onDismiss = onDismiss) {
        DialogTitle(stringResource(R.string.dialog_quick_actions))
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.dialog_quick_actions_note),
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        order.forEachIndexed { index, action ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = action in shown,
                    onCheckedChange = { checked -> shown = if (checked) shown + action else shown - action },
                    colors = CheckboxDefaults.colors(checkedColor = PrimaryColor)
                )
                Text(
                    action.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (action in shown) OnSurface else OnSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { move(index, -1) }, enabled = index > 0) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.dialog_move_up, action.label))
                }
                IconButton(onClick = { move(index, 1) }, enabled = index < order.lastIndex) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.dialog_move_down, action.label))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        CancelSaveRow(onCancel = onDismiss, onSave = { onSave(order.filter { it in shown }) })
    }
}

/** Edit or delete a feeding. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditFeedingDialog(feeding: FeedingEvent, onDismiss: () -> Unit, onSave: (FeedingEvent) -> Unit, onDelete: () -> Unit) {
    val isBottle = feeding.type == FeedType.BOTTLE || feeding.amountMl != null
    var time by remember(feeding.id) { mutableStateOf(feeding.timeMillis) }
    var ml by remember { mutableIntStateOf((feeding.amountMl ?: 120).coerceIn(ML_MIN, ML_MAX)) }
    var formula by remember { mutableStateOf(feeding.bottleFormula) }
    var leftSec by remember { mutableIntStateOf(feeding.leftDurationSec) }
    var rightSec by remember { mutableIntStateOf(feeding.rightDurationSec) }
    var lastSide by remember { mutableStateOf(feeding.lastSide) }
    var notes by remember { mutableStateOf(feeding.notes) }

    DialogFrame(onDismiss = onDismiss) {
        DialogTitle(stringResource(R.string.dialog_edit_feeding))
        Spacer(Modifier.height(16.dp))
        // Keeps the entry's own date (it may be from earlier in the week)
        TimeField(label = stringResource(R.string.time), millis = time, onChange = { time = it }, keepDay = true)
        Spacer(Modifier.height(16.dp))

        if (isBottle) {
            MilkToggle(formula = formula, onChange = { formula = it })
            Spacer(Modifier.height(20.dp))
            AmountStepper(ml = ml, onChange = { ml = it })
        } else {
            SecondsField(label = stringResource(R.string.side_left), seconds = leftSec, onChange = { leftSec = it })
            Spacer(Modifier.height(8.dp))
            SecondsField(label = stringResource(R.string.side_right), seconds = rightSec, onChange = { rightSec = it })
            Spacer(Modifier.height(16.dp))
            SectionLabel(stringResource(R.string.last_side))
            SegmentedToggle(
                options = listOf(stringResource(R.string.side_left), stringResource(R.string.side_right)),
                selectedIndex = when (lastSide) {
                    BreastSide.LEFT -> 0
                    BreastSide.RIGHT -> 1
                    else -> -1
                },
                onSelect = { lastSide = if (it == 0) BreastSide.LEFT else BreastSide.RIGHT }
            )
        }

        Spacer(Modifier.height(20.dp))
        NotesField(value = notes, onChange = { notes = it })
        Spacer(Modifier.height(20.dp))

        DeleteSaveRow(
            onDelete = onDelete,
            onSave = {
                val millis = time
                val updated = if (isBottle) {
                    feeding.copy(
                        timeMillis = millis,
                        timeFormatted = formatTime(millis),
                        amountMl = ml,
                        bottleFormula = formula,
                        notes = notes.trim()
                    )
                } else {
                    val side = when {
                        leftSec > 0 && rightSec > 0 -> BreastSide.BOTH
                        rightSec > 0 -> BreastSide.RIGHT
                        leftSec > 0 -> BreastSide.LEFT
                        else -> feeding.side
                    }
                    feeding.copy(
                        timeMillis = millis,
                        timeFormatted = formatTime(millis),
                        leftDurationSec = leftSec,
                        rightDurationSec = rightSec,
                        side = side,
                        sides = sidesEndingOn(lastSide, leftSec, rightSec) ?: feeding.sides,
                        notes = notes.trim()
                    )
                }
                onSave(updated)
            }
        )
    }
}

/** Side order ("LEFT -> RIGHT") for a breastfeed that ended on [last]; the other side comes first if it has time. */
private fun sidesEndingOn(last: BreastSide?, leftSec: Int, rightSec: Int): String? = when (last) {
    BreastSide.LEFT -> if (rightSec > 0) "RIGHT -> LEFT" else "LEFT"
    BreastSide.RIGHT -> if (leftSec > 0) "LEFT -> RIGHT" else "RIGHT"
    else -> null
}

/** Edit or delete a diaper change. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditDiaperDialog(diaper: DiaperEvent, onDismiss: () -> Unit, onSave: (DiaperEvent) -> Unit, onDelete: () -> Unit) {
    var time by remember(diaper.id) { mutableStateOf(diaper.timeMillis) }
    var type by remember { mutableStateOf(diaper.type) }
    var notes by remember { mutableStateOf(diaper.notes) }
    var rash by remember { mutableStateOf(diaper.rashPresent) }
    var barriers by remember { mutableStateOf(diaper.skinBarrierApplied) }
    var consistency by remember { mutableStateOf(diaper.consistency) }
    var color by remember { mutableStateOf(diaper.color) }
    var amount by remember { mutableStateOf(diaper.amount) } // optional; "" = not recorded
    val showStool = type == DiaperType.DIRTY || type == DiaperType.BOTH
    // Keep an unknown stored color visible (and selectable) so it isn't lost.
    val colorOptions = remember(diaper.id) {
        val base = COLOR_OPTIONS.map { diaperValueLabel(it) to it }
        if (diaper.color.isNotBlank() && diaper.color !in COLOR_OPTIONS) base + (diaper.color to diaper.color) else base
    }

    DialogFrame(onDismiss = onDismiss) {
        DialogTitle(stringResource(R.string.dialog_edit_diaper))
        Spacer(Modifier.height(16.dp))
        // Keeps the entry's own date (it may be from earlier in the week)
        TimeField(label = stringResource(R.string.time), millis = time, onChange = { time = it }, keepDay = true)
        Spacer(Modifier.height(16.dp))

        SegmentedToggle(
            options = listOf(stringResource(R.string.diaper_wet), stringResource(R.string.diaper_dirty), stringResource(R.string.diaper_both), stringResource(R.string.diaper_dry)),
            selectedIndex = DiaperType.entries.indexOf(type).coerceAtLeast(0),
            onSelect = { type = DiaperType.entries[it] }
        )

        if (type != DiaperType.DRY) {
            Spacer(Modifier.height(16.dp))
            SectionLabel(stringResource(R.string.diaper_amount))
            ChipGroup(
                options = DIAPER_AMOUNTS.map { diaperValueLabel(it) to it },
                isSelected = { it == amount },
                onToggle = { value -> amount = if (amount == value) "" else value }
            )
        }

        if (showStool) {
            Spacer(Modifier.height(16.dp))
            SectionLabel(stringResource(R.string.diaper_consistency_optional))
            ChipGroup(
                options = CONSISTENCY_OPTIONS.map { (label, value) -> diaperValueLabel(label) to value },
                isSelected = { it == consistency },
                onToggle = { value -> consistency = if (consistency == value) "" else value }
            )
            Spacer(Modifier.height(12.dp))
            SectionLabel(stringResource(R.string.diaper_color))
            ChipGroup(
                options = colorOptions,
                isSelected = { it == color },
                onToggle = { value -> color = if (color == value) "" else value }
            )
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel(stringResource(R.string.dialog_skin_care))
        ChipGroup(
            options = BARRIER_OPTIONS.map { (label, value) -> diaperValueLabel(label) to value },
            isSelected = { it in barriers },
            onToggle = { value -> barriers = if (value in barriers) barriers - value else barriers + value }
        )
        Spacer(Modifier.height(8.dp))
        SwitchRow(label = stringResource(R.string.diaper_rash), checked = rash, onChange = { rash = it })

        Spacer(Modifier.height(16.dp))
        NotesField(value = notes, onChange = { notes = it })
        Spacer(Modifier.height(20.dp))

        DeleteSaveRow(
            onDelete = onDelete,
            onSave = {
                val millis = time
                onSave(
                    diaper.copy(
                        type = type,
                        timeMillis = millis,
                        timeFormatted = formatTime(millis),
                        consistency = consistency,
                        color = color,
                        amount = amount,
                        rashPresent = rash,
                        skinBarrierApplied = barriers,
                        notes = notes.trim()
                    )
                )
            }
        )
    }
}

/** Edit or delete a tummy-time session. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTummyTimeDialog(tummyTime: TummyTimeEvent, onDismiss: () -> Unit, onSave: (TummyTimeEvent) -> Unit, onDelete: () -> Unit) {
    var time by remember(tummyTime.id) { mutableStateOf(tummyTime.timeMillis) }
    var minutes by remember { mutableIntStateOf(tummyTime.durationMinutes.coerceIn(TUMMY_MIN, TUMMY_MAX)) }
    var notes by remember { mutableStateOf(tummyTime.milestone) }

    DialogFrame(onDismiss = onDismiss) {
        DialogTitle(stringResource(R.string.dialog_edit_tummy))
        Spacer(Modifier.height(16.dp))
        // Keeps the entry's own date (it may be from earlier in the week)
        TimeField(label = stringResource(R.string.time), millis = time, onChange = { time = it }, keepDay = true)
        Spacer(Modifier.height(16.dp))

        MinuteStepper(
            label = stringResource(R.string.duration),
            minutes = minutes,
            onChange = { minutes = it },
            min = TUMMY_MIN,
            max = TUMMY_MAX
        )

        Spacer(Modifier.height(20.dp))
        NotesField(value = notes, onChange = { notes = it })
        Spacer(Modifier.height(20.dp))

        DeleteSaveRow(
            onDelete = onDelete,
            onSave = {
                val millis = time
                onSave(
                    tummyTime.copy(
                        timeMillis = millis,
                        timeFormatted = formatTime(millis),
                        durationMinutes = minutes,
                        milestone = notes.trim()
                    )
                )
            }
        )
    }
}

/** Edit or delete a pumping session. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditPumpingDialog(pumping: PumpingEvent, onDismiss: () -> Unit, onSave: (PumpingEvent) -> Unit, onDelete: () -> Unit) {
    var time by remember(pumping.id) { mutableStateOf(pumping.timeMillis) }
    var ml by remember { mutableIntStateOf(pumping.amountMl.coerceIn(ML_MIN, PUMP_ML_MAX)) }
    var minutes by remember { mutableIntStateOf(pumping.durationMinutes.coerceIn(PUMP_MIN_MINUTES, PUMP_MAX_MINUTES)) }
    var notes by remember { mutableStateOf(pumping.notes) }

    DialogFrame(onDismiss = onDismiss) {
        DialogTitle(stringResource(R.string.dialog_edit_pumping))
        Spacer(Modifier.height(16.dp))
        // Keeps the entry's own date (it may be from earlier in the week)
        TimeField(label = stringResource(R.string.time), millis = time, onChange = { time = it }, keepDay = true)
        Spacer(Modifier.height(16.dp))

        AmountStepper(ml = ml, onChange = { ml = it }, max = PUMP_ML_MAX)
        Spacer(Modifier.height(20.dp))
        MinuteStepper(
            label = stringResource(R.string.duration),
            minutes = minutes,
            onChange = { minutes = it },
            min = PUMP_MIN_MINUTES,
            max = PUMP_MAX_MINUTES
        )

        Spacer(Modifier.height(20.dp))
        NotesField(value = notes, onChange = { notes = it })
        Spacer(Modifier.height(20.dp))

        DeleteSaveRow(
            onDelete = onDelete,
            onSave = {
                val millis = time
                onSave(
                    pumping.copy(
                        timeMillis = millis,
                        timeFormatted = formatTime(millis),
                        amountMl = ml,
                        durationMinutes = minutes,
                        notes = notes.trim()
                    )
                )
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Private building blocks
// ---------------------------------------------------------------------------

@Composable
private fun DialogFrame(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = SurfaceColor,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                content = content
            )
        }
    }
}

@Composable
private fun DialogTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = OnSurface
    )
}

@Composable
private fun ChoiceTile(emoji: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(PrimaryFixed)
            .clickable(onClick = onClick)
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(emoji, fontSize = 44.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = PrimaryColor
        )
    }
}

@Composable
fun SegmentedToggle(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(SurfaceContainer)
            .padding(4.dp)
    ) {
        options.forEachIndexed { index, option ->
            val active = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(28.dp))
                    .background(if (active) PrimaryColor else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    option,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (active) OnPrimary else OnSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun MilkToggle(formula: Boolean, onChange: (Boolean) -> Unit) {
    SegmentedToggle(
        options = listOf(stringResource(R.string.dialog_breast_milk), stringResource(R.string.dialog_formula)),
        selectedIndex = if (formula) 1 else 0,
        onSelect = { onChange(it == 1) }
    )
}

@Composable
private fun StepButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(SurfaceContainerHigh)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            symbol,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = if (enabled) PrimaryColor else OnSurfaceVariant.copy(alpha = 0.35f)
        )
    }
}

@Composable
private fun AmountStepper(ml: Int, onChange: (Int) -> Unit, max: Int = ML_MAX) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StepButton("−", enabled = ml > ML_MIN) { onChange((ml - ML_STEP).coerceIn(ML_MIN, max)) }
            Text(
                stringResource(R.string.amount_ml, ml),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = OnSurface
            )
            StepButton("+", enabled = ml < max) { onChange((ml + ML_STEP).coerceIn(ML_MIN, max)) }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ML_PRESETS.forEach { preset ->
                val active = ml == preset
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(if (active) PrimaryColor else SurfaceContainer)
                        .clickable { onChange(preset) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "$preset",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (active) OnPrimary else OnSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun MinuteStepper(
    label: String,
    minutes: Int,
    onChange: (Int) -> Unit,
    min: Int = 0,
    max: Int = Int.MAX_VALUE
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = OnSurface,
            modifier = Modifier.weight(1f)
        )
        StepButton("−", enabled = minutes > min) { onChange((minutes - 1).coerceIn(min, max)) }
        Text(
            stringResource(R.string.amount_minutes, minutes),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = OnSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(96.dp)
        )
        StepButton("+", enabled = minutes < max) { onChange((minutes + 1).coerceIn(min, max)) }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = OnSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
    )
}

/** Pill chips; [options] are (label, stored value) pairs. Selection logic lives in the caller. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipGroup(options: List<Pair<String, String>>, isSelected: (String) -> Boolean, onToggle: (String) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (label, value) ->
            val active = isSelected(value)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (active) PrimaryColor else SurfaceContainer)
                    .clickable { onToggle(value) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (active) OnPrimary else OnSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable { onChange(!checked) }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = OnSurface,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = PrimaryColor, checkedThumbColor = OnPrimary)
        )
    }
}

@Composable
private fun NotesField(value: String, onChange: (String) -> Unit, placeholderText: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.notes)) },
        placeholder = if (placeholderText != null) {
            { Text(placeholderText) }
        } else {
            null
        },
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor, contentColor = OnPrimary),
        shape = RoundedCornerShape(32.dp)
    ) {
        Text(text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp))
    }
}

@Composable
private fun CancelSaveRow(onCancel: () -> Unit, onSave: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel), color = OnSurfaceVariant) }
        Spacer(Modifier.width(8.dp))
        PrimaryButton(text = stringResource(R.string.save), onClick = onSave)
    }
}

@Composable
private fun DeleteSaveRow(onDelete: () -> Unit, onSave: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
            Text(
                stringResource(if (confirmDelete) R.string.tap_again_to_delete else R.string.delete),
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold
            )
        }
        PrimaryButton(text = stringResource(R.string.save), onClick = onSave)
    }
}

/** Keeps the calendar date of [originalMillis], replacing hour and minute. */
private fun withTime(originalMillis: Long, hour: Int, minute: Int): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = originalMillis
    if (cal.get(Calendar.HOUR_OF_DAY) == hour && cal.get(Calendar.MINUTE) == minute) return originalMillis
    cal.set(Calendar.HOUR_OF_DAY, hour)
    cal.set(Calendar.MINUTE, minute)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

private fun formatTime(millis: Long): String = TimeFormat.clock(millis)
