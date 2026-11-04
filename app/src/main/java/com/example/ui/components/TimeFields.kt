package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.TimeFormat
import com.example.data.model.dayAndTime
import com.example.ui.theme.OnSurface
import com.example.ui.theme.OnSurfaceVariant
import com.example.ui.theme.PrimaryColor
import com.example.ui.theme.SurfaceContainer
import java.util.Calendar

// Picked times and durations: one moment (millis) or one length, typed or on wheels (TimeFormat.useWheel), shown as tap-to-edit rows that open one picker in a dialog.

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

/** A form row showing [label] and [value] with an edit icon; tapping it opens that value's picker. */
@Composable
fun TapToEditRow(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = OnSurface, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = PrimaryColor)
        Icon(
            Icons.Default.Edit,
            contentDescription = stringResource(R.string.dialog_change_label, label),
            tint = OnSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp).size(18.dp)
        )
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
    TapToEditRow(label = label, value = valueText ?: TimeFormat.clock(millis), onClick = { picking = true }, modifier = modifier)
}

/** A [TimeField] that shows "Now · 19:06" until a time is picked ([picked] null = now). */
@Composable
internal fun NowTimeField(label: String, picked: Long?, onChange: (Long) -> Unit) {
    val openedAt = remember { System.currentTimeMillis() }
    TimeField(
        label = label,
        millis = picked ?: openedAt,
        onChange = onChange,
        valueText = if (picked == null) stringResource(R.string.now_at, TimeFormat.clock(openedAt)) else null
    )
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
    TapToEditRow(label = label, value = "%d:%02d".format(minutes / 60, minutes % 60), onClick = { picking = true }, modifier = modifier)
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
    TapToEditRow(label = label, value = "%02d:%02d".format(seconds / 60, seconds % 60), onClick = { picking = true }, modifier = modifier)
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
internal fun DurationTyped(label: String, seconds: Int, onChange: (Int) -> Unit) {
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

/** Keeps the calendar date of [originalMillis], replacing hour and minute. */
internal fun withTime(originalMillis: Long, hour: Int, minute: Int): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = originalMillis
    if (cal.get(Calendar.HOUR_OF_DAY) == hour && cal.get(Calendar.MINUTE) == minute) return originalMillis
    cal.set(Calendar.HOUR_OF_DAY, hour)
    cal.set(Calendar.MINUTE, minute)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

internal fun formatTime(millis: Long): String = TimeFormat.clock(millis)
