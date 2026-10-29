package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bathtub
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.BreastSide
import com.example.data.model.FeedingEvent
import com.example.data.model.GrowthEvent
import com.example.data.model.GrowthKind
import com.example.data.model.LiveTimerState
import com.example.data.model.SleepTimerState
import com.example.data.model.TimeFormat
import com.example.data.model.TrackedItem
import com.example.data.model.formatGrowth
import com.example.ui.components.BathForm
import com.example.ui.components.BottleForm
import com.example.ui.components.BreastSideControls
import com.example.ui.components.GrowthForm
import com.example.ui.components.PumpingForm
import com.example.ui.components.latestGrowth
import com.example.ui.theme.ItemColors
import com.example.ui.theme.OnSurfaceVariant
import com.example.ui.theme.PrimaryColor
import com.example.ui.theme.SurfaceContainer
import com.example.ui.theme.SurfaceContainerLowest
import kotlinx.coroutines.delay

/** The entries on the Log screen; tapping one opens its form in place. */
private enum class LogEntry(val labelRes: Int, val item: TrackedItem) {
    BREASTFEED(R.string.log_breastfeeding, TrackedItem.BREASTFEEDING),
    BOTTLE(R.string.log_bottle, TrackedItem.BOTTLE),
    DIAPER(R.string.log_diaper, TrackedItem.DIAPERS),
    SLEEP(R.string.log_sleep, TrackedItem.SLEEP),
    PUMP(R.string.log_pumping, TrackedItem.PUMPING),
    BATH(R.string.log_bath, TrackedItem.BATH),
    GROWTH(R.string.log_growth, TrackedItem.GROWTH)
}

