package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.SleepEvent
import com.example.data.model.SleepTimerState
import com.example.data.model.TimeFormat
import com.example.data.model.formatMinutes
import com.example.data.model.isNapTime
import com.example.ui.components.EditSleepDialog
import com.example.ui.components.NapToggle
import com.example.ui.components.TimeField
import com.example.ui.theme.OnPrimary
import com.example.ui.theme.OutlineColor
import com.example.ui.theme.PrimaryColor
import com.example.ui.theme.SurfaceContainerLow
import com.example.ui.theme.SurfaceContainerLowest
import com.example.ui.theme.TimerDisplayStyle
import kotlinx.coroutines.delay

private const val DAY_MS = 24 * 60 * 60_000L

private fun elapsed(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}

/** Sleep and naps: a timer that saves when the baby wakes, or a past sleep entered by hand. */
@Composable
fun SleepScreen(
    timer: SleepTimerState,
    todaysSleeps: List<SleepEvent>,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onSetStart: (Long) -> Unit,
    onNotesChange: (String) -> Unit,
    onStop: (isNap: Boolean) -> Unit,
    onCancel: () -> Unit,
    onLogPast: (start: Long, end: Long, isNap: Boolean, notes: String) -> Unit,
    onUpdate: (SleepEvent) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var editing by remember { mutableStateOf<SleepEvent?>(null) }
    editing?.let { sleep ->
        EditSleepDialog(
            sleep = sleep,
            onDismiss = { editing = null },
            onSave = {
                onUpdate(it)
                editing = null
            },
            onDelete = {
                onDelete(sleep.id)
                editing = null
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.clip(CircleShape).background(SurfaceContainerLow)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(stringResource(R.string.item_sleep), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        SleepPanel(timer, onStart, onSetStart, onNotesChange, onStop, onCancel, onLogPast)

        if (todaysSleeps.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.today), style = MaterialTheme.typography.titleSmall, color = OutlineColor)
                todaysSleeps.forEach { sleep ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(SurfaceContainerLowest)
                            .clickable { editing = sleep }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${sleep.timeFormatted} – ${TimeFormat.clock(sleep.endMillis)}",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            stringResource(if (sleep.isNap) R.string.sleep_duration_nap else R.string.sleep_duration_night, formatMinutes(sleep.durationMinutes)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.size(8.dp))
    }
}

/**
 * The sleep controls: the running timer (fell asleep, nap, notes, woke up), or a start button and a form
 * for a past sleep. Shared by the Sleep screen and the Log screen ([framed] = false inside a Log entry).
 */
@Composable
fun SleepPanel(
    timer: SleepTimerState,
    onStart: () -> Unit,
    onSetStart: (Long) -> Unit,
    onNotesChange: (String) -> Unit,
    onStop: (isNap: Boolean) -> Unit,
    onCancel: () -> Unit,
    onLogPast: (start: Long, end: Long, isNap: Boolean, notes: String) -> Unit,
    framed: Boolean = true
) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (timer.isRunning) {
            RunningSleep(timer, onSetStart, onNotesChange, onStop, onCancel, framed)
        } else {
            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor, contentColor = OnPrimary),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (framed) 72.dp else 56.dp)
            ) {
                Icon(Icons.Default.Bedtime, contentDescription = null, modifier = Modifier.size(22.dp))
                Text(
                    stringResource(R.string.sleep_start_timer),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
            PastSleepForm(onLogPast)
        }
    }
}

@Composable
private fun RunningSleep(
    timer: SleepTimerState,
    onSetStart: (Long) -> Unit,
    onNotesChange: (String) -> Unit,
    onStop: (isNap: Boolean) -> Unit,
    onCancel: () -> Unit,
    framed: Boolean = true
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(timer.startMillis) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000L)
        }
    }
    var isNap by remember(timer.startMillis) { mutableStateOf(isNapTime(timer.startMillis)) }
    var notes by remember { mutableStateOf(timer.notes) }
    LaunchedEffect(notes) {
        delay(800)
        if (notes != timer.notes) onNotesChange(notes)
    }
    var confirmDiscard by remember { mutableStateOf(false) }

    Surface(shape = RoundedCornerShape(24.dp), color = if (framed) SurfaceContainerLowest else Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(if (framed) 20.dp else 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(elapsed((now - timer.startMillis) / 1000L), style = TimerDisplayStyle)
            Text(
                stringResource(R.string.sleep_asleep_since, TimeFormat.clock(timer.startMillis)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TimeField(label = stringResource(R.string.fell_asleep), millis = timer.startMillis, onChange = onSetStart)
            NapToggle(isNap = isNap, onChange = { isNap = it })
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text(stringResource(R.string.notes)) },
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    if (notes != timer.notes) onNotesChange(notes)
                    onStop(isNap)
                },
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor, contentColor = OnPrimary),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(stringResource(R.string.sleep_woke_up_save), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = { if (confirmDiscard) onCancel() else confirmDiscard = true }) {
                Text(
                    stringResource(if (confirmDiscard) R.string.tap_again_to_discard else R.string.sleep_discard_timer),
                    color = if (confirmDiscard) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PastSleepForm(onLogPast: (start: Long, end: Long, isNap: Boolean, notes: String) -> Unit) {
    val openedAt = remember { System.currentTimeMillis() }
    var start by remember { mutableLongStateOf(openedAt - 60 * 60_000L) }
    var end by remember { mutableLongStateOf(openedAt) }
    var napChoice by remember { mutableStateOf<Boolean?>(null) } // null = by start time
    var notes by remember { mutableStateOf("") }
    // Both times are the latest past moment at that clock time; a start after the end means the day before
    val fixedStart = if (start > end) start - DAY_MS else start
    val isNap = napChoice ?: isNapTime(fixedStart)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.sleep_log_past), style = MaterialTheme.typography.titleSmall, color = OutlineColor)
        TimeField(label = stringResource(R.string.fell_asleep), millis = start, onChange = { start = it })
        TimeField(label = stringResource(R.string.woke_up), millis = end, onChange = { end = it })
        Text(
            stringResource(R.string.sleep_slept, formatMinutes(((end - fixedStart) / 60_000L).toInt())),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp)
        )
        NapToggle(isNap = isNap, onChange = { napChoice = it })
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text(stringResource(R.string.notes)) },
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                onLogPast(fixedStart, end, isNap, notes.trim())
                notes = ""
            },
            enabled = end > fixedStart,
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor, contentColor = OnPrimary),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.sleep_save), fontWeight = FontWeight.Bold)
        }
    }
}
