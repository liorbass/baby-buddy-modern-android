package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.AppText
import com.example.data.model.BreastSide
import com.example.data.model.FeedingEvent
import com.example.data.model.LiveTimerState
import com.example.data.model.TimeFormat
import com.example.data.model.dayAndTime
import com.example.data.model.formatMinutes
import com.example.ui.theme.OnPrimary
import com.example.ui.theme.OutlineColor
import com.example.ui.theme.PrimaryColor
import com.example.ui.theme.TimerDisplayStyle

private fun sideName(side: BreastSide) = AppText.get(if (side == BreastSide.LEFT) R.string.side_left else R.string.side_right)

private fun clock(totalSeconds: Int): String = String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60)

/**
 * Breastfeeding controls: one button per side. Tapping a side starts or resumes the feed on it,
 * tapping the running side pauses it. Every tap is saved and synced, so there's no save button.
 */
@Composable
fun BreastSideControls(
    state: LiveTimerState,
    onTapSide: (BreastSide) -> Unit,
    onEndFeed: () -> Unit,
    modifier: Modifier = Modifier,
    showTotal: Boolean = true,
    buttonHeight: Dp = 72.dp,
    lastFeed: FeedingEvent? = null, // most recent breastfeed, to show which side it ended on
    onSetSideSeconds: ((BreastSide, Int) -> Unit)? = null, // manual correction; shows Edit under each side
    onSetStart: ((Long) -> Unit)? = null, // shows the feed's start time, editable
    previousFeedMillis: Long? = null // start of the feed before this one (breast or bottle)
) {
    val hasFeed = state.isRunning && state.feedingLocalId != null
    var editingSide by remember { mutableStateOf<BreastSide?>(null) }
    editingSide?.let { side ->
        SideDurationDialog(
            sideLabel = sideName(side),
            initialSeconds = if (side == BreastSide.LEFT) state.leftSeconds else state.rightSeconds,
            onDismiss = { editingSide = null },
            onSave = { seconds ->
                onSetSideSeconds?.invoke(side, seconds)
                editingSide = null
            }
        )
    }
    val lastSide = if (hasFeed) null else lastFeed?.lastSide
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (showTotal) {
            Text(
                text = clock(state.totalSeconds),
                style = TimerDisplayStyle,
                color = if (hasFeed && !state.isPaused) MaterialTheme.colorScheme.onSurface else OutlineColor
            )
            Text(
                text = when {
                    !hasFeed && lastFeed != null && lastSide != null ->
                        stringResource(R.string.widget_last_ended_on, sideName(lastSide), dayAndTime(lastFeed.timeMillis, lastFeed.timeFormatted))

                    !hasFeed -> stringResource(R.string.feed_tap_side_to_start)

                    state.isPaused -> stringResource(R.string.feed_paused_tap_side)

                    // The start time has its own field when it's editable
                    onSetStart != null -> stringResource(R.string.feed_feeding_on, sideName(state.activeBreast))

                    else -> stringResource(R.string.widget_on_side_started, sideName(state.activeBreast), state.sessionStartedTime)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (hasFeed && previousFeedMillis != null) {
            val gapMinutes = ((state.sessionStartMillis - previousFeedMillis) / 60_000L).toInt()
            Text(
                stringResource(R.string.feed_since_previous, formatMinutes(gapMinutes), TimeFormat.clock(previousFeedMillis)),
                style = MaterialTheme.typography.labelSmall,
                color = OutlineColor
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            SideButton(
                label = stringResource(R.string.side_left),
                seconds = state.leftSeconds,
                running = hasFeed && !state.isPaused && state.activeBreast == BreastSide.LEFT,
                isLast = lastSide == BreastSide.LEFT,
                onClick = { onTapSide(BreastSide.LEFT) },
                height = buttonHeight,
                modifier = Modifier.weight(1f)
            )
            SideButton(
                label = stringResource(R.string.side_right),
                seconds = state.rightSeconds,
                running = hasFeed && !state.isPaused && state.activeBreast == BreastSide.RIGHT,
                isLast = lastSide == BreastSide.RIGHT,
                onClick = { onTapSide(BreastSide.RIGHT) },
                height = buttonHeight,
                modifier = Modifier.weight(1f)
            )
        }

        if (hasFeed && onSetStart != null) {
            TimeField(
                label = stringResource(R.string.started),
                millis = state.sessionStartMillis,
                onChange = onSetStart,
                dialogNote = stringResource(R.string.feed_start_note)
            )
        }

        if (hasFeed && onSetSideSeconds != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(BreastSide.LEFT, BreastSide.RIGHT).forEach { side ->
                    TextButton(onClick = { editingSide = side }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp), tint = OutlineColor)
                        Text(
                            stringResource(R.string.edit_side, sideName(side)),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }
            }
        }

        if (hasFeed) {
            TextButton(onClick = onEndFeed) {
                Text(stringResource(R.string.end_feed), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SideButton(
    label: String,
    seconds: Int,
    running: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
    height: Dp,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val tap = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onClick()
    }
    val content: @Composable () -> Unit = {
        Icon(
            imageVector = if (running) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(22.dp)
        )
        Column(horizontalAlignment = Alignment.Start, modifier = Modifier.padding(start = 8.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(if (isLast) stringResource(R.string.last_side) else clock(seconds), style = MaterialTheme.typography.labelMedium)
        }
    }
    if (running) {
        Button(
            onClick = tap,
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor, contentColor = OnPrimary),
            shape = RoundedCornerShape(24.dp),
            modifier = modifier.height(height)
        ) { content() }
    } else {
        OutlinedButton(
            onClick = tap,
            border = BorderStroke(1.5.dp, PrimaryColor),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryColor),
            shape = RoundedCornerShape(24.dp),
            modifier = modifier.height(height)
        ) { content() }
    }
}