@Composable
fun QuickLogModal(
    liveTimerState: LiveTimerState,
    onClose: () -> Unit,
    onSaveAndSync: () -> Unit,
    onSaveDraft: () -> Unit,
    onQuickAction: (String) -> Unit,
    onSwitchSide: () -> Unit,
    onTogglePause: () -> Unit,
    onLogBottle: (ml: Int, formula: Boolean, notes: String, timeMillis: Long?) -> Unit = { _, _, _, _ -> },
    onTapSide: (BreastSide) -> Unit = {},
    onEndFeed: () -> Unit = {},
    onLogPumping: (ml: Int, minutes: Int, notes: String, startMillis: Long) -> Unit = { _, _, _, _ -> },
    lastPumpMl: Int = 120,
    lastPumpMinutes: Int = 20,
    lastBreastfeed: FeedingEvent? = null,
    previousFeedMillis: Long? = null,
    onSetSideSeconds: (BreastSide, Int) -> Unit = { _, _ -> },
    onSetFeedStart: (Long) -> Unit = {},
    onLogDiaper: DiaperSave = { _, _, _, _, _, _, _, _, _ -> },
    liveFeedNotes: String = "",
    onLiveFeedNotesChange: (String) -> Unit = {},
    hiddenItems: Set<TrackedItem> = emptySet(),
    onLogBath: (notes: String, timeMillis: Long?) -> Unit = { _, _ -> },
    sleepTimer: SleepTimerState = SleepTimerState(),
    onStartSleep: () -> Unit = {},
    onSetSleepStart: (Long) -> Unit = {},
    onSleepNotesChange: (String) -> Unit = {},
    onStopSleep: (isNap: Boolean) -> Unit = {},
    onCancelSleep: () -> Unit = {},
    onLogPastSleep: (start: Long, end: Long, isNap: Boolean, notes: String) -> Unit = { _, _, _, _ -> },
    growth: List<GrowthEvent> = emptyList(),
    onLogGrowth: (values: Map<GrowthKind, Double>, dateMillis: Long, notes: String) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier
) {
    val entries = LogEntry.entries.filter { it.item !in hiddenItems }
    // A running feed opens on its controls; otherwise everything starts closed
    var open by rememberSaveable {
        mutableStateOf(
            when {
                liveTimerState.isRunning -> LogEntry.BREASTFEED.name
                sleepTimer.isRunning -> LogEntry.SLEEP.name
                else -> null
            }
        )
    }
    fun toggle(entry: LogEntry) {
        open = if (open == entry.name) null else entry.name
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.nav_log), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(36.dp).clip(CircleShape).background(SurfaceContainer)
            ) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close), modifier = Modifier.size(18.dp))
            }
        }

        if (entries.isEmpty()) {
            Text(
                stringResource(R.string.log_nothing),
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceVariant
            )
        }

        entries.forEach { entry ->
            val expanded = open == entry.name
            when (entry) {
                LogEntry.BREASTFEED -> LogSection(
                    label = stringResource(entry.labelRes),
                    subtitle = when {
                        liveTimerState.isRunning && liveTimerState.isPaused -> stringResource(R.string.log_feed_paused, clockText(liveTimerState.totalSeconds))
                        liveTimerState.isRunning -> stringResource(R.string.log_feed_running, clockText(liveTimerState.totalSeconds))
                        else -> stringResource(R.string.log_feed_subtitle)
                    },
                    icon = painterResource(R.drawable.ic_widget_feed),
                    tint = ItemColors.tint(entry.item),
                    expanded = expanded,
                    highlight = liveTimerState.isRunning,
                    onToggle = { toggle(entry) }
                ) {
                    FeedPanel(
                        liveTimerState = liveTimerState,
                        onTapSide = onTapSide,
                        onEndFeed = onEndFeed,
                        liveFeedNotes = liveFeedNotes,
                        onLiveFeedNotesChange = onLiveFeedNotesChange,
                        lastBreastfeed = lastBreastfeed,
                        previousFeedMillis = previousFeedMillis,
                        onSetSideSeconds = onSetSideSeconds,
                        onSetFeedStart = onSetFeedStart
                    )
                }

                LogEntry.BOTTLE -> LogSection(
                    label = stringResource(entry.labelRes),
                    subtitle = stringResource(R.string.log_bottle_subtitle),
                    icon = rememberVectorPainter(Icons.Default.LocalDrink),
                    tint = ItemColors.tint(entry.item),
                    expanded = expanded,
                    onToggle = { toggle(entry) }
                ) {
                    BottleForm(
                        initialMl = 120,
                        initialFormula = false,
                        onCancel = { open = null },
                        onSave = { ml, formula, notes, time ->
                            open = null
                            onLogBottle(ml, formula, notes, time)
                        }
                    )
                }

                LogEntry.DIAPER -> LogSection(
                    label = stringResource(entry.labelRes),
                    subtitle = stringResource(R.string.log_diaper_subtitle),
                    icon = painterResource(R.drawable.ic_widget_diaper),
                    tint = ItemColors.tint(entry.item),
                    expanded = expanded,
                    onToggle = { toggle(entry) }
                ) {
                    DiaperForm(
                        onSave = { type, amount, consistency, color, rash, creams, time, notes, startNursing ->
                            open = null
                            onLogDiaper(type, amount, consistency, color, rash, creams, time, notes, startNursing)
                        }
                    )
                }

                LogEntry.SLEEP -> LogSection(
                    label = stringResource(entry.labelRes),
                    subtitle = if (sleepTimer.isRunning) stringResource(R.string.sleep_asleep_since, TimeFormat.clock(sleepTimer.startMillis)) else stringResource(R.string.log_sleep_subtitle),
                    icon = rememberVectorPainter(Icons.Default.Bedtime),
                    tint = ItemColors.tint(entry.item),
                    expanded = expanded,
                    highlight = sleepTimer.isRunning,
                    onToggle = { toggle(entry) }
                ) {
                    SleepPanel(
                        timer = sleepTimer,
                        onStart = onStartSleep,
                        onSetStart = onSetSleepStart,
                        onNotesChange = onSleepNotesChange,
                        onStop = onStopSleep,
                        onCancel = onCancelSleep,
                        onLogPast = { start, end, isNap, notes ->
                            open = null
                            onLogPastSleep(start, end, isNap, notes)
                        },
                        framed = false
                    )
                }

                LogEntry.PUMP -> LogSection(
                    label = stringResource(entry.labelRes),
                    subtitle = stringResource(R.string.log_pumping_subtitle),
                    icon = rememberVectorPainter(Icons.Default.WaterDrop),
                    tint = ItemColors.tint(entry.item),
                    expanded = expanded,
                    onToggle = { toggle(entry) }
                ) {
                    PumpingForm(
                        initialMl = lastPumpMl,
                        initialMinutes = lastPumpMinutes,
                        onCancel = { open = null },
                        onSave = { ml, minutes, notes, start ->
                            open = null
                            onLogPumping(ml, minutes, notes, start)
                        }
                    )
                }

                LogEntry.BATH -> LogSection(
                    label = stringResource(entry.labelRes),
                    subtitle = stringResource(R.string.log_bath_subtitle),
                    icon = rememberVectorPainter(Icons.Default.Bathtub),
                    tint = ItemColors.tint(entry.item),
                    expanded = expanded,
                    onToggle = { toggle(entry) }
                ) {
                    BathForm(
                        onCancel = { open = null },
                        onSave = { notes, time ->
                            open = null
                            onLogBath(notes, time)
                        }
                    )
                }

                LogEntry.GROWTH -> LogSection(
                    label = stringResource(entry.labelRes),
                    subtitle = latestGrowth(growth)[GrowthKind.WEIGHT]?.let { stringResource(R.string.log_last_weight, formatGrowth(GrowthKind.WEIGHT, it.value)) }
                        ?: stringResource(R.string.card_growth_desc),
                    icon = rememberVectorPainter(Icons.Default.MonitorWeight),
                    tint = ItemColors.tint(entry.item),
                    expanded = expanded,
                    onToggle = { toggle(entry) }
                ) {
                    GrowthForm(
                        latest = latestGrowth(growth),
                        onCancel = { open = null },
                        onSave = { values, date, notes ->
                            open = null
                            onLogGrowth(values, date, notes)
                        }
                    )
                }
            }
        }
    }
}

