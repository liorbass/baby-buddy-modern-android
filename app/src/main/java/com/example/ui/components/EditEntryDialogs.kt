package com.example.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.BATH_NOTE_TEXT
import com.example.data.model.BreastSide
import com.example.data.model.DIAPER_AMOUNTS
import com.example.data.model.DiaperEvent
import com.example.data.model.DiaperType
import com.example.data.model.FeedType
import com.example.data.model.FeedingEvent
import com.example.data.model.NoteEvent
import com.example.data.model.PumpingEvent
import com.example.data.model.SleepEvent
import com.example.data.model.TummyTimeEvent
import com.example.data.model.diaperValueLabel
import com.example.data.model.formatMinutes
import com.example.ui.theme.OnSurfaceVariant

// Editing (or deleting) one logged entry, from History or Today.

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
internal fun sidesEndingOn(last: BreastSide?, leftSec: Int, rightSec: Int): String? = when (last) {
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
