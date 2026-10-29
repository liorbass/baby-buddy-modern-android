package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bathtub
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.ml.RhythmTarget
import com.example.data.model.AppText
import com.example.data.model.BabySex
import com.example.data.model.BreastSide
import com.example.data.model.DashboardWidgetConfig
import com.example.data.model.DashboardWidgetType
import com.example.data.model.DiaperEvent
import com.example.data.model.DiaperType
import com.example.data.model.FeedIntervalStats
import com.example.data.model.FeedType
import com.example.data.model.FeedingEvent
import com.example.data.model.GrowthEvent
import com.example.data.model.GrowthKind
import com.example.data.model.HandoffSummary
import com.example.data.model.LiveTimerState
import com.example.data.model.MILK_STASH_TAG
import com.example.data.model.NoteEvent
import com.example.data.model.NoteTag
import com.example.data.model.PumpingEvent
import com.example.data.model.QuickAction
import com.example.data.model.ReminderSettings
import com.example.data.model.ServerConnectionConfig
import com.example.data.model.SleepEvent
import com.example.data.model.SleepTimerState
import com.example.data.model.TemperatureEvent
import com.example.data.model.TimeFormat
import com.example.data.model.TodayTile
import com.example.data.model.TrackedItem
import com.example.data.model.TummyTimeEvent
import com.example.data.model.TummyTimeGoal
import com.example.data.model.WakeWindow
import com.example.data.model.WakeWindowPart
import com.example.data.model.averageMinutes
import com.example.data.model.completedWakeWindows
import com.example.data.model.dayAndTime
import com.example.data.model.diaperTypeLabel
import com.example.data.model.formatMinutes
import com.example.ui.HistoryFilter
import com.example.ui.ScreenDestination
import com.example.ui.components.BathDialog
import com.example.ui.components.BottleDialog
import com.example.ui.components.BreastSideControls
import com.example.ui.components.FeedTypeChooserDialog
import com.example.ui.components.GrowthWidgetCard
import com.example.ui.components.MilkStashDialog
import com.example.ui.components.NoteDialog
import com.example.ui.components.NotesWidgetCard
import com.example.ui.components.PumpingDialog
import com.example.ui.components.QuickActionsEditorDialog
import com.example.ui.components.TemperatureWidgetCard
import com.example.ui.components.TummyTimeDialog
import com.example.ui.components.TummyTimeGoalWidgetCard
import com.example.ui.components.WakeWindowsWidgetCard
import com.example.ui.theme.ItemColors
import com.example.ui.theme.OnPrimary
import com.example.ui.theme.OutlineColor
import com.example.ui.theme.PrimaryColor
import com.example.ui.theme.PrimaryFixed
import com.example.ui.theme.SurfaceContainerHigh
import com.example.ui.theme.SurfaceContainerLow
import com.example.ui.theme.SurfaceContainerLowest
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayDashboardScreen(
    liveTimerState: LiveTimerState,
    connectionConfig: ServerConnectionConfig,
    recentFeedings: List<FeedingEvent>,
    handoff: HandoffSummary? = null, // what other caregivers logged while this phone was away
    onDismissHandoff: () -> Unit = {},
    recentDiapers: List<DiaperEvent> = emptyList(),
    recentTummyTimes: List<TummyTimeEvent> = emptyList(),
    recentPumpings: List<PumpingEvent> = emptyList(),
    dashboardWidgets: List<DashboardWidgetConfig> = emptyList(),
    tummyTimeGoal: TummyTimeGoal = TummyTimeGoal(),
    temperatures: List<TemperatureEvent> = emptyList(),
    growth: List<GrowthEvent> = emptyList(),
    birthDate: String? = null,
    babySex: BabySex? = null,
    notes: List<NoteEvent> = emptyList(),
    feedIntervals: FeedIntervalStats? = null,
    lastBreastfeed: FeedingEvent? = null,
    lastFeed: FeedingEvent? = null, // breast or bottle
    previousFeedMillis: Long? = null, // while a feed runs, when the one before it started
    lastDiaper: DiaperEvent? = null,
    quickActions: List<QuickAction> = QuickAction.DEFAULT,
    onQuickActionsChange: (List<QuickAction>) -> Unit = {},
    onLogPumping: (ml: Int, minutes: Int, notes: String, startMillis: Long) -> Unit = { _, _, _, _ -> },
    sleepTimer: SleepTimerState = SleepTimerState(),
    recentSleeps: List<SleepEvent> = emptyList(),
    milkStash: Int? = null,
    onSetMilkStash: (Int) -> Unit = {},
    noteTags: List<NoteTag> = emptyList(),
    onAddTaggedNote: (text: String, tags: List<String>) -> Unit = { _, _ -> },
    lastFeedMillis: Long? = null,
    lastSleepEndMillis: Long? = null,
    reminders: ReminderSettings = ReminderSettings(),
    modelNext: Map<RhythmTarget, Long> = emptyMap(), // picked rhythm models' next times (Trends → Models)
    hiddenItems: Set<TrackedItem> = emptySet(),
    hiddenTiles: Set<TodayTile> = emptySet(), // summary tiles turned off in Customize Today
    hiddenWakeParts: Set<WakeWindowPart> = emptySet(), // Wake windows card parts turned off there
    lastBathMillis: Long? = null,
    onLogBath: (notes: String, timeMillis: Long?) -> Unit = { _, _ -> },
    onOpenHistory: (HistoryFilter) -> Unit = {},
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onNavigate: (ScreenDestination) -> Unit,
    onOpenAddWidgetModal: () -> Unit = {},
    onAddTummyTimeMinutes: (Int) -> Unit = {},
    onLogTemperature: (Double, String) -> Unit = { _, _ -> },
    onLogGrowth: (values: Map<GrowthKind, Double>, dateMillis: Long, notes: String) -> Unit = { _, _, _ -> },
    onAddNote: (String) -> Unit = {},
    onRemoveDashboardWidget: (DashboardWidgetType) -> Unit = {},
    onLogBottle: (ml: Int, formula: Boolean, notes: String) -> Unit = { _, _, _ -> },
    onTapSide: (BreastSide) -> Unit = {},
    onEndFeed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showFeedChooser by remember { mutableStateOf(false) }
    var showBottleDialog by remember { mutableStateOf(false) }
    var showPumpDialog by remember { mutableStateOf(false) }
    var showTummyDialog by remember { mutableStateOf(false) }
    var showActionsEditor by remember { mutableStateOf(false) }
    var showStashDialog by remember { mutableStateOf(false) }
    var showBathDialog by remember { mutableStateOf(false) }

    // Leave out what this phone doesn't track (Settings → Tracked items)
    val feedingShown = TrackedItem.BREASTFEEDING !in hiddenItems || TrackedItem.BOTTLE !in hiddenItems
    val visibleFeedings = recentFeedings.filter { f ->
        if (f.type == FeedType.NURSING) TrackedItem.BREASTFEEDING !in hiddenItems else TrackedItem.BOTTLE !in hiddenItems
    }
    val availableActions = QuickAction.entries.filter { action ->
        when (action) {
            QuickAction.FEEDING -> feedingShown
            QuickAction.DIAPER -> TrackedItem.DIAPERS !in hiddenItems
            QuickAction.PUMP -> TrackedItem.PUMPING !in hiddenItems
            QuickAction.TUMMY_TIME -> TrackedItem.TUMMY_TIME !in hiddenItems
            QuickAction.SLEEP -> TrackedItem.SLEEP !in hiddenItems
            QuickAction.BATH -> TrackedItem.BATH !in hiddenItems
        }
    }
    var showNoteDialog by remember { mutableStateOf(false) }

    // Today's finished wake windows, for the Wake windows card and summary tile
    val todayStart = remember(recentSleeps) {
        java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val wakeWindows = remember(recentSleeps, todayStart) { completedWakeWindows(recentSleeps, todayStart) }

    if (showStashDialog) {
        MilkStashDialog(
            initialMl = milkStash,
            onDismiss = { showStashDialog = false },
            onSave = { ml ->
                showStashDialog = false
                onSetMilkStash(ml)
            }
        )
    }
    if (showNoteDialog) {
        NoteDialog(
            tags = noteTags.map { it.name },
            onDismiss = { showNoteDialog = false },
            onSave = { text, tags ->
                showNoteDialog = false
                onAddTaggedNote(text, tags)
            }
        )
    }

    if (showPumpDialog) {
        val lastPump = recentPumpings.firstOrNull()
        PumpingDialog(
            initialMl = lastPump?.amountMl ?: 120,
            initialMinutes = lastPump?.durationMinutes ?: 20,
            onDismiss = { showPumpDialog = false },
            onSave = { ml, minutes, notes, start ->
                showPumpDialog = false
                onLogPumping(ml, minutes, notes, start)
            }
        )
    }
    if (showBathDialog) {
        BathDialog(
            onDismiss = { showBathDialog = false },
            onSave = { notes, time ->
                showBathDialog = false
                onLogBath(notes, time)
            }
        )
    }
    if (showTummyDialog) {
        TummyTimeDialog(
            initialMinutes = recentTummyTimes.firstOrNull()?.durationMinutes ?: 5,
            onDismiss = { showTummyDialog = false },
            onSave = { minutes ->
                showTummyDialog = false
                onAddTummyTimeMinutes(minutes)
            }
        )
    }
    if (showActionsEditor) {
        QuickActionsEditorDialog(
            current = quickActions.filter { it in availableActions },
            available = availableActions,
            onDismiss = { showActionsEditor = false },
            onSave = { actions ->
                showActionsEditor = false
                onQuickActionsChange(actions)
            }
        )
    }

    if (showFeedChooser) {
        FeedTypeChooserDialog(
            onDismiss = { showFeedChooser = false },
            onBreastfeed = {
                showFeedChooser = false
                onNavigate(ScreenDestination.ACTIVE_NURSING_TIMER)
            },
            onBottle = {
                showFeedChooser = false
                showBottleDialog = true
            }
        )
    }
    if (showBottleDialog) {
        // Start from the last bottle's amount and contents
        val lastBottle = recentFeedings.firstOrNull { it.amountMl != null }
        BottleDialog(
            initialMl = lastBottle?.amountMl ?: 120,
            initialFormula = lastBottle?.bottleFormula ?: false,
            onDismiss = { showBottleDialog = false },
            onSave = { ml, formula, notes ->
                showBottleDialog = false
                onLogBottle(ml, formula, notes)
            }
        )
    }

    PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }

            // Logging buttons first, then the running feed (if any), then the last feed / last diaper pair
            item {
                QuickActionsRow(
                    actions = quickActions.filter { it in availableActions },
                    onAction = { action ->
                        when (action) {
                            QuickAction.FEEDING -> when {
                                // Only one kind of feed tracked: skip the chooser
                                TrackedItem.BOTTLE in hiddenItems -> onNavigate(ScreenDestination.ACTIVE_NURSING_TIMER)

                                TrackedItem.BREASTFEEDING in hiddenItems -> showBottleDialog = true

                                else -> showFeedChooser = true
                            }

                            QuickAction.DIAPER -> onNavigate(ScreenDestination.LOG_DIAPER)

                            QuickAction.PUMP -> showPumpDialog = true

                            QuickAction.TUMMY_TIME -> showTummyDialog = true

                            QuickAction.SLEEP -> onNavigate(ScreenDestination.SLEEP)

                            QuickAction.BATH -> showBathDialog = true
                        }
                    },
                    onEdit = { showActionsEditor = true }
                )
            }

            if (liveTimerState.isRunning) {
                item(key = "live_feed") {
                    LiveFeedingBanner(
                        liveTimerState = liveTimerState,
                        onTapSide = onTapSide,
                        onEndFeed = onEndFeed,
                        onOpenFullTimer = { onNavigate(ScreenDestination.ACTIVE_NURSING_TIMER) },
                        previousFeedMillis = previousFeedMillis
                    )
                }
            }

            if (sleepTimer.isRunning) {
                item(key = "live_sleep") {
                    SleepingBanner(startMillis = sleepTimer.startMillis, onClick = { onNavigate(ScreenDestination.SLEEP) })
                }
            }

            if (handoff != null) {
                item(key = "handoff") {
                    HandoffCard(summary = handoff, onOpen = { onOpenHistory(HistoryFilter.ALL) }, onDismiss = onDismissHandoff)
                }
            }

            // Cards (Customize Today), side by side when both are on
            fun cardOn(type: DashboardWidgetType) = dashboardWidgets.any { it.type == type && it.isEnabled }
            val showLastFeed = feedingShown && cardOn(DashboardWidgetType.LAST_FEED)
            val showLastDiaper = TrackedItem.DIAPERS !in hiddenItems && cardOn(DashboardWidgetType.LAST_DIAPER)
            if (showLastFeed || showLastDiaper) {
                item(key = "last_entries") {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (showLastFeed) {
                            LastFeedCard(
                                recentFeedings = visibleFeedings,
                                latestFeed = lastFeed?.takeIf {
                                    (if (it.type == FeedType.NURSING) TrackedItem.BREASTFEEDING else TrackedItem.BOTTLE) !in hiddenItems
                                },
                                lastBreastfeed = lastBreastfeed.takeIf { TrackedItem.BREASTFEEDING !in hiddenItems },
                                onClick = { onOpenHistory(HistoryFilter.FEEDS) },
                                onRemove = { onRemoveDashboardWidget(DashboardWidgetType.LAST_FEED) },
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                        if (showLastDiaper) {
                            LastDiaperCard(
                                lastDiaper = lastDiaper,
                                onClick = { onOpenHistory(HistoryFilter.DIAPERS) },
                                onRemove = { onRemoveDashboardWidget(DashboardWidgetType.LAST_DIAPER) },
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                    }
                }
            }

            // Awake for … / next feed around …
            val feedEvery = reminders.feedAfterMinutes.takeIf { it > 0 } ?: feedIntervals?.thisWeekAverageMinutes
            // (a sleep ending later than now, e.g. one entered with a wrong date, would leave the card empty)
            val awakeSince = lastSleepEndMillis.takeIf {
                !sleepTimer.isRunning && TrackedItem.SLEEP !in hiddenItems && it != null && it <= System.currentTimeMillis()
            }
            val showWakeWindows = TrackedItem.SLEEP !in hiddenItems && cardOn(DashboardWidgetType.WAKE_WINDOWS)
            if (showWakeWindows) {
                item(key = "wake_windows") {
                    WakeWindowsWidgetCard(
                        awakeSince = awakeSince,
                        sleepingSince = sleepTimer.startMillis.takeIf { sleepTimer.isRunning },
                        nextSleepAt = modelNext[RhythmTarget.AWAKE_WINDOW]
                            ?: awakeSince?.let { since -> reminders.awakeAfterMinutes.takeIf { it > 0 }?.let { since + it * 60_000L } },
                        windows = wakeWindows,
                        parts = WakeWindowPart.entries.toSet() - hiddenWakeParts,
                        onOpenSleep = { onNavigate(ScreenDestination.SLEEP) },
                        onRemoveWidget = { onRemoveDashboardWidget(DashboardWidgetType.WAKE_WINDOWS) }
                    )
                }
            }
            val nextFeedAt = modelNext[RhythmTarget.FEED_GAP]
                ?: if (!feedingShown || liveTimerState.isRunning || lastFeedMillis == null || feedEvery == null) {
                    null
                } else {
                    lastFeedMillis + feedEvery * 60_000L
                }
            // Left out when the Wake windows card already shows them
            val wakeCardShows = { part: WakeWindowPart -> showWakeWindows && part !in hiddenWakeParts }
            val otherNext = listOfNotNull(
                modelNext[RhythmTarget.DIAPER_GAP]?.let { AppText.get(R.string.rhythm_diaper_gap) to it },
                modelNext[RhythmTarget.AWAKE_WINDOW]?.takeUnless { wakeCardShows(WakeWindowPart.NAP_DUE) }?.let { AppText.get(R.string.rhythm_awake_window) to it },
                modelNext[RhythmTarget.SLEEP_LENGTH]?.let { AppText.get(R.string.rhythm_sleep_length) to it }
            )
            val nextUpAwakeSince = awakeSince.takeUnless { wakeCardShows(WakeWindowPart.AWAKE_FOR) }
            if (nextUpAwakeSince != null || nextFeedAt != null || otherNext.isNotEmpty()) {
                item(key = "next_up") {
                    NextUpCard(
                        awakeSince = nextUpAwakeSince,
                        nextFeedAt = nextFeedAt,
                        onSleepClick = { onNavigate(ScreenDestination.SLEEP) },
                        modelNext = otherNext
                    )
                }
            }

            item {
                TodaySummaryGrid(
                    recentFeedings = visibleFeedings,
                    recentDiapers = recentDiapers,
                    recentTummyTimes = recentTummyTimes,
                    recentPumpings = recentPumpings,
                    recentSleeps = recentSleeps,
                    hidden = hiddenItems,
                    hiddenTiles = hiddenTiles,
                    awakeSince = awakeSince,
                    wakeWindows = wakeWindows,
                    onWakeWindowClick = { onNavigate(ScreenDestination.SLEEP) },
                    sleepTimer = sleepTimer,
                    milkStash = milkStash,
                    onMilkStashClick = { showStashDialog = true },
                    lastBathMillis = lastBathMillis,
                    onBathClick = { showBathDialog = true },
                    onOpenHistory = onOpenHistory
                )
            }

            if (TrackedItem.QUICK_NOTES !in hiddenItems) {
                item(key = "quick_notes") {
                    QuickNotesCard(
                        tags = noteTags,
                        todaysNotes = notes,
                        onTag = { tag -> onAddTaggedNote(tag.name.replaceFirstChar(Char::uppercase), listOf(tag.name)) },
                        onAddNote = { showNoteDialog = true }
                    )
                }
            }

            if (recentPumpings.isNotEmpty() && TrackedItem.PUMPING !in hiddenItems) {
                item { LastPumpCard(recentPumpings = recentPumpings) }
            }

            if (feedIntervals?.thisWeekAverageMinutes != null && feedingShown) {
                item { FeedIntervalCard(feedIntervals) }
            }

            dashboardWidgets.filter {
                it.isEnabled && it.type !in TOP_CARDS && !(it.type == DashboardWidgetType.TUMMY_TIME_GOAL && TrackedItem.TUMMY_TIME in hiddenItems) &&
                    !(it.type == DashboardWidgetType.GROWTH && TrackedItem.GROWTH in hiddenItems)
            }.forEach { widget ->
                item(key = "widget_${widget.type.name}") {
                    when (widget.type) {
                        // Shown near the top
                        DashboardWidgetType.LAST_FEED, DashboardWidgetType.LAST_DIAPER, DashboardWidgetType.WAKE_WINDOWS -> Unit

                        DashboardWidgetType.TUMMY_TIME_GOAL -> TummyTimeGoalWidgetCard(
                            goalMinutes = tummyTimeGoal.dailyGoalMinutes,
                            todayMinutes = recentTummyTimes.sumOf { it.durationMinutes },
                            onAddMinutes = onAddTummyTimeMinutes,
                            onRemoveWidget = { onRemoveDashboardWidget(widget.type) }
                        )

                        DashboardWidgetType.HEALTH_VITALS -> TemperatureWidgetCard(
                            temperatures = temperatures,
                            onLogTemperature = onLogTemperature,
                            onRemoveWidget = { onRemoveDashboardWidget(widget.type) }
                        )

                        DashboardWidgetType.GROWTH -> GrowthWidgetCard(
                            growth = growth,
                            birthDate = birthDate,
                            sex = babySex,
                            onLogGrowth = onLogGrowth,
                            onRemoveWidget = { onRemoveDashboardWidget(widget.type) }
                        )

                        DashboardWidgetType.CAREGIVER_NOTES -> NotesWidgetCard(
                            notes = notes.filterNot { MILK_STASH_TAG in it.tags },
                            onAddNote = onAddNote,
                            onRemoveWidget = { onRemoveDashboardWidget(widget.type) }
                        )
                    }
                }
            }

            item {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(
                        onClick = onOpenAddWidgetModal,
                        modifier = Modifier.testTag("customize_dashboard_btn")
                    ) {
                        Icon(Icons.Default.Widgets, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.customize_title), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
fun LiveFeedingBanner(
    liveTimerState: LiveTimerState,
    onTapSide: (BreastSide) -> Unit,
    onEndFeed: () -> Unit,
    onOpenFullTimer: () -> Unit,
    previousFeedMillis: Long? = null
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = PrimaryFixed.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenFullTimer() }
            .testTag("live_feed_banner")
    ) {
        BreastSideControls(
            state = liveTimerState,
            onTapSide = onTapSide,
            onEndFeed = onEndFeed,
            buttonHeight = 64.dp,
            modifier = Modifier.padding(20.dp),
            previousFeedMillis = previousFeedMillis
        )
    }
}

/** Shown while a sleep is being timed; opens the Sleep screen. */
@Composable
private fun SleepingBanner(startMillis: Long, onClick: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startMillis) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000L)
        }
    }
    val s = ((now - startMillis) / 1000L).coerceAtLeast(0)
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainerHigh),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Bedtime, contentDescription = null, tint = PrimaryColor, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
                Text(stringResource(R.string.sleeping), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.tile_since_tap_when_awake, TimeFormat.clock(startMillis)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** "While you were away": the feeds, changes and sleeps other caregivers logged since this phone left the app. */
@Composable
private fun HandoffCard(summary: HandoffSummary, onOpen: () -> Unit, onDismiss: () -> Unit) {
    class Line(val at: Long, val item: TrackedItem, val text: String, val who: String?)
    val lines = (
        summary.feedings.map { f ->
            val text = when (f.type) {
                FeedType.NURSING -> listOfNotNull(
                    stringResource(R.string.tile_feed_name),
                    stringResource(R.string.left_short_time, formatMinutes(f.leftDurationMinutes)).takeIf { f.leftDurationSec > 0 },
                    stringResource(R.string.right_short_time, formatMinutes(f.rightDurationMinutes)).takeIf { f.rightDurationSec > 0 }
                ).joinToString(" · ")

                FeedType.BOTTLE -> listOfNotNull(stringResource(R.string.log_bottle), f.amountMl?.let { stringResource(R.string.amount_ml, it) }, stringResource(if (f.bottleFormula) R.string.today_formula else R.string.today_breast_milk)).joinToString(" · ")

                FeedType.SOLID -> stringResource(R.string.today_solid_food)
            }
            Line(f.timeMillis, if (f.type == FeedType.NURSING) TrackedItem.BREASTFEEDING else TrackedItem.BOTTLE, text, f.caregiver)
        } +
            summary.diapers.map { d ->
                val kind = when (d.type) {
                    DiaperType.WET -> stringResource(R.string.today_handoff_wet)
                    DiaperType.DIRTY -> stringResource(R.string.today_handoff_dirty)
                    DiaperType.BOTH -> stringResource(R.string.today_handoff_both)
                    DiaperType.DRY -> stringResource(R.string.today_handoff_dry)
                }
                Line(d.timeMillis, TrackedItem.DIAPERS, stringResource(R.string.today_handoff_diaper, kind), d.caregiver)
            } +
            summary.sleeps.map { s ->
                Line(s.timeMillis, TrackedItem.SLEEP, stringResource(if (s.isNap) R.string.today_handoff_nap else R.string.today_handoff_sleep, formatMinutes(s.durationMinutes)), s.caregiver)
            }
        ).sortedBy { it.at }
    val shown = 6
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f).padding(top = 8.dp)) {
                    Text(stringResource(R.string.today_handoff_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        listOfNotNull(
                            stringResource(R.string.since_time, dayAndTime(summary.sinceMillis, TimeFormat.clock(summary.sinceMillis))),
                            summary.caregivers.takeIf { it.isNotEmpty() }?.joinToString(", ")
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = OutlineColor
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.dismiss), tint = OutlineColor)
                }
            }
            lines.take(shown).forEach { line ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(ItemColors.tint(line.item))
                    )
                    Text(
                        TimeFormat.clock(line.at),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 10.dp).width(64.dp)
                    )
                    Text(line.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    line.who?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = OutlineColor) }
                }
            }
            if (lines.size > shown) {
                Text(stringResource(R.string.today_handoff_more, lines.size - shown), style = MaterialTheme.typography.bodySmall, color = OutlineColor)
            }
        }
    }
}

