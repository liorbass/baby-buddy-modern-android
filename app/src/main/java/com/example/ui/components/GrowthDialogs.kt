package com.example.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.GrowthEvent
import com.example.data.model.GrowthKind
import com.example.data.model.formatGrowth
import com.example.data.model.localDatePattern
import com.example.data.model.parseGrowth
import com.example.ui.theme.OnSurfaceVariant
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Growth: weight, length and head circumference logged together (one server entry per filled-in field), edited one measurement at a time. Dates only: the server keeps no time.

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
internal fun DateField(label: String, millis: Long, onChange: (Long) -> Unit) {
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
    TapToEditRow(label = label, value = SimpleDateFormat(localDatePattern("dMMMyyyy"), Locale.getDefault()).format(Date(millis)), onClick = { picking = true })
}

/** The local calendar day of [millis] as UTC midnight (what the Material date picker uses). */
internal fun localToUtcDate(millis: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = millis }
    return Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

/** A date picker's UTC midnight as local noon of the same calendar day. */
internal fun utcDateToLocalNoon(utcMillis: Long): Long {
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
