package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.QuickAction
import com.example.ui.theme.OnSurface
import com.example.ui.theme.OnSurfaceVariant
import com.example.ui.theme.PrimaryColor

// Logging dialogs and forms (bottle, pumping, bath, tummy time, notes, milk stash, shortcuts). Editing an entry:
// EditEntryDialogs.kt; growth: GrowthDialogs.kt; times and durations: TimeFields.kt; shared parts: DialogParts.kt.

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

// ---------------------------------------------------------------------------
// Private building blocks
// ---------------------------------------------------------------------------