/** "Just now", "45m ago", "2h 15m ago", "1d 3h ago". */
private fun sinceLabel(elapsedMillis: Long): String {
    val minutes = (elapsedMillis / 60_000L).coerceAtLeast(0)
    return when {
        minutes < 1 -> AppText.get(R.string.just_now)
        minutes < 60 -> AppText.get(R.string.ago_minutes, minutes)
        minutes < 24 * 60 -> AppText.get(R.string.ago_hours_minutes, minutes / 60, minutes % 60)
        else -> AppText.get(R.string.ago_days_hours, minutes / (24 * 60), (minutes / 60) % 24)
    }
}

// Shown near the top of Today rather than with the other cards
private val TOP_CARDS = setOf(DashboardWidgetType.LAST_FEED, DashboardWidgetType.LAST_DIAPER, DashboardWidgetType.WAKE_WINDOWS)

/** The current time, updated on each minute boundary (for "… ago" labels). */
@Composable
private fun rememberMinuteClock(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000L - now % 60_000L)
        }
    }
    return now
}

/** Tappable summary card for the "last …" pair at the top of Today, with a small remove button. */
@Composable
private fun LastEntryCard(
    label: String,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainerLowest),
        elevation = CardDefaults.cardElevation(1.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = OutlineColor, modifier = Modifier.weight(1f))
                IconButton(onClick = onRemove, modifier = Modifier.size(20.dp)) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cards_remove), tint = OutlineColor, modifier = Modifier.size(14.dp))
                }
            }
            content()
        }
    }
}