/** One Log entry: a header row that opens/closes [content] below it. */
@Composable
private fun LogSection(
    label: String,
    subtitle: String,
    icon: Painter,
    tint: Color,
    expanded: Boolean,
    onToggle: () -> Unit,
    highlight: Boolean = false,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = SurfaceContainerLowest,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(44.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                }
                Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (highlight) PrimaryColor else OnSurfaceVariant,
                        fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(if (expanded) R.string.close else R.string.open),
                    tint = OnSurfaceVariant
                )
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    content()
                }
            }
        }
    }
}

private fun clockText(totalSeconds: Int): String {
    val s = totalSeconds.coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}

@Composable
private fun FeedPanel(
    liveTimerState: LiveTimerState,
    onTapSide: (BreastSide) -> Unit,
    onEndFeed: () -> Unit,
    liveFeedNotes: String,
    onLiveFeedNotesChange: (String) -> Unit,
    lastBreastfeed: FeedingEvent? = null,
    previousFeedMillis: Long? = null,
    onSetSideSeconds: (BreastSide, Int) -> Unit = { _, _ -> },
    onSetFeedStart: (Long) -> Unit = {}
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BreastSideControls(
            state = liveTimerState,
            onTapSide = onTapSide,
            onEndFeed = onEndFeed,
            lastFeed = lastBreastfeed,
            onSetSideSeconds = onSetSideSeconds,
            onSetStart = onSetFeedStart,
            previousFeedMillis = previousFeedMillis
        )
        val feedId = liveTimerState.feedingLocalId
        if (liveTimerState.isRunning && feedId != null) {
            // Saved a moment after typing stops, like everything else in a running feed
            var note by remember(feedId) { mutableStateOf(liveFeedNotes) }
            LaunchedEffect(feedId, note) {
                delay(800)
                if (note != liveFeedNotes) onLiveFeedNotesChange(note)
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                placeholder = { Text(stringResource(R.string.note_optional)) },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