@Composable
private fun LastFeedCard(
    recentFeedings: List<FeedingEvent>,
    latestFeed: FeedingEvent?,
    lastBreastfeed: FeedingEvent?,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Today's last feed, else the last feed of either kind from the server (e.g. last night's bottle)
    val lastFeed = recentFeedings.firstOrNull() ?: latestFeed ?: lastBreastfeed
    val lastNursing = lastBreastfeed ?: recentFeedings.firstOrNull { it.type == FeedType.NURSING }
    val lastSide = lastNursing?.lastSide

    LastEntryCard(label = stringResource(R.string.card_last_feed), onClick = onClick, onRemove = onRemove, modifier = modifier) {
        if (lastFeed == null) {
            Text(stringResource(R.string.none_yet), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            return@LastEntryCard
        }
        Text(
            dayAndTime(lastFeed.timeMillis, lastFeed.timeFormatted),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        // Time since the feed started, refreshed every minute
        val now = rememberMinuteClock()
        Text(
            sinceLabel(now - lastFeed.timeMillis),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = PrimaryColor
        )
        Text(
            text = if (lastFeed.amountMl != null) {
                stringResource(if (lastFeed.bottleFormula) R.string.today_last_bottle_formula else R.string.today_last_bottle_breast_milk, lastFeed.amountMl)
            } else {
                stringResource(R.string.today_last_breastfeed, lastFeed.totalDurationMinutes)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (lastNursing != null) {
            Spacer(modifier = Modifier.height(6.dp))
            if (lastNursing.id != lastFeed.id) {
                Text(
                    stringResource(R.string.today_last_breast, dayAndTime(lastNursing.timeMillis, lastNursing.timeFormatted)),
                    style = MaterialTheme.typography.labelSmall,
                    color = OutlineColor
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SideChip(stringResource(R.string.side_left_short), lastNursing.leftDurationMinutes, lastSide == BreastSide.LEFT, Modifier.weight(1f))
                SideChip(stringResource(R.string.side_right_short), lastNursing.rightDurationMinutes, lastSide == BreastSide.RIGHT, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LastDiaperCard(lastDiaper: DiaperEvent?, onClick: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val diaper = lastDiaper
    LastEntryCard(label = stringResource(R.string.card_last_diaper), onClick = onClick, onRemove = onRemove, modifier = modifier) {
        if (diaper == null) {
            Text(stringResource(R.string.none_yet), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            return@LastEntryCard
        }
        Text(dayAndTime(diaper.timeMillis, diaper.timeFormatted), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        val now = rememberMinuteClock()
        Text(
            sinceLabel(now - diaper.timeMillis),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = PrimaryColor
        )
        Text(
            diaperTypeLabel(diaper.type),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        diaper.caregiver?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = OutlineColor)
        }
    }
}

/** One breast's minutes; [isLast] highlights the side the feed ended on. */
@Composable
private fun SideChip(label: String, minutes: Int, isLast: Boolean, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (isLast) PrimaryColor else SurfaceContainerLow)
            .padding(vertical = 6.dp)
    ) {
        Text(
            text = stringResource(R.string.today_side_minutes, label, minutes),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (isLast) OnPrimary else MaterialTheme.colorScheme.onSurface
        )
        if (isLast) {
            Text(stringResource(R.string.side_chip_last), style = MaterialTheme.typography.labelSmall, color = OnPrimary.copy(alpha = 0.85f))
        }
    }
}

@Composable
private fun QuickActionsRow(
    actions: List<QuickAction>,
    onAction: (QuickAction) -> Unit,
    onEdit: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (actions.isEmpty()) {
            Text(
                stringResource(R.string.today_add_quick_actions),
                style = MaterialTheme.typography.bodyMedium,
                color = OutlineColor,
                modifier = Modifier.weight(1f).clickable(onClick = onEdit).padding(start = 8.dp)
            )
        }
        actions.forEach { action ->
            val tile = Modifier.weight(1f)
            when (action) {
                QuickAction.FEEDING -> QuickActionTile(action.label, ItemColors.soft(TrackedItem.BREASTFEEDING), { onAction(action) }, tile) {
                    Text("🍼", fontSize = 20.sp)
                }

                QuickAction.DIAPER -> QuickActionTile(action.label, ItemColors.soft(TrackedItem.DIAPERS), { onAction(action) }, tile) {
                    Icon(Icons.Default.WaterDrop, contentDescription = null, tint = ItemColors.tint(TrackedItem.DIAPERS), modifier = Modifier.size(22.dp))
                }

                QuickAction.PUMP -> QuickActionTile(action.label, ItemColors.soft(TrackedItem.PUMPING), { onAction(action) }, tile) {
                    Icon(Icons.Default.WaterDrop, contentDescription = null, tint = ItemColors.tint(TrackedItem.PUMPING), modifier = Modifier.size(22.dp))
                }

                QuickAction.TUMMY_TIME -> QuickActionTile(action.label, ItemColors.soft(TrackedItem.TUMMY_TIME), { onAction(action) }, tile) {
                    Icon(Icons.Default.FitnessCenter, contentDescription = null, tint = ItemColors.tint(TrackedItem.TUMMY_TIME), modifier = Modifier.size(22.dp))
                }

                QuickAction.SLEEP -> QuickActionTile(action.label, ItemColors.soft(TrackedItem.SLEEP), { onAction(action) }, tile) {
                    Icon(Icons.Default.Bedtime, contentDescription = null, tint = ItemColors.tint(TrackedItem.SLEEP), modifier = Modifier.size(22.dp))
                }

                QuickAction.BATH -> QuickActionTile(action.label, ItemColors.soft(TrackedItem.BATH), { onAction(action) }, tile) {
                    Icon(Icons.Default.Bathtub, contentDescription = null, tint = ItemColors.tint(TrackedItem.BATH), modifier = Modifier.size(22.dp))
                }
            }
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.today_edit_quick_actions), tint = OutlineColor, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun QuickActionTile(
    label: String,
    background: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(background),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** "Today", "Yesterday" or "3 days ago", counted in calendar days. */
private fun bathAgo(millis: Long): String {
    fun midnight(t: Long) = java.util.Calendar.getInstance().apply {
        timeInMillis = t
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    // Rounded, so a daylight-saving day still counts as one day
    val days = Math.round((midnight(System.currentTimeMillis()) - midnight(millis)) / 86_400_000.0).toInt()
    return when {
        days <= 0 -> AppText.get(R.string.today)
        days == 1 -> AppText.get(R.string.yesterday)
        else -> AppText.plural(R.plurals.days_ago, days, days)
    }
}

@Composable
private fun TodaySummaryGrid(
    recentFeedings: List<FeedingEvent>,
    recentDiapers: List<DiaperEvent>,
    recentTummyTimes: List<TummyTimeEvent>,
    recentPumpings: List<PumpingEvent>,
    recentSleeps: List<SleepEvent>,
    sleepTimer: SleepTimerState,
    milkStash: Int?,
    onMilkStashClick: () -> Unit,
    onOpenHistory: (HistoryFilter) -> Unit,
    hidden: Set<TrackedItem>,
    hiddenTiles: Set<TodayTile> = emptySet(),
    awakeSince: Long? = null,
    wakeWindows: List<WakeWindow> = emptyList(),
    onWakeWindowClick: () -> Unit = {},
    lastBathMillis: Long? = null,
    onBathClick: () -> Unit = {}
) {
    // Sleep within today (last night's sleep counts from midnight), plus a sleep running now
    val todayStart = remember(recentSleeps) {
        java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val now = System.currentTimeMillis()
    val sleptMinutes = (
        recentSleeps.sumOf { (it.endMillis - maxOf(it.timeMillis, todayStart)).coerceAtLeast(0) } +
            if (sleepTimer.isRunning) now - maxOf(sleepTimer.startMillis, todayStart) else 0L
        ) / 60_000L
    val naps = recentSleeps.count { it.isNap && it.timeMillis >= todayStart }
    val wet = recentDiapers.count { it.type == DiaperType.WET || it.type == DiaperType.BOTH }
    val dirty = recentDiapers.count { it.type == DiaperType.DIRTY || it.type == DiaperType.BOTH }
    val tummyMinutes = recentTummyTimes.sumOf { it.durationMinutes }
    val nursingMinutes = recentFeedings.filter { it.type == FeedType.NURSING }.sumOf { it.totalDurationMinutes }
    val bottleMl = recentFeedings.sumOf { it.amountMl ?: 0 }

    // Only the tracked items, two per row
    val tiles = buildList<@Composable (Modifier) -> Unit> {
        if ((TrackedItem.BREASTFEEDING !in hidden || TrackedItem.BOTTLE !in hidden) && TodayTile.FEEDS !in hiddenTiles) {
            add { m ->
                SummaryTile(
                    label = stringResource(R.string.tile_feeds),
                    value = "${recentFeedings.size}",
                    detail = when {
                        TrackedItem.BREASTFEEDING in hidden -> stringResource(R.string.amount_ml, bottleMl)
                        TrackedItem.BOTTLE in hidden || bottleMl == 0 -> stringResource(R.string.history_min_nursing, nursingMinutes)
                        else -> stringResource(R.string.today_nursing_and_bottle, nursingMinutes, bottleMl)
                    },
                    icon = null,
                    emoji = "🍼",
                    tint = ItemColors.tint(TrackedItem.BREASTFEEDING),
                    background = ItemColors.soft(TrackedItem.BREASTFEEDING),
                    onClick = { onOpenHistory(HistoryFilter.FEEDS) },
                    modifier = m
                )
            }
        }
        if (TrackedItem.DIAPERS !in hidden && TodayTile.DIAPERS !in hiddenTiles) {
            add { m ->
                SummaryTile(
                    label = stringResource(R.string.tile_diapers),
                    value = "${recentDiapers.size}",
                    detail = stringResource(R.string.widget_wet_dirty, wet, dirty),
                    icon = Icons.Default.WaterDrop,
                    tint = ItemColors.tint(TrackedItem.DIAPERS),
                    background = ItemColors.soft(TrackedItem.DIAPERS),
                    onClick = { onOpenHistory(HistoryFilter.DIAPERS) },
                    modifier = m
                )
            }
        }
        if (TrackedItem.SLEEP !in hidden && TodayTile.SLEEP !in hiddenTiles) {
            add { m ->
                SummaryTile(
                    label = stringResource(R.string.tile_sleep),
                    value = formatMinutes(sleptMinutes.toInt()),
                    detail = when {
                        sleepTimer.isRunning -> stringResource(R.string.today_sleeping_now)
                        else -> pluralStringResource(R.plurals.naps_count, naps, naps)
                    },
                    icon = Icons.Default.Bedtime,
                    tint = ItemColors.tint(TrackedItem.SLEEP),
                    background = ItemColors.soft(TrackedItem.SLEEP),
                    onClick = { onOpenHistory(HistoryFilter.SLEEP) },
                    modifier = m
                )
            }
        }
        if (TrackedItem.SLEEP !in hidden && TodayTile.WAKE_WINDOW !in hiddenTiles) {
            add { m ->
                val minuteNow = rememberMinuteClock()
                SummaryTile(
                    label = stringResource(R.string.tile_wake_window),
                    value = when {
                        sleepTimer.isRunning -> stringResource(R.string.today_asleep)
                        awakeSince != null -> formatMinutes(((minuteNow - awakeSince) / 60_000L).toInt().coerceAtLeast(0))
                        else -> "–"
                    },
                    detail = if (wakeWindows.isEmpty()) {
                        stringResource(R.string.today_none_ended)
                    } else {
                        stringResource(R.string.today_wake_avg, formatMinutes(wakeWindows.averageMinutes()), wakeWindows.size)
                    },
                    icon = Icons.Default.WbSunny,
                    tint = ItemColors.tint(TrackedItem.SLEEP),
                    background = ItemColors.soft(TrackedItem.SLEEP),
                    onClick = onWakeWindowClick,
                    modifier = m
                )
            }
        }
        if (TrackedItem.PUMPING !in hidden && TodayTile.PUMPED !in hiddenTiles) {
            add { m ->
                SummaryTile(
                    label = stringResource(R.string.tile_pumped),
                    value = stringResource(R.string.amount_ml, recentPumpings.sumOf { it.amountMl }),
                    detail = sessions(recentPumpings.size),
                    icon = Icons.Default.WaterDrop,
                    tint = ItemColors.tint(TrackedItem.PUMPING),
                    background = ItemColors.soft(TrackedItem.PUMPING),
                    onClick = { onOpenHistory(HistoryFilter.PUMPING) },
                    modifier = m
                )
            }
        }
        if (TrackedItem.MILK_STASH !in hidden && TodayTile.MILK_STASH !in hiddenTiles) {
            add { m ->
                SummaryTile(
                    label = stringResource(R.string.tile_milk_stash),
                    value = milkStash?.let { stringResource(R.string.amount_ml, it) } ?: stringResource(R.string.today_set),
                    detail = stringResource(if (milkStash == null) R.string.today_tap_to_enter else R.string.today_tap_to_correct),
                    icon = Icons.Default.Kitchen,
                    tint = ItemColors.tint(TrackedItem.MILK_STASH),
                    background = ItemColors.soft(TrackedItem.MILK_STASH),
                    onClick = onMilkStashClick,
                    modifier = m
                )
            }
        }
        if (TrackedItem.TUMMY_TIME !in hidden && TodayTile.TUMMY_TIME !in hiddenTiles) {
            add { m ->
                SummaryTile(
                    label = stringResource(R.string.tile_tummy_time),
                    value = stringResource(R.string.amount_minutes, tummyMinutes),
                    detail = sessions(recentTummyTimes.size),
                    icon = Icons.Default.FitnessCenter,
                    tint = ItemColors.tint(TrackedItem.TUMMY_TIME),
                    background = ItemColors.soft(TrackedItem.TUMMY_TIME),
                    onClick = { onOpenHistory(HistoryFilter.TUMMY) },
                    modifier = m
                )
            }
        }
        if (TrackedItem.BATH !in hidden && TodayTile.BATH !in hiddenTiles) {
            add { m ->
                SummaryTile(
                    label = stringResource(R.string.tile_last_bath),
                    value = lastBathMillis?.let(::bathAgo) ?: stringResource(R.string.none_yet),
                    detail = lastBathMillis?.let { dayAndTime(it, TimeFormat.clock(it)) } ?: stringResource(R.string.today_tap_to_log_bath),
                    icon = Icons.Default.Bathtub,
                    tint = ItemColors.tint(TrackedItem.BATH),
                    background = ItemColors.soft(TrackedItem.BATH),
                    onClick = onBathClick,
                    modifier = m
                )
            }
        }
    }
    if (tiles.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.today),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp)
        )
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { tile -> tile(Modifier.weight(1f)) }
                if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

/** "Awake for 1h 20m" and "Next feed around 14:30 · in 25m", updated every minute. */
private fun sessions(count: Int) = AppText.plural(R.plurals.sessions_count, count, count)

@Composable
private fun NextUpCard(awakeSince: Long?, nextFeedAt: Long?, onSleepClick: () -> Unit, modelNext: List<Pair<String, Long>> = emptyList()) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            now = System.currentTimeMillis()
        }
    }
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if ((awakeSince != null && awakeSince <= now) || nextFeedAt != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (awakeSince != null && awakeSince <= now) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(onClick = onSleepClick)
                        ) {
                            Text(stringResource(R.string.wake_part_awake_for), style = MaterialTheme.typography.labelLarge, color = OutlineColor)
                            Text(
                                formatMinutes(((now - awakeSince) / 60_000L).toInt()),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(stringResource(R.string.cards_since, TimeFormat.clock(awakeSince)), style = MaterialTheme.typography.bodySmall, color = OutlineColor)
                        }
                    }
                    if (nextFeedAt != null) NextAt(stringResource(R.string.rhythm_feed_gap), nextFeedAt, now, Modifier.weight(1f))
                }
            }
            // The other predictions of the models picked in Trends → Models
            modelNext.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    pair.forEach { (label, at) -> NextAt(label, at, now, Modifier.weight(1f)) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** "Next feed / ~14:30 / in 25m" (or "due 5m ago"). */
@Composable
private fun NextAt(label: String, at: Long, now: Long, modifier: Modifier = Modifier) {
    val minutesLeft = ((at - now) / 60_000L).toInt()
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = OutlineColor)
        Text(
            "~${TimeFormat.clock(at)}",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = if (minutesLeft < 0) PrimaryColor else MaterialTheme.colorScheme.onSurface
        )
        Text(
            if (minutesLeft >= 0) stringResource(R.string.today_in, formatMinutes(minutesLeft)) else stringResource(R.string.today_due_ago, formatMinutes(-minutesLeft)),
            style = MaterialTheme.typography.bodySmall,
            color = if (minutesLeft < 0) PrimaryColor else OutlineColor
        )
    }
}

/** One-tap notes from the server's tags ("Vitamin", "Bath"), each marked with today's time once logged. */
@Composable
private fun QuickNotesCard(
    tags: List<NoteTag>,
    todaysNotes: List<com.example.data.model.NoteEvent>,
    onTag: (NoteTag) -> Unit,
    onAddNote: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.item_quick_notes),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            tags.take(8).forEach { tag ->
                val last = todaysNotes.filter { tag.name in it.tags }.maxByOrNull { it.timeMillis }
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (last != null) PrimaryFixed else SurfaceContainerLow,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { onTag(tag) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (last != null) {
                            Icon(Icons.Default.Check, contentDescription = stringResource(R.string.today_logged_today), tint = PrimaryColor, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            tag.name.replaceFirstChar(Char::uppercase),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        if (last != null) {
                            Text(
                                " · ${last.timeFormatted}",
                                style = MaterialTheme.typography.labelMedium,
                                color = OutlineColor
                            )
                        }
                    }
                }
            }
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = SurfaceContainerLow,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(onClick = onAddNote)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = PrimaryColor, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.note), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SummaryTile(
    label: String,
    value: String,
    detail: String,
    icon: ImageVector?,
    tint: Color,
    background: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emoji: String? = null
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(background),
                    contentAlignment = Alignment.Center
                ) {
                    if (icon != null) {
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                    } else if (emoji != null) {
                        Text(emoji, fontSize = 14.sp)
                    }
                }
                Text(label, style = MaterialTheme.typography.labelLarge, color = OutlineColor)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = OutlineColor)
        }
    }
}

/** What happened last, so caregivers don't have to ask each other. */
@Composable
private fun LastPumpCard(recentPumpings: List<PumpingEvent>) {
    val pump = recentPumpings.maxByOrNull { it.timeMillis }
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainerLowest),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LastEntryRow(
                label = stringResource(R.string.today_last_pump),
                value = pump?.let { listOfNotNull(it.timeFormatted, stringResource(R.string.amount_ml, it.amountMl), it.caregiver).joinToString(" · ") } ?: stringResource(R.string.today_none_today)
            )
        }
    }
}

@Composable
private fun LastEntryRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = OutlineColor)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatMinutes(minutes: Int): String = if (minutes >= 60) AppText.get(R.string.duration_hours_minutes_short, minutes / 60, minutes % 60) else AppText.get(R.string.duration_minutes, minutes)

/** Average time between feeds this week, how it compares with last week, and a 7-day line. */
@Composable
private fun FeedIntervalCard(stats: FeedIntervalStats) {
    val thisWeek = stats.thisWeekAverageMinutes ?: return
    val diff = stats.lastWeekAverageMinutes?.let { thisWeek - it }
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainerLowest),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.today_time_between_feeds), style = MaterialTheme.typography.bodyMedium, color = OutlineColor)
                Text(formatMinutes(thisWeek), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    text = when {
                        diff == null || kotlin.math.abs(diff) < 5 -> stringResource(R.string.today_same_as_last_week)
                        diff > 0 -> stringResource(R.string.today_longer_than_last_week, formatMinutes(diff))
                        else -> stringResource(R.string.today_shorter_than_last_week, formatMinutes(-diff))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IntervalSparkline(stats.dailyAverageMinutes, Modifier.width(110.dp).height(44.dp))
        }
    }
}

@Composable
private fun IntervalSparkline(values: List<Int?>, modifier: Modifier) {
    val lineColor = PrimaryColor
    val dotColor = PrimaryColor
    Canvas(modifier = modifier) {
        val points = values.mapIndexedNotNull { i, v -> v?.let { i to it } }
        if (points.size < 2) return@Canvas
        val min = points.minOf { it.second }.toFloat()
        val max = points.maxOf { it.second }.toFloat()
        val range = (max - min).coerceAtLeast(30f) // flat-ish weeks shouldn't look dramatic
        val stepX = size.width / (values.size - 1).coerceAtLeast(1)
        fun y(v: Int) = size.height - ((v - min) / range) * size.height * 0.8f - size.height * 0.1f
        val path = Path()
        points.forEachIndexed { index, (i, v) ->
            if (index == 0) path.moveTo(i * stepX, y(v)) else path.lineTo(i * stepX, y(v))
        }
        drawPath(path, color = lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        val (lastI, lastV) = points.last()
        drawCircle(color = dotColor, radius = 3.dp.toPx(), center = Offset(lastI * stepX, y(lastV)))
    }
}
