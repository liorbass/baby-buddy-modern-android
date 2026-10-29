package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.snapshotFlow
import com.example.R
import com.example.data.api.BabyBuddyApiClient
import com.example.data.api.BabyBuddyApiService
import com.example.data.api.BabyBuddyChangeDto
import com.example.data.api.BabyBuddyFeedingDto
import com.example.data.api.BabyBuddyGrowthDto
import com.example.data.api.BabyBuddyNoteDto
import com.example.data.api.BabyBuddyPumpingDto
import com.example.data.api.BabyBuddySleepDto
import com.example.data.api.BabyBuddyTemperatureDto
import com.example.data.api.BabyBuddyTummyTimeDto
import com.example.data.api.PaginatedResponse
import com.example.data.api.UntrustedCertificateException
import com.example.data.api.findInChain
import com.example.data.ml.RhythmEvents
import com.example.data.ml.RhythmPrediction
import com.example.data.ml.RhythmTarget
import com.example.data.ml.SavedRhythmModel
import com.example.data.ml.predictRhythms
import com.example.data.ml.trainRhythmModels
import com.example.data.model.AppText
import com.example.data.model.BATH_NOTE_TEXT
import com.example.data.model.BATH_TAG
import com.example.data.model.BabyProfile
import com.example.data.model.BabySex
import com.example.data.model.BreastSide
import com.example.data.model.DashboardWidgetConfig
import com.example.data.model.DashboardWidgetType
import com.example.data.model.DayStats
import com.example.data.model.DiaperEvent
import com.example.data.model.DiaperMark
import com.example.data.model.DiaperType
import com.example.data.model.FeedIntervalStats
import com.example.data.model.FeedMark
import com.example.data.model.FeedType
import com.example.data.model.FeedingEvent
import com.example.data.model.GrowthEvent
import com.example.data.model.GrowthKind
import com.example.data.model.HandoffSummary
import com.example.data.model.HistoryDay
import com.example.data.model.LiveTimerState
import com.example.data.model.MILK_STASH_TAG
import com.example.data.model.NoteEvent
import com.example.data.model.NoteTag
import com.example.data.model.PumpingEvent
import com.example.data.model.QuickAction
import com.example.data.model.ReminderSettings
import com.example.data.model.ServerConnectionConfig
import com.example.data.model.SleepEvent
import com.example.data.model.SleepMark
import com.example.data.model.SleepTimerState
import com.example.data.model.StartSide
import com.example.data.model.TemperatureEvent
import com.example.data.model.ThemeMode
import com.example.data.model.TimeFormat
import com.example.data.model.TodayTile
import com.example.data.model.TrackedItem
import com.example.data.model.TummyTimeEvent
import com.example.data.model.TummyTimeGoal
import com.example.data.model.WakeWindowPart
import com.example.data.model.isNapTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class BabyBuddyRepository {

    private val _babyProfile = MutableStateFlow(BabyProfile(name = "", shortName = "", ageFormatted = "", avatarUrl = ""))
    val babyProfile: StateFlow<BabyProfile> = _babyProfile.asStateFlow()

    // Set on a successful connect; writes go to the server only while these are present
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var remoteApi: BabyBuddyApiService? = null
    private var remoteAuthHeader: String = ""
    private var remoteChildId: Int? = null

    // Local entry id -> Baby Buddy record id, filled as creates succeed
    private val serverIds = ConcurrentHashMap<Long, Long>()
    private val localIdSeq = AtomicLong(System.currentTimeMillis())
    fun nextLocalId(): Long = localIdSeq.incrementAndGet()

    /** Writes the server hasn't accepted yet, oldest first. Guarded by itself; mirrored to disk on every change. */
    private val pendingWrites = mutableListOf<QueuedWrite>()

    @Volatile private var pendingSnapshot: List<QueuedWrite> = emptyList()
    private var flushJob: Job? = null
    private var store: OfflineStore? = null

    /** Number of entries waiting to be saved to the server. */
    private val _pendingWriteCount = MutableStateFlow(0)
    val pendingWriteCount: StateFlow<Int> = _pendingWriteCount.asStateFlow()

    /** Errors from background writes to the server, for the UI to surface. */
    private val _syncErrors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val syncErrors: SharedFlow<String> = _syncErrors.asSharedFlow()

    private val _connectionConfig = MutableStateFlow(ServerConnectionConfig())
    val connectionConfig: StateFlow<ServerConnectionConfig> = _connectionConfig.asStateFlow()

    private val _liveTimerState = MutableStateFlow(LiveTimerState())
    val liveTimerState: StateFlow<LiveTimerState> = _liveTimerState.asStateFlow()

    private val _feedings = MutableStateFlow<List<FeedingEvent>>(emptyList())
    val feedings: StateFlow<List<FeedingEvent>> = _feedings.asStateFlow()

    // The latest breastfeed from the server, so the last side is known even before today's first feed
    private val _lastBreastfeedRemote = MutableStateFlow<FeedingEvent?>(null)

    /** The most recent breastfeed (today's if any, else the latest on the server), for "last side". */
    val lastBreastfeed: StateFlow<FeedingEvent?> by lazy {
        combine(_feedings, _lastBreastfeedRemote) { today, remote ->
            today.filter { it.type == FeedType.NURSING }.maxByOrNull { it.timeMillis }
                ?.takeIf { remote == null || it.timeMillis >= remote.timeMillis } ?: remote
        }.stateIn(syncScope, SharingStarted.Eagerly, null)
    }

    // The latest diaper change on the server, so "last diaper" still shows before today's first change
    private val _lastDiaperRemote = MutableStateFlow<DiaperEvent?>(null)

    /** The most recent diaper change (today's if any, else the latest on the server). */
    val lastDiaper: StateFlow<DiaperEvent?> by lazy {
        combine(_diapers, _lastDiaperRemote) { today, remote ->
            today.maxByOrNull { it.timeMillis }?.takeIf { remote == null || it.timeMillis >= remote.timeMillis } ?: remote
        }.stateIn(syncScope, SharingStarted.Eagerly, null)
    }

    /** Today's sleeps (including one that started last night and ended today). */
    // Newest feed of any kind and newest sleep end on the server (may be yesterday's), for reminders and "awake for"
    private val _recentFeedsRemote = MutableStateFlow<List<FeedingEvent>>(emptyList()) // newest first
    private val _lastSleepEndRemote = MutableStateFlow<Long?>(null)

    /** The most recent feed of any kind, breast or bottle (today's if any, else the latest on the server). */
    val lastFeed: StateFlow<FeedingEvent?> by lazy {
        combine(_feedings, _recentFeedsRemote) { today, recent ->
            val remote = recent.firstOrNull()
            today.maxByOrNull { it.timeMillis }?.takeIf { remote == null || it.timeMillis >= remote.timeMillis } ?: remote
        }.stateIn(syncScope, SharingStarted.Eagerly, null)
    }

    /**
     * While a feed is timed, when the feed before it (breast or bottle) started, from today's entries and the
     * server's latest; null without a running feed or an earlier one.
     */
    val previousFeedMillis: StateFlow<Long?> by lazy {
        combine(
            _feedings,
            _recentFeedsRemote,
            _liveTimerState.map { it.takeIf { s -> s.isRunning }?.let { s -> s.sessionStartMillis to s.feedingLocalId } }.distinctUntilChanged()
        ) { today, recent, feed ->
            val (start, currentId) = feed ?: return@combine null
            (today + recent).filter { it.id != currentId && it.timeMillis < start - 60_000L }.maxOfOrNull { it.timeMillis }
        }.stateIn(syncScope, SharingStarted.Eagerly, null)
    }

    /** When the most recent feed (breast or bottle) started. */
    val lastFeedMillis: StateFlow<Long?> by lazy {
        lastFeed.map { it?.timeMillis }.stateIn(syncScope, SharingStarted.Eagerly, null)
    }

    /** When the baby last woke up (end of the latest sleep), or null if unknown. */
    val lastSleepEndMillis: StateFlow<Long?> by lazy {
        combine(_sleeps, _lastSleepEndRemote) { today, remote ->
            listOfNotNull(today.maxOfOrNull { it.endMillis }, remote).maxOrNull()
        }.stateIn(syncScope, SharingStarted.Eagerly, null)
    }

    // Latest bath on the server (searched over the last few weeks); today's local ones are in _notes
    private val _lastBathRemote = MutableStateFlow<Long?>(null)

    /** When the most recent bath was (a note tagged BATH_TAG), or null if none was found. */
    val lastBathMillis: StateFlow<Long?> by lazy {
        combine(_notes, _lastBathRemote) { today, remote ->
            listOfNotNull(today.filter { BATH_TAG in it.tags }.maxOfOrNull { it.timeMillis }, remote).maxOrNull()
        }.stateIn(syncScope, SharingStarted.Eagerly, null)
    }

    private val _noteTags = MutableStateFlow<List<NoteTag>>(emptyList())

    /** The server's note tags, most recently used first, for one-tap notes on Today. */
    val noteTags: StateFlow<List<NoteTag>> = _noteTags.asStateFlow()

    private val _reminders = MutableStateFlow(ReminderSettings())
    val reminders: StateFlow<ReminderSettings> = _reminders.asStateFlow()

    fun setReminders(settings: ReminderSettings) {
        _reminders.value = settings
        prefs?.edit()
            ?.putInt(PREF_REMIND_FEED, settings.feedAfterMinutes)
            ?.putInt(PREF_REMIND_AWAKE, settings.awakeAfterMinutes)
            ?.putInt(PREF_REMIND_SLEEP, settings.sleepAfterMinutes)
            ?.putInt(PREF_REMIND_DIAPER, settings.diaperAfterMinutes)
            ?.putInt(PREF_REMIND_EARLY, settings.earlyMinutes)
            ?.apply()
    }

    private val _sleeps = MutableStateFlow<List<SleepEvent>>(emptyList())
    val sleeps: StateFlow<List<SleepEvent>> = _sleeps.asStateFlow()

    /** A sleep being timed on this phone; saved as a sleep entry when it ends. */
    private val _sleepTimer = MutableStateFlow(SleepTimerState())
    val sleepTimer: StateFlow<SleepTimerState> = _sleepTimer.asStateFlow()

    private val _diapers = MutableStateFlow<List<DiaperEvent>>(emptyList())
    val diapers: StateFlow<List<DiaperEvent>> = _diapers.asStateFlow()

    private val _tummyTimes = MutableStateFlow<List<TummyTimeEvent>>(emptyList())
    val tummyTimes: StateFlow<List<TummyTimeEvent>> = _tummyTimes.asStateFlow()

    private val _pumpings = MutableStateFlow<List<PumpingEvent>>(emptyList())
    val pumpings: StateFlow<List<PumpingEvent>> = _pumpings.asStateFlow()

    // Dashboard Widgets Customization
    private val _dashboardWidgets = MutableStateFlow<List<DashboardWidgetConfig>>(defaultDashboardWidgets())
    val dashboardWidgets: StateFlow<List<DashboardWidgetConfig>> = _dashboardWidgets.asStateFlow()

    // Only the goal lives here; progress comes from today's tummy-time entries
    private val _tummyTimeGoal = MutableStateFlow(TummyTimeGoal())
    val tummyTimeGoal: StateFlow<TummyTimeGoal> = _tummyTimeGoal.asStateFlow()

    private val _temperatures = MutableStateFlow<List<TemperatureEvent>>(emptyList())
    val temperatures: StateFlow<List<TemperatureEvent>> = _temperatures.asStateFlow()

    private val _notes = MutableStateFlow<List<NoteEvent>>(emptyList())
    val notes: StateFlow<List<NoteEvent>> = _notes.asStateFlow()

    // Every weight / length / head circumference measurement (any date), newest first
    private val _growth = MutableStateFlow<List<GrowthEvent>>(emptyList())
    val growth: StateFlow<List<GrowthEvent>> = _growth.asStateFlow()

    private val _babySex = MutableStateFlow<BabySex?>(null)

    /** Picks the WHO reference charts; null until chosen (Settings → You, or the Trends growth card). */
    val babySex: StateFlow<BabySex?> = _babySex.asStateFlow()

    fun setBabySex(sex: BabySex) {
        _babySex.value = sex
        prefs?.edit()?.putString(PREF_BABY_SEX, sex.name)?.apply()
    }

    // Settings (saved in prefs)
    private val _caregiverName = MutableStateFlow("")

    /** This device's caregiver; written as "Caregiver: Name" into new entries, like the server's other clients do. */
    val caregiverName: StateFlow<String> = _caregiverName.asStateFlow()
    private val _feedNotificationEnabled = MutableStateFlow(true)
    val feedNotificationEnabled: StateFlow<Boolean> = _feedNotificationEnabled.asStateFlow()

    fun setCaregiverName(name: String) {
        _caregiverName.value = name.trim()
        prefs?.edit()?.putString(PREF_CAREGIVER, name.trim())?.apply()
    }

    private val _use24h = MutableStateFlow(true)

    /** 24-hour clock (default) or 12-hour with AM/PM. */
    val use24h: StateFlow<Boolean> = _use24h.asStateFlow()

    // Settings → Appearance: the Models tab in Trends (rhythm models fitted on the phone)
    private val _modelsTab = MutableStateFlow(false)
    val modelsTab: StateFlow<Boolean> = _modelsTab.asStateFlow()

    fun setModelsTab(enabled: Boolean) {
        _modelsTab.value = enabled
        prefs?.edit()?.putBoolean(PREF_MODELS_TAB, enabled)?.apply()
    }

    fun setUse24h(enabled: Boolean) {
        _use24h.value = enabled
        TimeFormat.use24h = enabled
        prefs?.edit()?.putBoolean(PREF_USE_24H, enabled)?.apply()
        reformatTimes()
    }

    /** Rewrites every shown time in the current clock format. */
    private fun reformatTimes() {
        _feedings.update { list -> list.map { it.copy(timeFormatted = formatClock(it.timeMillis)) } }
        _diapers.update { list -> list.map { it.copy(timeFormatted = formatClock(it.timeMillis)) } }
        _tummyTimes.update { list -> list.map { it.copy(timeFormatted = formatClock(it.timeMillis)) } }
        _pumpings.update { list -> list.map { it.copy(timeFormatted = formatClock(it.timeMillis)) } }
        _temperatures.update { list -> list.map { it.copy(timeFormatted = formatClock(it.timeMillis)) } }
        _notes.update { list -> list.map { it.copy(timeFormatted = formatClock(it.timeMillis)) } }
        _sleeps.update { list -> list.map { it.copy(timeFormatted = formatClock(it.timeMillis)) } }
        _lastBreastfeedRemote.update { it?.copy(timeFormatted = formatClock(it.timeMillis)) }
        _recentFeedsRemote.update { list -> list.map { it.copy(timeFormatted = formatClock(it.timeMillis)) } }
        _lastDiaperRemote.update { it?.copy(timeFormatted = formatClock(it.timeMillis)) }
        _liveTimerState.update { t ->
            if (t.isRunning && t.sessionStartMillis > 0) t.copy(sessionStartedTime = formatClock(t.sessionStartMillis)) else t
        }
    }

    private val _hiddenItems = MutableStateFlow<Set<TrackedItem>>(emptySet())

    /** Items this phone doesn't track; the UI leaves them out everywhere. */
    val hiddenItems: StateFlow<Set<TrackedItem>> = _hiddenItems.asStateFlow()

    fun setItemTracked(item: TrackedItem, tracked: Boolean) {
        val hidden = if (tracked) _hiddenItems.value - item else _hiddenItems.value + item
        _hiddenItems.value = hidden
        prefs?.edit()?.putString(PREF_HIDDEN_ITEMS, hidden.joinToString(",") { it.name })?.apply()
    }

    // Customize Today: summary tiles and Wake windows card parts turned off (saved as what's off, so new ones show)
    private val _hiddenTodayTiles = MutableStateFlow<Set<TodayTile>>(emptySet())
    val hiddenTodayTiles: StateFlow<Set<TodayTile>> = _hiddenTodayTiles.asStateFlow()

    fun setTodayTileShown(tile: TodayTile, shown: Boolean) {
        val hidden = if (shown) _hiddenTodayTiles.value - tile else _hiddenTodayTiles.value + tile
        _hiddenTodayTiles.value = hidden
        prefs?.edit()?.putString(PREF_HIDDEN_SUMMARY_TILES, hidden.joinToString(",") { it.name })?.apply()
    }

    private val _hiddenWakeWindowParts = MutableStateFlow<Set<WakeWindowPart>>(emptySet())
    val hiddenWakeWindowParts: StateFlow<Set<WakeWindowPart>> = _hiddenWakeWindowParts.asStateFlow()

    fun setWakeWindowPartShown(part: WakeWindowPart, shown: Boolean) {
        val hidden = if (shown) _hiddenWakeWindowParts.value - part else _hiddenWakeWindowParts.value + part
        _hiddenWakeWindowParts.value = hidden
        prefs?.edit()?.putString(PREF_HIDDEN_WAKE_PARTS, hidden.joinToString(",") { it.name })?.apply()
    }

    private val _startSide = MutableStateFlow(StartSide.OPPOSITE)

    /** Which side one-tap feeds start on (timer widget, Quick Settings tile); Settings → You. */
    val startSide: StateFlow<StartSide> = _startSide.asStateFlow()

    fun setStartSide(side: StartSide) {
        _startSide.value = side
        prefs?.edit()?.putString(PREF_START_SIDE, side.name)?.apply()
    }

    /** The side a one-tap feed starts on now, per [startSide] and the last breastfeed. */
    fun nextStartSide(): BreastSide = _startSide.value.after(lastBreastfeed.value?.lastSide)

    private val _themeMode = MutableStateFlow(ThemeMode.BY_TIME)

    /** Light/dark choice for this phone. */
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs?.edit()?.putString(PREF_THEME_MODE, mode.name)?.apply()
    }

    private val _quickActions = MutableStateFlow(QuickAction.DEFAULT)

    /** Today's shortcut buttons, in the order shown. */
    val quickActions: StateFlow<List<QuickAction>> = _quickActions.asStateFlow()

    fun setQuickActions(actions: List<QuickAction>) {
        _quickActions.value = actions.distinct()
        prefs?.edit()?.putString(PREF_QUICK_ACTIONS, actions.distinct().joinToString(",") { it.name })?.apply()
    }

    fun setFeedNotificationEnabled(enabled: Boolean) {
        _feedNotificationEnabled.value = enabled
        prefs?.edit()?.putBoolean(PREF_FEED_NOTIFICATION, enabled)?.apply()
    }

    private val _nextUpNotification = MutableStateFlow(false)

    /** Silent notification with what's next (feed, diaper, sleep) while no timer runs (Settings → Notifications). */
    val nextUpNotification: StateFlow<Boolean> = _nextUpNotification.asStateFlow()

    fun setNextUpNotification(enabled: Boolean) {
        _nextUpNotification.value = enabled
        prefs?.edit()?.putBoolean(PREF_NEXT_UP_NOTIFICATION, enabled)?.apply()
    }

    private val _handoff = MutableStateFlow<HandoffSummary?>(null)

    /** Entries other caregivers logged while this phone was away from the app, until dismissed. */
    val handoff: StateFlow<HandoffSummary?> = _handoff.asStateFlow()

    /** The app went to the background: entries others log from now on make the next [checkHandoff]'s summary. */
    fun markAway() {
        prefs?.edit()?.putLong(PREF_AWAY_SINCE, System.currentTimeMillis())?.apply()
    }

    fun dismissHandoff() {
        _handoff.value = null
    }

    /**
     * Back in the app (after a refresh): collects the feeds, changes and sleeps someone else logged since [markAway]
     * (at most the last day). With a caregiver name set, everything not carrying it counts; without one, only
     * entries that name someone. Keeps the previous summary when nothing new came in.
     */
    suspend fun checkHandoff() {
        val p = prefs ?: return
        val since = p.getLong(PREF_AWAY_SINCE, 0L)
        if (since <= 0L) return
        p.edit().remove(PREF_AWAY_SINCE).apply()
        if (!_connectionConfig.value.isConnected) return
        val from = maxOf(since, System.currentTimeMillis() - 24 * 60 * 60_000L)
        val (feeds, changes, sleeps) = if (from >= startOfTodayMillis()) {
            Triple(_feedings.value, _diapers.value, _sleeps.value)
        } else {
            withContext(Dispatchers.IO) { runCatching { fetchEvents(from) }.getOrNull() } ?: Triple(_feedings.value, _diapers.value, _sleeps.value)
        }
        val me = myName()
        fun byOther(caregiver: String?) = if (me != null) !caregiver.equals(me, ignoreCase = true) else !caregiver.isNullOrBlank()
        val hidden = _hiddenItems.value
        val summary = HandoffSummary(
            sinceMillis = from,
            feedings = feeds.filter { f ->
                f.timeMillis >= from && byOther(f.caregiver) &&
                    (if (f.type == FeedType.NURSING) TrackedItem.BREASTFEEDING else TrackedItem.BOTTLE) !in hidden
            }.sortedBy { it.timeMillis },
            diapers = changes.filter { it.timeMillis >= from && byOther(it.caregiver) && TrackedItem.DIAPERS !in hidden }.sortedBy { it.timeMillis },
            sleeps = sleeps.filter { it.id != -1L && it.endMillis >= from && byOther(it.caregiver) && TrackedItem.SLEEP !in hidden }.sortedBy { it.timeMillis }
        )
        if (!summary.isEmpty) _handoff.value = summary
    }

    private fun myName(): String? = _caregiverName.value.ifBlank { null }

    // Offline test toggle to view disconnected state exactly like the screenshot
    private val _isServerDisconnectedView = MutableStateFlow(false)
    val isServerDisconnectedView: StateFlow<Boolean> = _isServerDisconnectedView.asStateFlow()

    fun toggleServerDisconnectedView(disconnected: Boolean) {
        _isServerDisconnectedView.value = disconnected
        _connectionConfig.update { it.copy(isConnected = !disconnected) }
    }

    private var allDemoFeedings: List<FeedingEvent> = emptyList()
    private var allDemoDiapers: List<DiaperEvent> = emptyList()
    private var allDemoSleeps: List<SleepEvent> = emptyList()
    private var allDemoPumpings: List<PumpingEvent> = emptyList()
    private var allDemoTummyTimes: List<TummyTimeEvent> = emptyList()
    private var allDemoNotes: List<NoteEvent> = emptyList()

    fun enterDemoMode() {
        clearRemote()
        prefs?.edit()
            ?.remove(PREF_URL)?.remove(PREF_TOKEN_ENC)?.remove(PREF_CF_ID)
            ?.remove(PREF_CF_SECRET_ENC)?.remove(PREF_CERT_PIN)?.remove(PREF_CHILD_ID)
            ?.remove(PREF_CHILD_NAME)?.remove(PREF_BIRTH_DATE)
            ?.putBoolean(PREF_DEMO_MODE, true)?.apply()

        _caregiverName.value = "Sarah"
        prefs?.edit()?.putString(PREF_CAREGIVER, "Sarah")?.apply()

        _isServerDisconnectedView.value = false
        setThemeMode(ThemeMode.LIGHT)
        _connectionConfig.value = ServerConnectionConfig(
            serverUrl = "demo.babybuddy.local",
            apiToken = "demo-token",
            isConnected = true,
            isDemoMode = true,
            detectedChildName = "Sam",
            lastSyncFormatted = AppText.get(R.string.demo_mode)
        )
        _babyProfile.value = BabyProfile(
            name = "Sam",
            shortName = "Sam",
            birthDate = "2026-01-10",
            ageFormatted = AppText.get(R.string.age_months_days, 9, 3)
        )
        loadDemoData()
        // Show every feature: growth charts, reminders and the rhythm models (fitted on the demo data and in use)
        if (_babySex.value == null) setBabySex(BabySex.BOY)
        if (!_reminders.value.anyOn) setReminders(ReminderSettings(feedAfterMinutes = 180, awakeAfterMinutes = 120, sleepAfterMinutes = 150, diaperAfterMinutes = 180, earlyMinutes = 15))
        setModelsTab(true)
        syncScope.launch(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            val from = now - 60 * 86_400_000L
            val events = RhythmEvents(
                allDemoFeedings.filter { it.timeMillis >= from },
                allDemoDiapers.filter { it.timeMillis >= from },
                allDemoSleeps.filter { it.timeMillis >= from },
                birthMillis = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(_babyProfile.value.birthDate)!!.time }.getOrNull() ?: from
            )
            val r = _reminders.value
            val defaults = mapOf(
                RhythmTarget.FEED_GAP to r.feedAfterMinutes,
                RhythmTarget.DIAPER_GAP to r.diaperAfterMinutes,
                RhythmTarget.SLEEP_LENGTH to r.sleepAfterMinutes,
                RhythmTarget.AWAKE_WINDOW to r.awakeAfterMinutes
            )
            trainRhythmModels(events, defaults).forEach { m ->
                val result = m.result ?: return@forEach
                setUsedModel(
                    m.target,
                    SavedRhythmModel(m.target, result.best.family, result.best.settings, result.best.mae, result.model, m.ageOriginMillis ?: from, now, m.inputs, result.best.residuals)
                )
            }
        }
    }

    private fun loadDemoData() {
        val today = startOfTodayMillis()
        val cal = Calendar.getInstance()

        fun clockMs(daysAgo: Int, hour: Int, minute: Int): Long {
            cal.timeInMillis = today
            cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
            cal.set(Calendar.HOUR_OF_DAY, hour)
            cal.set(Calendar.MINUTE, minute)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        // Simulated up to now, so "last feed", "awake for" and the charts read naturally at any time of day
        val now = System.currentTimeMillis()
        val birth = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(_babyProfile.value.birthDate)!!.time }.getOrDefault(today - 270 * 86_400_000L)
        val demo = generateDemoHistory(now = now, birthMillis = birth, clock = ::formatClock)
        allDemoFeedings = demo.feeds
        allDemoDiapers = demo.diapers
        allDemoSleeps = demo.sleeps
        allDemoPumpings = demo.pumpings
        allDemoTummyTimes = demo.tummyTimes
        allDemoNotes = demo.notes
        _growth.value = demo.growth
        _noteTags.value = listOf(NoteTag("Vitamin D"), NoteTag("Medicine"), NoteTag("Teething"))

        _feedings.value = allDemoFeedings.filter { it.timeMillis >= today }
        _diapers.value = allDemoDiapers.filter { it.timeMillis >= today }
        _sleeps.value = allDemoSleeps.filter { it.endMillis >= today }
        _pumpings.value = allDemoPumpings.filter { it.timeMillis >= today }
        _tummyTimes.value = allDemoTummyTimes.filter { it.timeMillis >= today }
        _notes.value = allDemoNotes.filter { it.timeMillis >= today }

        // The latest of each, from before today too (early in the morning today has little)
        _lastBreastfeedRemote.value = allDemoFeedings.firstOrNull { it.type == FeedType.NURSING }
        _recentFeedsRemote.value = allDemoFeedings.sortedByDescending { it.timeMillis }.take(10)
        _lastDiaperRemote.value = allDemoDiapers.firstOrNull()
        _lastSleepEndRemote.value = allDemoSleeps.maxOfOrNull { it.endMillis }
        _lastBathRemote.value = allDemoNotes.firstOrNull { BATH_TAG in it.tags }?.timeMillis
        _stashBase.value = StashBase(450, now, 0, 0)
        val temperatureAt = clockMs(0, 9, 30).let { if (it < now) it else clockMs(1, 9, 30) }
        _temperatures.value = listOf(
            TemperatureEvent(id = 900L, temperature = 36.8, timeMillis = temperatureAt, timeFormatted = formatClock(temperatureAt), caregiver = "Sarah")
        )
        _dashboardWidgets.update { list -> list.map { it.copy(isEnabled = true) } }
    }

    suspend fun testConnection(
        serverUrl: String,
        token: String,
        allowSelfSigned: Boolean = false,
        cfClientId: String = "",
        cfClientSecret: String = "",
        pinnedCertSha256: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val pin = pinnedCertSha256.takeIf { allowSelfSigned }.orEmpty()
            val api = BabyBuddyApiClient.createService(serverUrl, allowSelfSigned, cfClientId, cfClientSecret, pin)
            val authHeader = if (token.startsWith("Token ")) token else "Token $token"
            val response = api.getChildren(authHeader)
            if (response.isSuccessful) {
                val children = response.body()?.results ?: emptyList()
                val detectedChild = children.firstOrNull()
                val detectedName = detectedChild?.let { "${it.firstName} ${it.lastName}".trim() } ?: AppText.get(R.string.profile_baby)
                val detectedShort = detectedChild?.firstName ?: AppText.get(R.string.profile_baby)

                _babyProfile.update {
                    it.copy(
                        name = detectedName,
                        shortName = detectedShort,
                        birthDate = detectedChild?.birthDate ?: it.birthDate,
                        ageFormatted = detectedChild?.birthDate?.let(::formatAge) ?: it.ageFormatted
                    )
                }

                _connectionConfig.update {
                    it.copy(
                        serverUrl = serverUrl,
                        apiToken = token,
                        isConnected = true,
                        isDemoMode = false,
                        allowSelfSignedCert = allowSelfSigned,
                        pinnedCertSha256 = pin,
                        cfClientId = cfClientId,
                        cfClientSecret = cfClientSecret,
                        detectedChildName = detectedName,
                        lastSyncFormatted = AppText.get(R.string.synced_just_now)
                    )
                }
                _isServerDisconnectedView.value = false

                remoteApi = api
                remoteAuthHeader = authHeader
                remoteChildId = detectedChild?.id
                prefs?.edit()
                    ?.putString(PREF_URL, serverUrl)
                    ?.putString(PREF_TOKEN_ENC, runCatching { SecretCipher.encrypt(token) }.getOrNull())
                    ?.putBoolean(PREF_SELF_SIGNED, allowSelfSigned)
                    ?.putString(PREF_CERT_PIN, pin)
                    ?.remove(PREF_DEMO_MODE) // logging in from demo mode must stick after a restart
                    ?.putString(PREF_CF_ID, cfClientId)
                    ?.putString(PREF_CF_SECRET_ENC, cfClientSecret.takeIf { it.isNotBlank() }?.let { runCatching { SecretCipher.encrypt(it) }.getOrNull() })
                    ?.putInt(PREF_CHILD_ID, detectedChild?.id ?: -1)
                    ?.putString(PREF_CHILD_NAME, detectedName)
                    ?.putString(PREF_BIRTH_DATE, detectedChild?.birthDate)
                    ?.apply()

                syncToday(api, authHeader, detectedChild?.id)
                flushPendingWrites()
                pollSharedTimers()
                loadMilkStash()

                Result.success(AppText.get(R.string.connected_as, detectedShort))
            } else if (response.code() == 401 || response.code() == 403) {
                val behindCloudflare = response.headers()["cf-ray"] != null
                Result.failure(
                    Exception(
                        if (behindCloudflare && cfClientId.isBlank()) {
                            AppText.get(R.string.error_blocked_by_cloudflare, response.code())
                        } else {
                            AppText.get(if (behindCloudflare) R.string.error_check_token_and_cloudflare else R.string.error_check_token, response.code())
                        }
                    )
                )
            } else {
                Result.failure(Exception("HTTP ${response.code()}: ${response.message()}"))
            }
        } catch (e: com.squareup.moshi.JsonDataException) {
            Result.failure(Exception(AppText.get(R.string.error_web_page_not_api)))
        } catch (e: com.squareup.moshi.JsonEncodingException) {
            Result.failure(Exception(AppText.get(R.string.error_web_page_not_api)))
        } catch (e: Exception) {
            // OkHttp wraps the trust manager's verdict in an SSLHandshakeException; surface it so the user can trust the certificate
            val untrusted = e.findInChain { it is UntrustedCertificateException }
            Result.failure(
                when {
                    untrusted != null -> untrusted

                    !allowSelfSigned && e.findInChain { it is java.security.cert.CertPathValidatorException } != null ->
                        Exception(AppText.get(R.string.error_certificate_not_trusted))

                    else -> e
                }
            )
        }
    }

    /** Replaces local lists with today's (device-local midnight onwards) entries from the server. */
    private suspend fun syncToday(api: BabyBuddyApiService, authHeader: String, childId: Int?) {
        val since = startOfTodayUtc()

        runCatching {
            api.getFeedings(authHeader, childId, since).body()!!.results.map(::mapFeeding)
        }.onSuccess { remote ->
            _feedings.value = mergeWithPending(ENDPOINT_FEEDINGS, remote, _feedings.value, { it.id }, { it.serverId }, { it.timeMillis })
        }

        runCatching {
            // Newest first; skip bottles to find the last breastfeed, which may be from yesterday
            api.getFeedings(authHeader, childId, limit = 10).body()!!.results.map(::mapFeeding)
        }.onSuccess { recent ->
            _lastBreastfeedRemote.value = recent.firstOrNull { it.type == FeedType.NURSING }
            _recentFeedsRemote.value = recent.sortedByDescending { it.timeMillis }
        }

        runCatching {
            // Newest first (may be from yesterday)
            api.getChanges(authHeader, childId, limit = 1).body()!!.results.map(::mapChange).firstOrNull()
        }.onSuccess { _lastDiaperRemote.value = it }

        runCatching {
            api.getSleep(authHeader, childId, limit = 5).body()!!.results.map(::mapSleep)
        }.onSuccess { recent -> _lastSleepEndRemote.value = recent.maxOfOrNull { it.endMillis } }

        runCatching {
            api.getTags(authHeader).body()!!.results
        }.onSuccess { tags ->
            _noteTags.value = tags.filter { it.name.isNotBlank() && it.name != MILK_STASH_TAG && it.name != BATH_TAG }
                .map { NoteTag(it.name, it.color, it.lastUsed?.let(::parseServerTime)?.time ?: 0L) }
                .sortedByDescending { it.lastUsedMillis }
        }

        runCatching {
            val monthAgo = isoUtc(System.currentTimeMillis() - 45L * 24 * 60 * 60_000L)
            api.getNotes(authHeader, childId, since = monthAgo, limit = 1000).body()!!.results
                .filter { BATH_TAG in it.tags.orEmpty() }
                .mapNotNull { it.time?.let(::parseServerTime)?.time }
                .maxOrNull()
        }.onSuccess { _lastBathRemote.value = it }

        runCatching {
            api.getChanges(authHeader, childId, since).body()!!.results.map(::mapChange)
        }.onSuccess { remote ->
            _diapers.value = mergeWithPending(ENDPOINT_CHANGES, remote, _diapers.value, { it.id }, { it.serverId }, { it.timeMillis })
        }

        runCatching {
            api.getTummyTimes(authHeader, childId, since).body()!!.results.map(::mapTummyTime)
        }.onSuccess { remote ->
            _tummyTimes.value = mergeWithPending(ENDPOINT_TUMMY, remote, _tummyTimes.value, { it.id }, { it.serverId }, { it.timeMillis })
        }

        runCatching {
            api.getPumping(authHeader, childId, since).body()!!.results.map(::mapPumping)
        }.onSuccess { remote ->
            _pumpings.value = mergeWithPending(ENDPOINT_PUMPING, remote, _pumpings.value, { it.id }, { it.serverId }, { it.timeMillis })
        }

        runCatching {
            // Sleeps that ended today, so last night's sleep counts too
            api.getSleep(authHeader, childId, endedSince = since).body()!!.results.map(::mapSleep)
        }.onSuccess { remote ->
            _sleeps.value = mergeWithPending(ENDPOINT_SLEEP, remote, _sleeps.value, { it.id }, { it.serverId }, { it.timeMillis })
        }

        runCatching {
            api.getTemperatures(authHeader, childId, since).body()!!.results.map(::mapTemperature)
        }.onSuccess { remote ->
            _temperatures.value = mergeWithPending(ENDPOINT_TEMPERATURE, remote, _temperatures.value, { it.id }, { it.serverId }, { it.timeMillis })
        }

        runCatching {
            api.getNotes(authHeader, childId, since).body()!!.results.map(::mapNote)
        }.onSuccess { remote ->
            _notes.value = mergeWithPending(ENDPOINT_NOTES, remote, _notes.value, { it.id }, { it.serverId }, { it.timeMillis })
        }

        if (TrackedItem.GROWTH !in _hiddenItems.value) {
            GrowthKind.entries.forEach { kind ->
                runCatching {
                    api.getGrowth(authHeader, kind.endpoint, childId).body()!!.results.map { mapGrowth(kind, it) }
                }.onSuccess { remote ->
                    val merged = mergeWithPending(kind.endpoint, remote, _growth.value.filter { it.kind == kind }, { it.id }, { it.serverId }, { it.dateMillis })
                    _growth.update { list -> (list.filter { it.kind != kind } + merged).sortedByDescending { it.dateMillis } }
                }
            }
        }
        val syncedAt = System.currentTimeMillis()
        _connectionConfig.update { it.copy(lastSyncFormatted = AppText.get(R.string.synced_at, formatClock(syncedAt)), lastSyncMillis = syncedAt) }
    }

    /**
     * Server entries win, except where a queued write hasn't reached the server yet:
     * entries with a pending create/update keep their local version, and pending deletes stay deleted.
     */
    private fun <T> mergeWithPending(
        endpoint: String,
        remote: List<T>,
        local: List<T>,
        id: (T) -> Long,
        serverId: (T) -> Long?,
        time: (T) -> Long
    ): List<T> {
        val pending = pendingSnapshot.filter { it.endpoint == endpoint }
        val touchedLocalIds = pending.map { it.localId }.toSet()
        val deletedServerIds = pending.filter { it.action == QueuedWrite.ACTION_DELETE }
            .mapNotNull { it.serverId ?: serverIds[it.localId] ?: it.localId }.toSet()
        val keepLocal = local.filter { id(it) in touchedLocalIds }
        val shadowed = keepLocal.mapNotNull(serverId).toSet() + keepLocal.map(id)
        val keepRemote = remote.filter { r -> serverId(r).let { it !in deletedServerIds && it !in shadowed } }
        return (keepRemote + keepLocal).sortedByDescending(time)
    }

    // --- Generated segments in server notes: "Caregiver: Alex", and on breastfeeds "Left: … | Right: … | Sides: …" ---

    /** Removes "Caregiver: X" segments (" | "-separated or on their own line) and returns (caregiver, remaining notes). */
    private fun splitCaregiver(notes: String): Pair<String?, String> {
        var caregiver: String? = null
        val segment = Regex("""^Caregiver:\s*(.+)$""")
        val lines = notes.lines().mapNotNull { line ->
            line.split(" | ").filter { seg ->
                val match = segment.find(seg.trim())
                if (match != null) caregiver = match.groupValues[1].trim()
                match == null
            }.joinToString(" | ").takeIf { it.isNotBlank() }
        }
        return caregiver to lines.joinToString("\n").trim()
    }

    /** Adds "Caregiver: X" as the last line. */
    private fun withCaregiver(notes: String, caregiver: String?): String = listOfNotNull(notes.ifBlank { null }, caregiver?.let { "Caregiver: $it" }).joinToString("\n")

    /** Removes the generated "Left: … | Right: … | Sides: …" segments; returns (side order, remaining notes). */
    private fun splitFeedingMeta(notes: String): Pair<String?, String> {
        var sides: String? = null
        val generated = Regex("""^(Left|Right):\s*\d+m(\s*\d+s)?$""")
        val sidesSegment = Regex("""^Sides:\s*(.+)$""")
        val lines = notes.lines().mapNotNull { line ->
            line.split(" | ").filter { seg ->
                val t = seg.trim()
                sidesSegment.find(t)?.let { sides = it.groupValues[1].trim() }
                !generated.matches(t) && !sidesSegment.matches(t)
            }.joinToString(" | ").takeIf { it.isNotBlank() }
        }
        return sides to lines.joinToString("\n").trim()
    }

    /** Server notes for a feed: the generated line (breastfeeds) with the caregiver, then the user's notes. */
    private fun composeFeedingNotes(e: FeedingEvent): String {
        if (e.type != FeedType.NURSING) return withCaregiver(e.notes, e.caregiver)
        val line = listOfNotNull(
            "Left: ${e.leftDurationSec / 60}m ${e.leftDurationSec % 60}s",
            "Right: ${e.rightDurationSec / 60}m ${e.rightDurationSec % 60}s",
            e.sides?.let { "Sides: $it" },
            e.caregiver?.let { "Caregiver: $it" }
        ).joinToString(" | ")
        return listOfNotNull(line, e.notes.ifBlank { null }).joinToString("\n")
    }

    private class DiaperExtras(val rash: Boolean, val creams: List<String>, val consistency: String, val amount: String, val notes: String)

    /**
     * Amount (Light/Medium/Heavy), rash, cream and consistency have no server field; they live in one
     * "Amount: … | Rash | Cream: … | Consistency: …" notes line.
     */
    private fun composeDiaperNotes(e: DiaperEvent): String {
        val solid = e.type == DiaperType.DIRTY || e.type == DiaperType.BOTH
        val extras = buildList {
            if (e.type != DiaperType.DRY && e.amount.isNotBlank()) add("Amount: ${e.amount}")
            if (e.rashPresent) add("Rash")
            if (e.skinBarrierApplied.isNotEmpty()) add("Cream: ${e.skinBarrierApplied.joinToString(", ")}")
            if (solid && e.consistency.isNotBlank()) add("Consistency: ${e.consistency}")
        }.joinToString(" | ")
        return listOf(e.notes, extras).filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun parseDiaperNotes(notes: String): DiaperExtras {
        val segment = Regex("""^(Amount: .+|Rash|Cream: .+|Consistency: .+)$""")
        val lines = notes.lines()
        val extrasLine = lines.lastOrNull { line ->
            line.isNotBlank() && line.split(" | ").all { segment.matches(it.trim()) }
        } ?: return DiaperExtras(false, emptyList(), "", "", notes)
        val segments = extrasLine.split(" | ").map { it.trim() }
        return DiaperExtras(
            rash = "Rash" in segments,
            creams = segments.firstOrNull { it.startsWith("Cream: ") }?.removePrefix("Cream: ")?.split(", ").orEmpty(),
            consistency = segments.firstOrNull { it.startsWith("Consistency: ") }?.removePrefix("Consistency: ").orEmpty(),
            amount = segments.firstOrNull { it.startsWith("Amount: ") }?.removePrefix("Amount: ").orEmpty(),
            notes = (lines - extrasLine).joinToString("\n").trim()
        )
    }

    // App colour names <-> the server's colour choices
    private fun appDiaperColor(server: String?): String = when (server) {
        "yellow" -> "Mustard"
        "brown" -> "Tan/Brown"
        "green" -> "Greenish"
        "black" -> "Dark"
        else -> server.orEmpty()
    }

    private fun serverDiaperColor(app: String): String = when (app) {
        "Mustard" -> "yellow"
        "Tan/Brown" -> "brown"
        "Greenish" -> "green"
        "Dark" -> "black"
        in SERVER_COLORS -> app
        else -> ""
    }

    private fun mapChange(dto: BabyBuddyChangeDto): DiaperEvent {
        val (caregiver, rest) = splitCaregiver(dto.notes.orEmpty())
        val extras = parseDiaperNotes(rest)
        return DiaperEvent(
            id = dto.id ?: nextLocalId(),
            serverId = dto.id,
            timeMillis = parseServerTime(dto.time)?.time ?: System.currentTimeMillis(),
            childId = dto.child,
            timeFormatted = formatServerTime(dto.time),
            type = when {
                dto.wet && dto.solid -> DiaperType.BOTH
                dto.solid -> DiaperType.DIRTY
                dto.wet -> DiaperType.WET
                else -> DiaperType.DRY
            },
            amount = extras.amount,
            consistency = extras.consistency,
            color = appDiaperColor(dto.color),
            rashPresent = extras.rash,
            skinBarrierApplied = extras.creams,
            notes = extras.notes,
            caregiver = caregiver,
            imageUrl = null
        )
    }

    private fun mapPumping(dto: BabyBuddyPumpingDto): PumpingEvent {
        val start = dto.start ?: dto.end
        val (caregiver, rest) = splitCaregiver(dto.notes.orEmpty())
        return PumpingEvent(
            id = dto.id ?: nextLocalId(),
            serverId = dto.id,
            timeMillis = start?.let(::parseServerTime)?.time ?: System.currentTimeMillis(),
            timeFormatted = start?.let(::formatServerTime).orEmpty(),
            durationMinutes = parseDurationSeconds(dto.duration) / 60,
            amountMl = dto.amount?.toInt() ?: 0,
            notes = rest,
            caregiver = caregiver
        )
    }

    private fun mapSleep(dto: BabyBuddySleepDto): SleepEvent {
        val (caregiver, rest) = splitCaregiver(dto.notes.orEmpty())
        val start = parseServerTime(dto.start)?.time ?: System.currentTimeMillis()
        val end = dto.end?.let(::parseServerTime)?.time ?: (start + parseDurationSeconds(dto.duration) * 1000L)
        return SleepEvent(
            id = dto.id ?: nextLocalId(),
            serverId = dto.id,
            caregiver = caregiver,
            timeMillis = start,
            endMillis = end,
            timeFormatted = formatServerTime(dto.start),
            isNap = dto.nap ?: isNapTime(start),
            notes = rest
        )
    }

    private fun mapTummyTime(dto: BabyBuddyTummyTimeDto): TummyTimeEvent = TummyTimeEvent(
        id = dto.id ?: nextLocalId(),
        serverId = dto.id,
        timeMillis = parseServerTime(dto.start)?.time ?: System.currentTimeMillis(),
        childId = dto.child,
        timeFormatted = formatServerTime(dto.start),
        durationMinutes = parseDurationSeconds(dto.duration) / 60,
        milestone = dto.milestone.orEmpty(),
        caregiver = splitCaregiver(dto.notes.orEmpty()).first
    )

    private fun mapTemperature(dto: BabyBuddyTemperatureDto): TemperatureEvent {
        val (caregiver, rest) = splitCaregiver(dto.notes.orEmpty())
        return TemperatureEvent(
            id = dto.id ?: nextLocalId(),
            serverId = dto.id,
            timeMillis = dto.time?.let(::parseServerTime)?.time ?: System.currentTimeMillis(),
            timeFormatted = dto.time?.let(::formatServerTime).orEmpty(),
            temperature = dto.temperature ?: 0.0,
            notes = rest,
            caregiver = caregiver
        )
    }

    private fun mapGrowth(kind: GrowthKind, dto: BabyBuddyGrowthDto): GrowthEvent {
        val (caregiver, rest) = splitCaregiver(dto.notes.orEmpty())
        return GrowthEvent(
            id = dto.id ?: nextLocalId(),
            serverId = dto.id,
            kind = kind,
            dateMillis = dto.date?.let(::parseLocalDateNoon) ?: System.currentTimeMillis(),
            value = growthValue(kind, dto) ?: 0.0,
            notes = rest,
            caregiver = caregiver
        )
    }

    private fun growthValue(kind: GrowthKind, dto: BabyBuddyGrowthDto): Double? = when (kind) {
        GrowthKind.WEIGHT -> dto.weight
        GrowthKind.LENGTH -> dto.height
        GrowthKind.HEAD -> dto.headCircumference
    }

    /** "2026-10-12" → local noon of that day (so the day never shifts with time zones). */
    private fun parseLocalDateNoon(date: String): Long? = runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)!!.time + 12 * 3_600_000L
    }.getOrNull()

    private fun localDate(millis: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))

    private fun mapNote(dto: BabyBuddyNoteDto): NoteEvent = NoteEvent(
        id = dto.id ?: nextLocalId(),
        serverId = dto.id,
        timeMillis = dto.time?.let(::parseServerTime)?.time ?: System.currentTimeMillis(),
        timeFormatted = dto.time?.let(::formatServerTime).orEmpty(),
        text = dto.note.orEmpty(),
        tags = dto.tags.orEmpty()
    )

    /**
     * Entries for the [days] local days ending with the day containing [lastDayMillis], oldest first.
     * Today's column uses the live local lists, which include unsent entries and pending edits.
     */
    suspend fun loadHistory(lastDayMillis: Long, days: Int = 7): Result<List<HistoryDay>> = withContext(Dispatchers.IO) {
        val dayStarts = (days - 1 downTo 0).map { back ->
            Calendar.getInstance().apply {
                timeInMillis = lastDayMillis
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, -back)
            }.timeInMillis
        }
        if (_connectionConfig.value.isDemoMode) {
            val feeds = allDemoFeedings
            val changes = allDemoDiapers
            val tummies = allDemoTummyTimes
            val pumps = allDemoPumpings
            val sleeps = allDemoSleeps
            val allNotes = allDemoNotes
            val (baths, otherNotes) = allNotes.partition { BATH_TAG in it.tags }
            val daysList = dayStarts.map { start ->
                val end = Calendar.getInstance().apply {
                    timeInMillis = start
                    add(Calendar.DAY_OF_YEAR, 1)
                }.timeInMillis
                HistoryDay(
                    dayStartMillis = start,
                    feedings = feeds.filter { it.timeMillis in start until end },
                    diapers = changes.filter { it.timeMillis in start until end },
                    tummyTimes = tummies.filter { it.timeMillis in start until end },
                    pumpings = pumps.filter { it.timeMillis in start until end },
                    sleeps = sleeps.filter { it.timeMillis in start until end },
                    baths = baths.filter { it.timeMillis in start until end },
                    notes = otherNotes.filter { it.timeMillis in start until end }
                )
            }
            return@withContext Result.success(daysList)
        }
        val api = remoteApi
        val child = remoteChildId
        if (api == null || child == null) {
            return@withContext Result.failure(IllegalStateException(AppText.get(R.string.error_connect_for_history)))
        }
        try {
            val rangeEnd = Calendar.getInstance().apply {
                timeInMillis = dayStarts.last()
                add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
            val since = isoUtc(dayStarts.first())
            val until = isoUtc(rangeEnd - 1000)

            val feedResp = api.getFeedings(remoteAuthHeader, child, since = since, until = until, limit = 1000)
            val changeResp = api.getChanges(remoteAuthHeader, child, since = since, until = until, limit = 1000)
            val tummyResp = api.getTummyTimes(remoteAuthHeader, child, since = since, until = until, limit = 1000)
            val pumpResp = api.getPumping(remoteAuthHeader, child, since = since, until = until, limit = 1000)
            val sleepResp = runCatching { api.getSleep(remoteAuthHeader, child, since = since, until = until, limit = 1000) }.getOrNull()
            val noteResp = runCatching { api.getNotes(remoteAuthHeader, child, since = since, until = until, limit = 1000) }.getOrNull()
            if (!feedResp.isSuccessful) error("HTTP ${feedResp.code()} loading feedings")
            if (!changeResp.isSuccessful) error("HTTP ${changeResp.code()} loading diaper changes")

            val feeds = feedResp.body()?.results.orEmpty().map(::mapFeeding)
            val changes = changeResp.body()?.results.orEmpty().map(::mapChange)
            val tummies = tummyResp.body()?.results.orEmpty().map(::mapTummyTime)
            val pumps = pumpResp.body()?.results.orEmpty().map(::mapPumping)
            val sleeps = sleepResp?.body()?.results.orEmpty().map(::mapSleep)
            val allNotes = noteResp?.body()?.results.orEmpty().map(::mapNote)
            val (baths, otherNotes) = allNotes.partition { BATH_TAG in it.tags }
            val todayStart = startOfTodayMillis()

            Result.success(
                dayStarts.map { start ->
                    val end = Calendar.getInstance().apply {
                        timeInMillis = start
                        add(Calendar.DAY_OF_YEAR, 1)
                    }.timeInMillis
                    if (start == todayStart) {
                        HistoryDay(
                            start,
                            _feedings.value,
                            _diapers.value,
                            _tummyTimes.value,
                            _pumpings.value,
                            _sleeps.value.filter { it.timeMillis >= start },
                            _notes.value.filter { BATH_TAG in it.tags && it.timeMillis >= start },
                            _notes.value.filter { BATH_TAG !in it.tags && it.timeMillis >= start }
                        )
                    } else {
                        HistoryDay(
                            dayStartMillis = start,
                            feedings = feeds.filter { it.timeMillis in start until end },
                            diapers = changes.filter { it.timeMillis in start until end },
                            tummyTimes = tummies.filter { it.timeMillis in start until end },
                            pumpings = pumps.filter { it.timeMillis in start until end },
                            sleeps = sleeps.filter { it.timeMillis in start until end },
                            baths = baths.filter { it.timeMillis in start until end },
                            notes = otherNotes.filter { it.timeMillis in start until end }
                        )
                    }
                }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun mapFeeding(dto: BabyBuddyFeedingDto): FeedingEvent {
        val method = dto.method.lowercase()
        val totalSec = parseDurationSeconds(dto.duration)
        val side = when (method) {
            "left breast" -> BreastSide.LEFT
            "right breast" -> BreastSide.RIGHT
            "both breasts" -> BreastSide.BOTH
            else -> null
        }
        // The server only stores a total duration. For "both breasts", use a
        // "Left: 7m 44s | Right: 6m 40s" note if present, else split evenly.
        val (leftSec, rightSec) = when (side) {
            BreastSide.LEFT -> totalSec to 0
            BreastSide.RIGHT -> 0 to totalSec
            BreastSide.BOTH -> parseSideSplit(dto.notes) ?: (totalSec / 2 to totalSec - totalSec / 2)
            null -> totalSec to 0
        }
        val (caregiver, withoutCaregiver) = splitCaregiver(dto.notes.orEmpty())
        val (sides, userNotes) = splitFeedingMeta(withoutCaregiver)
        return FeedingEvent(
            id = dto.id ?: nextLocalId(),
            serverId = dto.id,
            timeMillis = parseServerTime(dto.start)?.time ?: System.currentTimeMillis(),
            bottleFormula = dto.type.lowercase() == "formula",
            childId = dto.child,
            timeFormatted = formatServerTime(dto.start),
            type = when {
                dto.type.lowercase() == "solid food" -> FeedType.SOLID
                side != null -> FeedType.NURSING
                else -> FeedType.BOTTLE
            },
            side = side,
            leftDurationSec = leftSec,
            rightDurationSec = rightSec,
            amountMl = dto.amount?.toInt(),
            notes = userNotes,
            caregiver = caregiver,
            sides = sides,
            observations = emptyList(),
            imageUrl = null
        )
    }

    private fun parseSideSplit(notes: String?): Pair<Int, Int>? {
        if (notes == null) return null
        fun side(name: String): Int? = Regex("""$name:\s*(?:(\d+)m)?\s*(?:(\d+)s)?""", RegexOption.IGNORE_CASE).find(notes)?.let { m ->
            val min = m.groupValues[1].toIntOrNull()
            val sec = m.groupValues[2].toIntOrNull()
            if (min == null && sec == null) null else (min ?: 0) * 60 + (sec ?: 0)
        }
        val left = side("Left") ?: return null
        val right = side("Right") ?: return null
        return left to right
    }

    /** Parses Django REST DurationField output: "[D ]HH:MM:SS[.ffffff]". */
    private fun parseDurationSeconds(duration: String?): Int {
        if (duration.isNullOrBlank()) return 0
        val parts = duration.trim().split(" ")
        val days = if (parts.size > 1) parts[0].toIntOrNull() ?: 0 else 0
        val hms = parts.last().substringBefore(".").split(":").map { it.toIntOrNull() ?: 0 }
        val (h, m, sec) = when (hms.size) {
            3 -> Triple(hms[0], hms[1], hms[2])
            2 -> Triple(0, hms[0], hms[1])
            else -> Triple(0, 0, hms.firstOrNull() ?: 0)
        }
        return days * 86400 + h * 3600 + m * 60 + sec
    }

    /** Every page of a list endpoint (up to [MAX_FETCH] entries); [load] gets the offset. */
    private suspend fun <T> allPages(what: String, load: suspend (offset: Int) -> retrofit2.Response<PaginatedResponse<T>>): List<T> {
        val out = mutableListOf<T>()
        while (out.size < MAX_FETCH) {
            val resp = load(out.size)
            if (!resp.isSuccessful) error("HTTP ${resp.code()} loading $what")
            val page = resp.body() ?: break
            out += page.results
            if (page.next == null || page.results.isEmpty()) break
        }
        return out
    }

    /**
     * Feeds (from the day before [fromMillis], for gaps across it), diaper changes and sleeps (ended since
     * [fromMillis], plus the one being timed, ending now) up to [toMillis] from the server, or the demo data.
     */
    private suspend fun fetchEvents(fromMillis: Long, toMillis: Long? = null): Triple<List<FeedingEvent>, List<DiaperEvent>, List<SleepEvent>> {
        val api = remoteApi
        val child = remoteChildId
        if (_connectionConfig.value.isDemoMode) {
            val to = toMillis ?: Long.MAX_VALUE
            return Triple(
                allDemoFeedings.filter { it.timeMillis < to }.sortedBy { it.timeMillis },
                allDemoDiapers.filter { it.timeMillis < to },
                allDemoSleeps.filter { it.timeMillis < to }
            )
        }
        check(api != null && child != null) { AppText.get(R.string.error_connect_for_trends) }
        val until = toMillis?.let(::isoUtc)
        val fetchFrom = fromMillis - 24 * 60 * 60_000L
        val feeds = allPages("feedings") { api.getFeedings(remoteAuthHeader, child, since = isoUtc(fetchFrom), until = until, limit = PAGE, offset = it) }
            .map(::mapFeeding)
            .sortedBy { it.timeMillis }
        val changes = allPages("diaper changes") { api.getChanges(remoteAuthHeader, child, since = isoUtc(fromMillis), until = until, limit = PAGE, offset = it) }
            .map(::mapChange)
        val now = System.currentTimeMillis()
        val sleeps = runCatching {
            allPages("sleep") { api.getSleep(remoteAuthHeader, child, until = until, endedSince = isoUtc(fromMillis), limit = PAGE, offset = it) }
        }.getOrDefault(emptyList()).map(::mapSleep) +
            listOfNotNull(
                _sleepTimer.value.takeIf { it.isRunning && toMillis == null }?.let { t ->
                    SleepEvent(id = -1L, timeMillis = t.startMillis, endMillis = now, isNap = isNapTime(t.startMillis))
                }
            )
        return Triple(feeds, changes, sleeps)
    }

    /**
     * Feeds, changes and finished sleeps from [fromMillis] to [toMillis], for training the rhythm models
     * (Trends → Models). Age counts from the birth date, else from [fromMillis].
     */
    suspend fun loadRhythmEvents(fromMillis: Long, toMillis: Long, birthDate: String?): Result<RhythmEvents> = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            val (feeds, changes, sleeps) = fetchEvents(fromMillis, toMillis.takeIf { it < now - 60_000L })
            val running = _sleepTimer.value.takeIf { it.isRunning && toMillis >= now - 60_000L }?.startMillis
            val birth = birthDate?.let { runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(it)?.time }.getOrNull() }
            Result.success(RhythmEvents(feeds, changes, sleeps.filter { it.id != -1L }, running, birth ?: fromMillis))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        }
    }

    // Trends → Models: the models picked for reminders, Today and the summary widget (prefs "rhythm_models"),
    // and when each expects the next event, from the last two days of entries (refetched every half hour)
    private val _usedModels = MutableStateFlow<Map<RhythmTarget, SavedRhythmModel>>(emptyMap())
    val usedModels: StateFlow<Map<RhythmTarget, SavedRhythmModel>> = _usedModels.asStateFlow()
    private val _rhythmPredictions = MutableStateFlow<Map<RhythmTarget, RhythmPrediction>>(emptyMap())
    val rhythmPredictions: StateFlow<Map<RhythmTarget, RhythmPrediction>> = _rhythmPredictions.asStateFlow()
    private var recentEvents: Triple<List<FeedingEvent>, List<DiaperEvent>, List<SleepEvent>>? = null
    private var recentFetchedAt = 0L
    private val predictionMutex = Mutex()

    /** Uses [model] for its rhythm from now on, or stops using a model for [target] when null. */
    fun setUsedModel(target: RhythmTarget, model: SavedRhythmModel?) {
        _usedModels.update { if (model == null) it - target else it + (target to model) }
        modelPrefs?.edit()?.apply { if (model == null) remove(target.name) else putString(target.name, model.toText()) }?.apply()
        syncScope.launch { updatePredictions() }
    }

    /** The model's expected time for the next event counted from [anchorMillis], if a model is used for [target]. */
    fun modelDue(target: RhythmTarget, anchorMillis: Long): Long? = _rhythmPredictions.value[target]?.takeIf { kotlin.math.abs(it.anchorMillis - anchorMillis) < 60_000L }?.dueMillis

    /** Like [modelDue], but once that time has passed, when the event now looks most likely (see [RhythmPrediction.expectedMillis]). */
    private fun modelExpected(target: RhythmTarget, anchorMillis: Long, now: Long): Long? = _rhythmPredictions.value[target]?.takeIf { kotlin.math.abs(it.anchorMillis - anchorMillis) < 60_000L }?.expectedMillis(now)

    /**
     * The picked models' times for what's next from here: the next feed (unless one is running), the next diaper
     * change, and the wake-up while a sleep is timed, else the next sleep. Untracked items are left out. An overdue
     * time is re-estimated every minute.
     */
    val modelNextTimes: StateFlow<Map<RhythmTarget, Long>> by lazy {
        val minutes = flow {
            while (true) {
                emit(System.currentTimeMillis())
                delay(60_000L)
            }
        }
        combine(_rhythmPredictions, _hiddenItems, _liveTimerState.map { it.isRunning }, minutes) { _, hidden, feeding, now ->
            val sleep = _sleepTimer.value
            buildMap {
                val feedsShown = TrackedItem.BREASTFEEDING !in hidden || TrackedItem.BOTTLE !in hidden
                lastFeedMillis.value?.takeIf { feedsShown && !feeding }?.let { modelExpected(RhythmTarget.FEED_GAP, it, now) }?.let { put(RhythmTarget.FEED_GAP, it) }
                lastDiaper.value?.takeIf { TrackedItem.DIAPERS !in hidden }?.let { modelExpected(RhythmTarget.DIAPER_GAP, it.timeMillis, now) }
                    ?.let { put(RhythmTarget.DIAPER_GAP, it) }
                if (TrackedItem.SLEEP !in hidden) {
                    if (sleep.isRunning) {
                        modelExpected(RhythmTarget.SLEEP_LENGTH, sleep.startMillis, now)?.let { put(RhythmTarget.SLEEP_LENGTH, it) }
                    } else {
                        lastSleepEndMillis.value?.let { modelExpected(RhythmTarget.AWAKE_WINDOW, it, now) }?.let { put(RhythmTarget.AWAKE_WINDOW, it) }
                    }
                }
            }
        }.stateIn(syncScope, SharingStarted.Eagerly, emptyMap())
    }

    /**
     * What's next for the next-up notification: [modelNextTimes], else the next feed and diaper change by the
     * reminder intervals (Settings → Notifications).
     */
    val nextUpTimes: StateFlow<Map<RhythmTarget, Long>> by lazy {
        combine(
            modelNextTimes,
            _reminders,
            combine(lastFeedMillis, lastDiaper.map { it?.timeMillis }) { feed, diaper -> feed to diaper },
            _liveTimerState.map { it.isRunning },
            _hiddenItems
        ) { model, reminders, (lastFeed, lastChange), feeding, hidden ->
            buildMap {
                putAll(model)
                val feedsShown = TrackedItem.BREASTFEEDING !in hidden || TrackedItem.BOTTLE !in hidden
                if (RhythmTarget.FEED_GAP !in model && feedsShown && !feeding && lastFeed != null && reminders.feedAfterMinutes > 0) {
                    put(RhythmTarget.FEED_GAP, lastFeed + reminders.feedAfterMinutes * 60_000L)
                }
                if (RhythmTarget.DIAPER_GAP !in model && TrackedItem.DIAPERS !in hidden && lastChange != null && reminders.diaperAfterMinutes > 0) {
                    put(RhythmTarget.DIAPER_GAP, lastChange + reminders.diaperAfterMinutes * 60_000L)
                }
            }
        }.stateIn(syncScope, SharingStarted.Eagerly, emptyMap())
    }

    /** Recomputes [rhythmPredictions]: today's entries from memory, the day before from the server. */
    suspend fun updatePredictions() = predictionMutex.withLock {
        val models = _usedModels.value.values
        if (models.isEmpty()) {
            _rhythmPredictions.value = emptyMap()
            return@withLock
        }
        val now = System.currentTimeMillis()
        val midnight = startOfTodayMillis()
        if (recentEvents == null || recentFetchedAt < midnight || now - recentFetchedAt > 30 * 60_000L) {
            withContext(Dispatchers.IO) { runCatching { fetchEvents(now - 2 * 24 * 60 * 60_000L) } }.onSuccess {
                recentEvents = it
                recentFetchedAt = now
            }
        }
        val (oldFeeds, oldChanges, oldSleeps) = recentEvents ?: Triple(emptyList(), emptyList(), emptyList())
        val events = RhythmEvents(
            feeds = oldFeeds.filter { it.timeMillis < midnight } + _feedings.value.filter { it.timeMillis >= midnight },
            changes = oldChanges.filter { it.timeMillis < midnight } + _diapers.value.filter { it.timeMillis >= midnight },
            sleeps = oldSleeps.filter { it.id != -1L && it.endMillis < midnight } + _sleeps.value.filter { it.endMillis >= midnight },
            runningSleepStart = _sleepTimer.value.takeIf { it.isRunning }?.startMillis
        )
        _rhythmPredictions.value = withContext(Dispatchers.Default) { predictRhythms(events, models) }
    }

    /** Per-day feed/diaper totals for the last [days] days (today last). Needs a server connection. */
    suspend fun loadTrends(days: Int): Result<List<DayStats>> = withContext(Dispatchers.IO) {
        val isDemo = _connectionConfig.value.isDemoMode
        val api = remoteApi
        val child = remoteChildId
        if (!isDemo && (api == null || child == null)) {
            return@withContext Result.failure(IllegalStateException(AppText.get(R.string.error_connect_for_trends)))
        }
        try {
            val firstDay = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, -(days - 1))
            }

            val (feeds, changes, sleeps) = fetchEvents(firstDay.timeInMillis)

            fun endOf(f: FeedingEvent) = f.timeMillis + (f.leftDurationSec + f.rightDurationSec) * 1000L
            // Gaps between consecutive feeds, keyed by the time the gap starts
            val gaps = feeds.zipWithNext { prev, next -> endOf(prev) to (next.timeMillis - endOf(prev)) }
                .filter { it.second > 0 }

            val labelFormat = SimpleDateFormat("EEE d", Locale.getDefault())
            val stats = (0 until days).map { offset ->
                val dayStart = (firstDay.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset) }.timeInMillis
                val dayEnd = (firstDay.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset + 1) }.timeInMillis
                val dayFeeds = feeds.filter { it.timeMillis in dayStart until dayEnd }
                val dayChanges = changes.filter { it.timeMillis in dayStart until dayEnd }
                val nursing = dayFeeds.filter { it.type == FeedType.NURSING }
                val longestGap = gaps.filter { it.first in dayStart until dayEnd }.maxByOrNull { it.second }
                val daySleep = sleeps.mapNotNull { s ->
                    val from = maxOf(s.timeMillis, dayStart)
                    val to = minOf(s.endMillis, dayEnd)
                    if (to <= from) {
                        null
                    } else {
                        SleepMark(((from - dayStart) / 60_000L).toInt(), ((to - from) / 60_000L).toInt(), s.isNap)
                    }
                }.sortedBy { it.startMinute }
                DayStats(
                    label = labelFormat.format(Date(dayStart)),
                    feeds = dayFeeds.size,
                    nursingMinutes = nursing.sumOf { it.leftDurationSec + it.rightDurationSec } / 60,
                    bottleMl = dayFeeds.sumOf { it.amountMl ?: 0 },
                    wet = dayChanges.count { it.type == DiaperType.WET || it.type == DiaperType.BOTH },
                    dirty = dayChanges.count { it.type == DiaperType.DIRTY || it.type == DiaperType.BOTH },
                    isToday = offset == days - 1,
                    leftMinutes = nursing.sumOf { it.leftDurationSec } / 60,
                    rightMinutes = nursing.sumOf { it.rightDurationSec } / 60,
                    feedMarks = dayFeeds.map { f ->
                        FeedMark(
                            startMinute = ((f.timeMillis - dayStart) / 60_000L).toInt(),
                            durationMinutes = (f.leftDurationSec + f.rightDurationSec) / 60,
                            isBottle = f.amountMl != null
                        )
                    },
                    longestGapStartMinute = longestGap?.let { ((it.first - dayStart) / 60_000L).toInt() },
                    longestGapMinutes = longestGap?.let { (it.second / 60_000L).toInt() } ?: 0,
                    diaperMarks = dayChanges.map { c ->
                        DiaperMark(
                            minute = ((c.timeMillis - dayStart) / 60_000L).toInt(),
                            wet = c.type == DiaperType.WET || c.type == DiaperType.BOTH,
                            dirty = c.type == DiaperType.DIRTY || c.type == DiaperType.BOTH
                        )
                    },
                    sleepMarks = daySleep,
                    sleepMinutes = daySleep.sumOf { it.durationMinutes },
                    naps = sleeps.count { it.isNap && it.timeMillis in dayStart until dayEnd },
                    longestSleepMinutes = sleeps.filter { it.timeMillis in dayStart until dayEnd }
                        .maxOfOrNull { it.durationMinutes } ?: 0
                )
            }
            Result.success(stats)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseServerTime(isoString: String): Date? {
        val normalized = isoString.replace(Regex("""\.\d+"""), "").replace(Regex("Z$"), "+00:00")
        return runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(normalized) }.getOrNull()
    }

    private fun startOfTodayUtc(): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(cal.time)
    }

    private fun formatAge(birthDate: String): String? {
        val birth = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(birthDate) }.getOrNull() ?: return null
        val b = Calendar.getInstance().apply { time = birth }
        val now = Calendar.getInstance()
        var months = (now.get(Calendar.YEAR) - b.get(Calendar.YEAR)) * 12 + now.get(Calendar.MONTH) - b.get(Calendar.MONTH)
        if (now.get(Calendar.DAY_OF_MONTH) < b.get(Calendar.DAY_OF_MONTH)) months--
        if (months < 0) return null
        b.add(Calendar.MONTH, months)
        val days = ((now.timeInMillis - b.timeInMillis) / 86_400_000L).toInt()
        return if (months == 0) AppText.get(R.string.age_days, days) else AppText.get(R.string.age_months_days, months, days)
    }

    private fun formatServerTime(isoString: String): String {
        // Server timestamps are UTC ("...Z") or carry an offset; show them in device-local time
        val date = parseServerTime(isoString) ?: return isoString
        return TimeFormat.clock(date)
    }

    fun disconnect() {
        clearRemote()
        prefs?.edit()?.remove(PREF_URL)?.remove(PREF_TOKEN_ENC)?.remove(PREF_CF_ID)?.remove(PREF_CF_SECRET_ENC)?.remove(PREF_CERT_PIN)?.remove(PREF_CHILD_ID)?.remove(PREF_CHILD_NAME)?.remove(PREF_BIRTH_DATE)?.remove(PREF_DEMO_MODE)?.apply()
        _connectionConfig.value = ServerConnectionConfig()
        _feedings.value = emptyList()
        _sleeps.value = emptyList()
        _sleepTimer.value = SleepTimerState()
        prefs?.edit()?.remove(PREF_SLEEP_START)?.remove(PREF_SLEEP_NOTES)?.apply()
        _diapers.value = emptyList()
        _tummyTimes.value = emptyList()
        _pumpings.value = emptyList()
        _lastBreastfeedRemote.value = null
        _recentFeedsRemote.value = emptyList()
        _lastDiaperRemote.value = null
        _lastSleepEndRemote.value = null
        _lastBathRemote.value = null
        _noteTags.value = emptyList()
        _stashBase.value = null
        setFeedTimerRemoteId(null)
        setSleepTimerRemoteId(null)
        _temperatures.value = emptyList()
        _notes.value = emptyList()
        _growth.value = emptyList()
        _handoff.value = null
        prefs?.edit()?.remove(PREF_AWAY_SINCE)?.apply()
        // Saved rhythm models learned this child's (or the demo's) rhythm
        RhythmTarget.entries.forEach { setUsedModel(it, null) }
        _babyProfile.value = BabyProfile(name = AppText.get(R.string.profile_my_baby), shortName = AppText.get(R.string.profile_baby), ageFormatted = AppText.get(R.string.profile_newborn))
        stopAndResetTimer()
    }

    fun updateConfig(config: ServerConnectionConfig) {
        _connectionConfig.value = config
    }

    // Live Nursing Session Actions
    fun updateLiveTimer(
        isPaused: Boolean? = null,
        activeBreast: BreastSide? = null,
        leftIncrement: Int = 0,
        rightIncrement: Int = 0,
        bottleOz: Double? = null,
        notes: String? = null
    ) {
        _liveTimerState.update { curr ->
            curr.copy(
                isPaused = isPaused ?: curr.isPaused,
                activeBreast = activeBreast ?: curr.activeBreast,
                leftSeconds = curr.leftSeconds + leftIncrement,
                rightSeconds = curr.rightSeconds + rightIncrement,
                bottleAmountOz = bottleOz ?: curr.bottleAmountOz,
                notes = notes ?: curr.notes
            )
        }
    }

    // --- Breastfeeding: every tap is saved and synced; there is no separate save step ---

    /**
     * Left/Right button: starts (or resumes) the feed on [side], switches to it, or pauses it if it's the running side.
     * A feed paused for longer than [SESSION_TIMEOUT_MS] has ended, so the next tap starts a new one.
     */
    fun tapSide(side: BreastSide) {
        val t = _liveTimerState.value
        val now = System.currentTimeMillis()
        val sessionOver = !t.isRunning || t.feedingLocalId == null ||
            (t.isPaused && now - t.pausedAtMillis > SESSION_TIMEOUT_MS)
        when {
            sessionOver -> startFeedOn(side, now)

            !t.isPaused && t.activeBreast == side ->
                _liveTimerState.value = t.copy(isPaused = true, pausedAtMillis = now, lastActiveMillis = 0L)

            else ->
                _liveTimerState.value = t.copy(isPaused = false, activeBreast = side, pausedAtMillis = 0L, lastActiveMillis = 0L)
        }
        syncLiveFeeding()
        feedChanged()
    }

    /** Manually corrects one side's time on the running feed (e.g. the timer was started late). */
    fun setSideSeconds(side: BreastSide, seconds: Int) {
        val t = _liveTimerState.value
        if (!t.isRunning || t.feedingLocalId == null) return
        val s = seconds.coerceAtLeast(0)
        _liveTimerState.value = when (side) {
            BreastSide.LEFT -> t.copy(leftSeconds = s, editCount = t.editCount + 1)
            else -> t.copy(rightSeconds = s, editCount = t.editCount + 1)
        }
        syncLiveFeeding()
        feedChanged()
    }

    /**
     * Moves the running feed's start. Moving it earlier means the timer was started late, so the
     * difference goes to the side the feed began on (and comes off it when moved later).
     */
    fun setFeedStart(startMillis: Long) {
        val t = _liveTimerState.value
        if (!t.isRunning || t.feedingLocalId == null || t.sessionStartMillis <= 0L) return
        val newStart = startMillis.coerceAtMost(feedFinishMillis(t))
        val delta = ((t.sessionStartMillis - newStart) / 1000L).toInt()
        val first = t.firstSide ?: t.activeBreast
        _liveTimerState.value = t.copy(
            sessionStartMillis = newStart,
            sessionStartedTime = formatClock(newStart),
            leftSeconds = if (first == BreastSide.LEFT) (t.leftSeconds + delta).coerceAtLeast(0) else t.leftSeconds,
            rightSeconds = if (first == BreastSide.RIGHT) (t.rightSeconds + delta).coerceAtLeast(0) else t.rightSeconds,
            editCount = t.editCount + 1
        )
        syncLiveFeeding()
        feedChanged()
    }

    /** The last moment a side was running: now while running, else when it was paused. */
    fun feedFinishMillis(t: LiveTimerState = _liveTimerState.value): Long = when {
        !t.isPaused -> System.currentTimeMillis()
        t.lastActiveMillis > 0 -> t.lastActiveMillis
        t.pausedAtMillis > 0 -> t.pausedAtMillis
        else -> System.currentTimeMillis()
    }

    /** Ends the current feed now (it's already saved), so the next tap starts a new one. */
    fun endFeed() {
        syncLiveFeeding()
        _liveTimerState.value = LiveTimerState(softChimeEnabled = _liveTimerState.value.softChimeEnabled)
        feedEndedAt = System.currentTimeMillis()
        prefs?.edit()?.putLong(PREF_FEED_ENDED_AT, feedEndedAt)?.apply()
        feedTimerDirty.trySend(Unit)
    }

    private fun startFeedOn(side: BreastSide, now: Long) {
        if (_liveTimerState.value.feedingLocalId != null) syncLiveFeeding()
        val event = FeedingEvent(
            id = nextLocalId(),
            timeMillis = now,
            timeFormatted = formatClock(now),
            type = FeedType.NURSING,
            side = side,
            leftDurationSec = 0,
            rightDurationSec = 0,
            amountMl = null,
            notes = "",
            observations = emptyList(),
            imageUrl = null
        )
        _liveTimerState.value = LiveTimerState(
            isRunning = true,
            isPaused = false,
            activeBreast = side,
            sessionStartedTime = formatClock(now),
            softChimeEnabled = _liveTimerState.value.softChimeEnabled,
            feedingLocalId = event.id,
            sessionStartMillis = now,
            firstSide = side
        )
        addFeeding(event.copy(sides = side.name))
    }

    /** Pushes the running feed's current durations/sides to its feeding record (local list + server queue). */
    private fun syncLiveFeeding() {
        val t = _liveTimerState.value
        val id = t.feedingLocalId ?: return
        val existing = _feedings.value.firstOrNull { it.id == id } ?: return
        val start = t.sessionStartMillis.takeIf { it > 0 } ?: existing.timeMillis
        val updated = existing.copy(
            timeMillis = start,
            timeFormatted = formatClock(start),
            leftDurationSec = t.leftSeconds,
            rightDurationSec = t.rightSeconds,
            side = when {
                t.leftSeconds > 0 && t.rightSeconds > 0 -> BreastSide.BOTH
                t.rightSeconds > 0 -> BreastSide.RIGHT
                t.leftSeconds > 0 -> BreastSide.LEFT
                else -> t.activeBreast
            },
            sides = (t.firstSide ?: t.activeBreast).let { first ->
                if (first == t.activeBreast) first.name else "${first.name} -> ${t.activeBreast.name}"
            }
        )
        if (updated == existing) return
        _feedings.update { list -> list.map { if (it.id == id) updated else it }.sortedByDescending { it.timeMillis } }
        enqueue(QueuedWrite(ENDPOINT_FEEDINGS, QueuedWrite.ACTION_UPDATE, id, knownServerId(id, updated.serverId), feedingBody(updated)))
    }

    // Older entry points (widgets, other screens) map onto the side taps
    fun switchActiveSide() {
        val t = _liveTimerState.value
        tapSide(if (t.activeBreast == BreastSide.LEFT) BreastSide.RIGHT else BreastSide.LEFT)
    }

    fun togglePause() = tapSide(_liveTimerState.value.activeBreast)

    fun toggleChime() {
        _liveTimerState.update { it.copy(softChimeEnabled = !it.softChimeEnabled) }
    }

    fun toggleObservation(tag: String) {
        _liveTimerState.update { curr ->
            val updated = if (curr.observations.contains(tag)) {
                curr.observations - tag
            } else {
                curr.observations + tag
            }
            curr.copy(observations = updated)
        }
    }

    fun startNewNursingSession(side: BreastSide = BreastSide.RIGHT) {
        if (_liveTimerState.value.feedingLocalId != null) endFeed()
        tapSide(side)
    }

    fun stopAndResetTimer() {
        _liveTimerState.value = LiveTimerState(softChimeEnabled = _liveTimerState.value.softChimeEnabled)
    }

    /** Ends the running feed (already saved) and logs an optional bottle top-up. */
    fun finishAndSaveNursingSession(
        supplementOz: Double = 0.0,
        supplementType: String = "Pumped Breast Milk",
        notes: String = ""
    ) {
        endFeed()
        if (supplementOz > 0) {
            logBottle(Math.round(supplementOz * 29.5735).toInt(), supplementType.contains("formula", ignoreCase = true))
        }
    }

    /** Returns the new entry's id (for undo). */
    fun addDiaperLog(event: DiaperEvent, timeMs: Long = System.currentTimeMillis()): Long {
        val local = event.copy(id = nextLocalId(), timeMillis = timeMs, timeFormatted = formatClock(timeMs), caregiver = myName())
        _diapers.update { (listOf(local) + it).sortedByDescending { e -> e.timeMillis } }
        enqueue(QueuedWrite(ENDPOINT_CHANGES, QueuedWrite.ACTION_CREATE, local.id, body = changeBody(local)))
        return local.id
    }

    fun updateDiaper(updated: DiaperEvent) {
        _diapers.update { list -> list.map { if (it.id == updated.id) updated else it }.sortedByDescending { it.timeMillis } }
        _lastDiaperRemote.update { if (it?.id == updated.id) updated else it }
        enqueue(QueuedWrite(ENDPOINT_CHANGES, QueuedWrite.ACTION_UPDATE, updated.id, knownServerId(updated.id, updated.serverId), changeBody(updated)))
    }

    fun deleteDiaper(id: Long) {
        val event = _diapers.value.firstOrNull { it.id == id } ?: return
        _diapers.update { list -> list.filterNot { it.id == id } }
        _lastDiaperRemote.update { if (it?.id == id) null else it }
        enqueue(QueuedWrite(ENDPOINT_CHANGES, QueuedWrite.ACTION_DELETE, id, knownServerId(id, event.serverId)))
    }

    fun addFeeding(event: FeedingEvent): Long {
        val local = event.copy(caregiver = event.caregiver ?: myName())
        _feedings.update { (listOf(local) + it).sortedByDescending { e -> e.timeMillis } }
        enqueue(QueuedWrite(ENDPOINT_FEEDINGS, QueuedWrite.ACTION_CREATE, local.id, body = feedingBody(local)))
        return local.id
    }

    fun logBottle(ml: Int, formula: Boolean, timeMs: Long = System.currentTimeMillis(), notes: String = ""): Long = addFeeding(
        FeedingEvent(
            id = nextLocalId(),
            timeMillis = timeMs,
            timeFormatted = formatClock(timeMs),
            type = FeedType.BOTTLE,
            bottleFormula = formula,
            side = null,
            leftDurationSec = 0,
            rightDurationSec = 0,
            amountMl = ml,
            notes = notes,
            observations = emptyList(),
            imageUrl = null
        )
    )

    fun updateFeeding(updated: FeedingEvent) {
        val event = updated
        _feedings.update { list -> list.map { if (it.id == event.id) event else it }.sortedByDescending { it.timeMillis } }
        // An older feed edited from History may be the "last breastfeed" (its last side drives Today and the widget)
        _lastBreastfeedRemote.update { if (it?.id == event.id) event else it }
        _recentFeedsRemote.update { list -> list.map { if (it.id == event.id) event else it }.sortedByDescending { it.timeMillis } }
        enqueue(QueuedWrite(ENDPOINT_FEEDINGS, QueuedWrite.ACTION_UPDATE, event.id, knownServerId(event.id, event.serverId), feedingBody(event)))
    }

    fun deleteFeeding(id: Long) {
        val event = _feedings.value.firstOrNull { it.id == id } ?: return
        _feedings.update { list -> list.filterNot { it.id == id } }
        _recentFeedsRemote.update { list -> list.filterNot { it.id == id } }
        enqueue(QueuedWrite(ENDPOINT_FEEDINGS, QueuedWrite.ACTION_DELETE, id, knownServerId(id, event.serverId)))
    }

    fun updateTummyTime(updated: TummyTimeEvent) {
        _tummyTimes.update { list -> list.map { if (it.id == updated.id) updated else it }.sortedByDescending { it.timeMillis } }
        enqueue(QueuedWrite(ENDPOINT_TUMMY, QueuedWrite.ACTION_UPDATE, updated.id, knownServerId(updated.id, updated.serverId), tummyBody(updated)))
    }

    fun deleteTummyTime(id: Long) {
        val event = _tummyTimes.value.firstOrNull { it.id == id } ?: return
        _tummyTimes.update { list -> list.filterNot { it.id == id } }
        enqueue(QueuedWrite(ENDPOINT_TUMMY, QueuedWrite.ACTION_DELETE, id, knownServerId(id, event.serverId)))
    }

    /** [startMillis] defaults to "just finished": now minus the duration. */
    fun logPumping(ml: Int, minutes: Int, notes: String, startMillis: Long? = null): Long {
        val start = startMillis ?: (System.currentTimeMillis() - minutes * 60_000L)
        val event = PumpingEvent(
            id = nextLocalId(),
            timeMillis = start,
            timeFormatted = formatClock(start),
            durationMinutes = minutes,
            amountMl = ml,
            notes = notes,
            caregiver = myName()
        )
        _pumpings.update { (listOf(event) + it).sortedByDescending { e -> e.timeMillis } }
        enqueue(QueuedWrite(ENDPOINT_PUMPING, QueuedWrite.ACTION_CREATE, event.id, body = pumpingBody(event)))
        return event.id
    }

    fun logTemperature(value: Double, notes: String): Long {
        val now = System.currentTimeMillis()
        val event = TemperatureEvent(
            id = nextLocalId(),
            timeMillis = now,
            timeFormatted = formatClock(now),
            temperature = value,
            notes = notes,
            caregiver = myName()
        )
        _temperatures.update { (listOf(event) + it).sortedByDescending { e -> e.timeMillis } }
        enqueue(QueuedWrite(ENDPOINT_TEMPERATURE, QueuedWrite.ACTION_CREATE, event.id, body = temperatureBody(event)))
        return event.id
    }

    fun deleteTemperature(id: Long) {
        val event = _temperatures.value.firstOrNull { it.id == id } ?: return
        _temperatures.update { list -> list.filterNot { it.id == id } }
        enqueue(QueuedWrite(ENDPOINT_TEMPERATURE, QueuedWrite.ACTION_DELETE, id, knownServerId(id, event.serverId)))
    }

    /** Logs one measurement (kg or cm) on the day of [dateMillis]; returns its id (for undo). */
    fun logGrowth(kind: GrowthKind, value: Double, dateMillis: Long = System.currentTimeMillis(), notes: String = ""): Long {
        val event = GrowthEvent(
            id = nextLocalId(),
            kind = kind,
            dateMillis = parseLocalDateNoon(localDate(dateMillis)) ?: dateMillis,
            value = value,
            notes = notes,
            caregiver = myName()
        )
        _growth.update { (listOf(event) + it).sortedByDescending { e -> e.dateMillis } }
        enqueue(QueuedWrite(kind.endpoint, QueuedWrite.ACTION_CREATE, event.id, body = growthBody(event)))
        return event.id
    }

    // Each kind has its own server endpoint and ids, so a weight and a length can share an id: match on both.

    /** Saves a changed measurement (value, day or note); the day is kept as local noon like the server's date. */
    fun updateGrowth(updated: GrowthEvent) {
        val event = updated.copy(dateMillis = parseLocalDateNoon(localDate(updated.dateMillis)) ?: updated.dateMillis)
        _growth.update { list -> list.map { if (it.id == event.id && it.kind == event.kind) event else it }.sortedByDescending { it.dateMillis } }
        enqueue(QueuedWrite(event.kind.endpoint, QueuedWrite.ACTION_UPDATE, event.id, knownServerId(event.id, event.serverId), growthBody(event)))
    }

    fun deleteGrowth(kind: GrowthKind, id: Long) {
        val event = _growth.value.firstOrNull { it.id == id && it.kind == kind } ?: return
        _growth.update { list -> list.filterNot { it.id == id && it.kind == kind } }
        enqueue(QueuedWrite(event.kind.endpoint, QueuedWrite.ACTION_DELETE, id, knownServerId(id, event.serverId)))
    }

    fun addNote(text: String, tags: List<String> = emptyList(), timeMillis: Long = System.currentTimeMillis()): Long {
        val now = timeMillis
        val event = NoteEvent(id = nextLocalId(), timeMillis = now, timeFormatted = formatClock(now), text = text, tags = tags)
        _notes.update { (listOf(event) + it).sortedByDescending { e -> e.timeMillis } }
        val body = mapOf("note" to text, "time" to isoUtc(now), "tags" to tags.takeIf { it.isNotEmpty() })
        enqueue(QueuedWrite(ENDPOINT_NOTES, QueuedWrite.ACTION_CREATE, event.id, body = body))
        return event.id
    }

    // --- Baths: notes tagged BATH_TAG ---

    fun logBath(timeMillis: Long, notes: String): Long = addNote(notes.ifBlank { BATH_NOTE_TEXT }, listOf(BATH_TAG), timeMillis)

    /** Saves a changed note or bath (from today or an earlier day in History); its tags are kept. */
    fun updateNote(updated: NoteEvent) {
        val event = updated.copy(timeFormatted = formatClock(updated.timeMillis))
        _notes.update { list -> list.map { if (it.id == event.id) event else it }.sortedByDescending { it.timeMillis } }
        val body = mapOf("note" to event.text, "time" to isoUtc(event.timeMillis), "tags" to event.tags)
        enqueue(QueuedWrite(ENDPOINT_NOTES, QueuedWrite.ACTION_UPDATE, event.id, knownServerId(event.id, event.serverId), body))
    }

    /** Deletes a note or bath (from today or an earlier day in History). */
    fun deleteNoteEvent(note: NoteEvent) {
        _notes.update { list -> list.filterNot { it.id == note.id } }
        enqueue(QueuedWrite(ENDPOINT_NOTES, QueuedWrite.ACTION_DELETE, note.id, knownServerId(note.id, note.serverId)))
        // The server's "last bath" may have been this one
        if (BATH_TAG in note.tags && _lastBathRemote.value == note.timeMillis) _lastBathRemote.value = null
    }

    // --- Milk stash: the last amount someone entered (a note tagged "milk-stash", so every phone shares it)
    // plus pumped milk since then, minus breast-milk bottles since then ---

    private data class StashBase(val ml: Int, val sinceMillis: Long, val pumpedBeforeToday: Int, val bottlesBeforeToday: Int)

    private val _stashBase = MutableStateFlow<StashBase?>(null)

    /** Breast milk on hand in ml, or null until someone enters an amount. */
    val milkStash: StateFlow<Int?> by lazy {
        combine(_stashBase, _pumpings, _feedings) { base, pumps, feeds ->
            if (base == null) return@combine null
            // Today's part comes from the live lists, which include unsent entries
            val from = maxOf(base.sinceMillis, startOfTodayMillis())
            val pumped = pumps.filter { it.timeMillis >= from }.sumOf { it.amountMl }
            val given = feeds.filter { it.timeMillis >= from && isBreastMilkBottle(it) }.sumOf { it.amountMl ?: 0 }
            (base.ml + base.pumpedBeforeToday - base.bottlesBeforeToday + pumped - given).coerceAtLeast(0)
        }.stateIn(syncScope, SharingStarted.Eagerly, null)
    }

    private fun isBreastMilkBottle(f: FeedingEvent) = f.type == FeedType.BOTTLE && !f.bottleFormula && f.amountMl != null

    /** Records how much milk is on hand now; later pumping and bottles count from here. */
    fun setMilkStash(ml: Int): Long {
        _stashBase.value = StashBase(ml, System.currentTimeMillis(), 0, 0)
        return addNote("Milk stash: $ml ml", listOf(MILK_STASH_TAG))
    }

    fun undoMilkStash(noteId: Long) {
        deleteNote(noteId)
        _stashBase.value = null
        syncScope.launch {
            delay(5_000L) // let the delete reach the server, then use the previous amount
            loadMilkStash()
        }
    }

    /** Finds the latest stash amount on the server and totals the days between it and today. */
    suspend fun loadMilkStash() {
        val api = remoteApi ?: return
        val child = remoteChildId ?: return
        runCatching {
            val since = isoUtc(System.currentTimeMillis() - 180L * 24 * 60 * 60_000L)
            val note = api.getNotes(remoteAuthHeader, child, since = since, limit = 1000).body()!!.results
                .filter { MILK_STASH_TAG in it.tags.orEmpty() }
                .maxByOrNull { it.time?.let(::parseServerTime)?.time ?: 0L }
                ?: return@runCatching null
            val ml = Regex("""(\d+)""").find(note.note.orEmpty())?.value?.toIntOrNull() ?: return@runCatching null
            val sinceMillis = note.time?.let(::parseServerTime)?.time ?: return@runCatching null
            val todayStart = startOfTodayMillis()
            if (sinceMillis >= todayStart) return@runCatching StashBase(ml, sinceMillis, 0, 0)
            val from = isoUtc(sinceMillis)
            val pumped = api.getPumping(remoteAuthHeader, child, since = from, limit = 1000).body()!!.results
                .map(::mapPumping).filter { it.timeMillis in sinceMillis until todayStart }.sumOf { it.amountMl }
            val given = api.getFeedings(remoteAuthHeader, child, since = from, limit = 1000).body()!!.results
                .map(::mapFeeding).filter { it.timeMillis in sinceMillis until todayStart && isBreastMilkBottle(it) }
                .sumOf { it.amountMl ?: 0 }
            StashBase(ml, sinceMillis, pumped, given)
        }.onSuccess { base ->
            // Keep an amount entered on this phone that the server hasn't returned yet
            val local = _stashBase.value
            if (base != null && (local == null || base.sinceMillis >= local.sinceMillis - 60_000L)) _stashBase.value = base
        }
    }

    fun deleteNote(id: Long) {
        val event = _notes.value.firstOrNull { it.id == id } ?: return
        _notes.update { list -> list.filterNot { it.id == id } }
        enqueue(QueuedWrite(ENDPOINT_NOTES, QueuedWrite.ACTION_DELETE, id, knownServerId(id, event.serverId)))
    }

    fun updatePumping(updated: PumpingEvent) {
        _pumpings.update { list -> list.map { if (it.id == updated.id) updated else it }.sortedByDescending { it.timeMillis } }
        enqueue(QueuedWrite(ENDPOINT_PUMPING, QueuedWrite.ACTION_UPDATE, updated.id, knownServerId(updated.id, updated.serverId), pumpingBody(updated)))
    }

    fun deletePumping(id: Long) {
        val event = _pumpings.value.firstOrNull { it.id == id } ?: return
        _pumpings.update { list -> list.filterNot { it.id == id } }
        enqueue(QueuedWrite(ENDPOINT_PUMPING, QueuedWrite.ACTION_DELETE, id, knownServerId(id, event.serverId)))
    }

    /** Note on the running breastfeed, synced like any edit. */
    fun setLiveFeedNotes(notes: String) {
        val id = _liveTimerState.value.feedingLocalId ?: return
        val existing = _feedings.value.firstOrNull { it.id == id } ?: return
        val updated = existing.copy(notes = notes.trim())
        if (updated == existing) return
        _feedings.update { list -> list.map { if (it.id == id) updated else it } }
        enqueue(QueuedWrite(ENDPOINT_FEEDINGS, QueuedWrite.ACTION_UPDATE, id, knownServerId(id, updated.serverId), feedingBody(updated)))
    }

    /** The running breastfeed's note. */
    fun liveFeedUserNotes(): String {
        val id = _liveTimerState.value.feedingLocalId ?: return ""
        return _feedings.value.firstOrNull { it.id == id }?.notes.orEmpty()
    }

    private fun knownServerId(localId: Long, serverId: Long?): Long? = serverId ?: serverIds[localId]

    private fun formatClock(millis: Long): String = TimeFormat.clock(millis)

    /** Returns the new entry's id (for undo), or null if the category isn't known. */
    fun addQuickLog(category: String, amount: String? = null, note: String? = null): Long? {
        val nowFormatted = TimeFormat.clock(System.currentTimeMillis())
        return when (category.lowercase()) {
            "pee", "wet" -> {
                addDiaperLog(
                    DiaperEvent(
                        timeFormatted = nowFormatted,
                        type = DiaperType.WET,
                        amount = "",
                        consistency = "",
                        color = "",
                        skinBarrierApplied = emptyList(),
                        notes = note.orEmpty(),
                        imageUrl = null
                    )
                )
            }

            "poop", "dirty" -> {
                addDiaperLog(
                    DiaperEvent(
                        timeFormatted = nowFormatted,
                        type = DiaperType.DIRTY,
                        amount = "",
                        consistency = "",
                        color = "",
                        skinBarrierApplied = emptyList(),
                        notes = note.orEmpty(),
                        imageUrl = null
                    )
                )
            }

            "both" -> {
                addDiaperLog(
                    DiaperEvent(
                        timeFormatted = nowFormatted,
                        type = DiaperType.BOTH,
                        amount = "",
                        consistency = "",
                        color = "",
                        skinBarrierApplied = emptyList(),
                        notes = note.orEmpty(),
                        imageUrl = null
                    )
                )
            }

            "bottle" -> logBottle(amount?.toIntOrNull() ?: 120, formula = false)

            else -> null
        }
    }

    // --- Sleep: a timer on this phone, or a past sleep entered by hand ---

    /** Saves a finished sleep; returns its id (for undo). */
    fun logSleep(startMillis: Long, endMillis: Long, isNap: Boolean, notes: String): Long {
        val event = SleepEvent(
            id = nextLocalId(),
            timeMillis = startMillis,
            endMillis = endMillis.coerceAtLeast(startMillis),
            timeFormatted = formatClock(startMillis),
            isNap = isNap,
            notes = notes.trim(),
            caregiver = myName()
        )
        _sleeps.update { (listOf(event) + it).sortedByDescending { e -> e.timeMillis } }
        enqueue(QueuedWrite(ENDPOINT_SLEEP, QueuedWrite.ACTION_CREATE, event.id, body = sleepBody(event)))
        return event.id
    }

    fun updateSleep(updated: SleepEvent) {
        _sleeps.update { list -> list.map { if (it.id == updated.id) updated else it }.sortedByDescending { it.timeMillis } }
        enqueue(QueuedWrite(ENDPOINT_SLEEP, QueuedWrite.ACTION_UPDATE, updated.id, knownServerId(updated.id, updated.serverId), sleepBody(updated)))
    }

    fun deleteSleep(id: Long) {
        val event = _sleeps.value.firstOrNull { it.id == id } ?: return
        _sleeps.update { list -> list.filterNot { it.id == id } }
        enqueue(QueuedWrite(ENDPOINT_SLEEP, QueuedWrite.ACTION_DELETE, id, knownServerId(id, event.serverId)))
    }

    fun startSleep(startMillis: Long = System.currentTimeMillis()) {
        if (_sleepTimer.value.isRunning) return
        setSleepTimer(SleepTimerState(startMillis = startMillis, changedAtMillis = nextChangeMillis(_sleepTimer.value.changedAtMillis)))
        sleepTimerDirty.trySend(Unit)
    }

    /** Corrects when the running sleep started (never in the future). */
    fun setSleepStart(startMillis: Long) {
        val t = _sleepTimer.value
        if (!t.isRunning) return
        setSleepTimer(
            t.copy(
                startMillis = startMillis.coerceAtMost(System.currentTimeMillis()),
                changedAtMillis = nextChangeMillis(t.changedAtMillis)
            )
        )
        sleepTimerDirty.trySend(Unit)
    }

    fun setSleepNotes(notes: String) {
        val t = _sleepTimer.value
        if (!t.isRunning) return
        setSleepTimer(t.copy(notes = notes))
    }

    /** Ends the running sleep at [endMillis] and saves it; returns the entry id, or null if none was running. */
    fun stopSleep(endMillis: Long = System.currentTimeMillis(), isNap: Boolean? = null): Long? {
        val t = _sleepTimer.value
        if (!t.isRunning) return null
        sleepEnded()
        return logSleep(t.startMillis, endMillis, isNap ?: isNapTime(t.startMillis), t.notes)
    }

    fun cancelSleep() = sleepEnded()

    private fun sleepEnded() {
        setSleepTimer(SleepTimerState())
        sleepEndedAt = System.currentTimeMillis()
        prefs?.edit()?.putLong(PREF_SLEEP_ENDED_AT, sleepEndedAt)?.apply()
        sleepTimerDirty.trySend(Unit)
    }

    private fun setSleepTimer(state: SleepTimerState) {
        _sleepTimer.value = state
        prefs?.edit()
            ?.putLong(PREF_SLEEP_START, state.startMillis)
            ?.putString(PREF_SLEEP_NOTES, state.notes)
            ?.putLong(PREF_SLEEP_CHANGED, state.changedAtMillis)
            ?.apply()
    }

    // --- Shared timers: a running feed or sleep is mirrored to a Baby Buddy timer (see SharedTimers),
    // so every phone shows it and can continue or end it. Newest change wins. ---

    private var feedTimerRemoteId: Long? = null
    private var sleepTimerRemoteId: Long? = null
    private var feedEndedAt = 0L // a remote snapshot older than this is a feed already ended here
    private var sleepEndedAt = 0L
    private val feedTimerDirty = Channel<Unit>(Channel.CONFLATED)
    private val sleepTimerDirty = Channel<Unit>(Channel.CONFLATED)
    private val timerMutex = Mutex()

    /** A change time later than now and than the last known change, so it wins even if this phone's clock is behind. */
    private fun nextChangeMillis(previous: Long): Long = maxOf(System.currentTimeMillis(), previous + 1)

    private fun feedChanged() {
        _liveTimerState.update { it.copy(changedAtMillis = nextChangeMillis(it.changedAtMillis)) }
        feedTimerDirty.trySend(Unit)
    }

    private fun setFeedTimerRemoteId(id: Long?) {
        feedTimerRemoteId = id
        prefs?.edit()?.putLong(PREF_FEED_TIMER_ID, id ?: -1L)?.apply()
    }

    private fun setSleepTimerRemoteId(id: Long?) {
        sleepTimerRemoteId = id
        prefs?.edit()?.putLong(PREF_SLEEP_TIMER_ID, id ?: -1L)?.apply()
    }

    private fun startTimerPushers() {
        syncScope.launch { for (signal in feedTimerDirty) timerMutex.withLock { pushFeedTimer() } }
        syncScope.launch { for (signal in sleepTimerDirty) timerMutex.withLock { pushSleepTimer() } }
    }

    private fun retryTimerPush(channel: Channel<Unit>) {
        syncScope.launch {
            delay(15_000L)
            channel.trySend(Unit)
        }
    }

    private fun feedSnapshot(t: LiveTimerState) = FeedSnapshot(
        feedingServerId = t.feedingServerId ?: t.feedingLocalId?.let { id ->
            serverIds[id] ?: _feedings.value.firstOrNull { it.id == id }?.serverId
        },
        side = if (t.activeBreast == BreastSide.RIGHT) BreastSide.RIGHT else BreastSide.LEFT,
        paused = t.isPaused,
        leftSeconds = t.leftSeconds,
        rightSeconds = t.rightSeconds,
        firstSide = t.firstSide,
        startMillis = t.sessionStartMillis,
        pausedAtMillis = t.pausedAtMillis,
        lastActiveMillis = t.lastActiveMillis,
        changedAtMillis = t.changedAtMillis
    )

    private suspend fun pushFeedTimer() {
        val t = _liveTimerState.value
        val running = t.isRunning && t.feedingLocalId != null
        pushTimer(
            name = if (running) SharedTimers.encodeFeed(feedSnapshot(t)) else null,
            startMillis = t.sessionStartMillis,
            remoteId = feedTimerRemoteId,
            isOurs = { SharedTimers.decodeFeed(it) != null },
            setRemoteId = ::setFeedTimerRemoteId,
            channel = feedTimerDirty,
            onGone = { feedEndedElsewhere(System.currentTimeMillis()) }
        )
    }

    private suspend fun pushSleepTimer() {
        val t = _sleepTimer.value
        pushTimer(
            name = if (t.isRunning) SharedTimers.encodeSleep(SleepSnapshot(t.startMillis, t.changedAtMillis)) else null,
            startMillis = t.startMillis,
            remoteId = sleepTimerRemoteId,
            isOurs = { SharedTimers.decodeSleep(it) != null },
            setRemoteId = ::setSleepTimerRemoteId,
            channel = sleepTimerDirty,
            onGone = { sleepEndedElsewhere(System.currentTimeMillis()) }
        )
    }

    /** Creates/renames the server timer for [name], or deletes it when [name] is null (the feed/sleep ended). */
    private suspend fun pushTimer(
        name: String?,
        startMillis: Long,
        remoteId: Long?,
        isOurs: (String?) -> Boolean,
        setRemoteId: (Long?) -> Unit,
        channel: Channel<Unit>,
        onGone: () -> Unit
    ) {
        val api = remoteApi ?: return
        val child = remoteChildId ?: return
        val auth = remoteAuthHeader
        try {
            if (name == null) {
                val id = remoteId ?: return
                val response = api.deleteRecord(auth, ENDPOINT_TIMERS, id)
                when {
                    response.isSuccessful || response.code() == 404 -> setRemoteId(null)
                    response.code() >= 500 -> retryTimerPush(channel)
                }
                return
            }
            // Reuse our timer, or one another phone made, before creating a new one
            val id = remoteId ?: api.getTimers(auth, child).body()?.results.orEmpty()
                .firstOrNull { isOurs(it.name) }?.id
            if (id != null) {
                val response = api.updateRecord(auth, ENDPOINT_TIMERS, id, mapOf("name" to name))
                if (response.isSuccessful) {
                    setRemoteId(id)
                    return
                }
                if (response.code() >= 500) {
                    retryTimerPush(channel)
                    return
                }
                if (response.code() != 404) return
                if (id == remoteId) {
                    // Our timer was deleted: the feed/sleep was ended on another phone. Recreating it would bring
                    // it back on every phone, so end it here too and fetch the finished entry.
                    onGone()
                    syncToday(api, auth, child)
                    return
                }
            }
            // The server rejects starts in the future, so allow for this phone's clock being ahead
            val now = System.currentTimeMillis()
            val start = minOf(startMillis.takeIf { it > 0 } ?: now, now - 60_000L)
            val response = api.createRecord(auth, ENDPOINT_TIMERS, mapOf("child" to child, "name" to name, "start" to isoUtc(start)))
            if (response.isSuccessful) {
                setRemoteId((response.body()?.get("id") as? Number)?.toLong())
            } else if (response.code() >= 500) {
                retryTimerPush(channel)
            }
        } catch (e: IOException) {
            retryTimerPush(channel)
        } catch (e: Exception) {
            // Malformed reply; the next change pushes again
        }
    }

    /**
     * Picks up feeds and sleeps started, changed or ended on other phones. Called on refresh and every
     * few seconds while the app is open.
     */
    suspend fun pollSharedTimers() {
        val api = remoteApi ?: return
        val child = remoteChildId ?: return
        val auth = remoteAuthHeader
        val timers = try {
            api.getTimers(auth, child).takeIf { it.isSuccessful }?.body()?.results
        } catch (e: Exception) {
            null
        } ?: return
        val feed = timers.firstNotNullOfOrNull { dto -> SharedTimers.decodeFeed(dto.name)?.let { dto.id to it } }
        val sleep = timers.firstNotNullOfOrNull { dto -> SharedTimers.decodeSleep(dto.name)?.let { dto.id to it } }

        // A feed started elsewhere: make sure its record is in the local list so taps here update it
        val feedServerId = feed?.second?.feedingServerId
        if (feedServerId != null && _feedings.value.none { it.serverId == feedServerId }) {
            syncToday(api, auth, child)
        }

        var endedElsewhere = false
        timerMutex.withLock {
            val now = System.currentTimeMillis()
            val local = _liveTimerState.value
            if (feed == null) {
                if (local.isRunning && feedTimerRemoteId != null) {
                    feedEndedElsewhere(now)
                    endedElsewhere = true
                }
            } else {
                val (remoteId, snap) = feed
                val fresh = now - snap.changedAtMillis < 6 * 60 * 60_000L // ignore timers abandoned long ago
                if (fresh && snap.changedAtMillis > local.changedAtMillis && snap.changedAtMillis > feedEndedAt) {
                    adoptFeed(remoteId, snap, now)
                } else if (local.isRunning && snap.feedingServerId != null &&
                    _feedings.value.none { it.id == local.feedingLocalId }
                ) {
                    // Adopted before its record arrived: link to it now
                    _feedings.value.firstOrNull { it.serverId == snap.feedingServerId }?.let { record ->
                        _liveTimerState.update { it.copy(feedingLocalId = record.id, feedingServerId = record.serverId) }
                    }
                }
            }

            val localSleep = _sleepTimer.value
            if (sleep == null) {
                if (localSleep.isRunning && sleepTimerRemoteId != null) {
                    sleepEndedElsewhere(now)
                    endedElsewhere = true
                }
            } else {
                val (remoteId, snap) = sleep
                val fresh = now - snap.changedAtMillis < 24 * 60 * 60_000L
                if (fresh && snap.changedAtMillis > localSleep.changedAtMillis && snap.changedAtMillis > sleepEndedAt) {
                    setSleepTimer(
                        SleepTimerState(
                            startMillis = snap.startMillis,
                            notes = if (localSleep.startMillis == snap.startMillis) localSleep.notes else "",
                            changedAtMillis = snap.changedAtMillis
                        )
                    )
                    setSleepTimerRemoteId(remoteId)
                }
            }
        }
        if (endedElsewhere) syncToday(api, auth, child) // fetch the finished feed/sleep
    }

    /**
     * The feed's shared timer is gone: another phone ended it and saved its final times. Stop here without
     * writing anything, and drop this phone's queued minute-saves, which would overwrite those times.
     */
    private fun feedEndedElsewhere(now: Long) {
        val local = _liveTimerState.value
        local.feedingLocalId?.let { dropQueuedUpdates(ENDPOINT_FEEDINGS, it) }
        setFeedTimerRemoteId(null)
        feedEndedAt = now
        _liveTimerState.value = LiveTimerState(softChimeEnabled = local.softChimeEnabled)
    }

    /** The sleep's shared timer is gone: it was stopped (and saved) on another phone. */
    private fun sleepEndedElsewhere(now: Long) {
        setSleepTimerRemoteId(null)
        setSleepTimer(SleepTimerState())
        sleepEndedAt = now
    }

    /** Drops queued, unsent edits of one entry (the head of the queue may be in flight, so it stays). */
    private fun dropQueuedUpdates(endpoint: String, localId: Long) = synchronized(pendingWrites) {
        val head = pendingWrites.firstOrNull()
        val dropped = pendingWrites.removeAll {
            it !== head && it.endpoint == endpoint && it.localId == localId && it.action == QueuedWrite.ACTION_UPDATE
        }
        if (dropped) queueChanged()
    }

    /** Takes over a feed from another phone, counting the running side forward from the snapshot. */
    private fun adoptFeed(remoteId: Long?, snap: FeedSnapshot, now: Long) {
        val local = _liveTimerState.value
        val elapsed = if (snap.paused) 0 else ((now - snap.changedAtMillis) / 1000L).toInt().coerceAtLeast(0)
        val record = snap.feedingServerId?.let { sid -> _feedings.value.firstOrNull { it.serverId == sid } }
        _liveTimerState.value = LiveTimerState(
            isRunning = true,
            isPaused = snap.paused,
            activeBreast = snap.side,
            leftSeconds = snap.leftSeconds + if (snap.side == BreastSide.LEFT) elapsed else 0,
            rightSeconds = snap.rightSeconds + if (snap.side == BreastSide.RIGHT) elapsed else 0,
            sessionStartedTime = formatClock(snap.startMillis),
            softChimeEnabled = local.softChimeEnabled,
            // Without the record yet, a placeholder id; it's linked on a later poll
            feedingLocalId = record?.id ?: local.feedingLocalId?.takeIf { local.isRunning } ?: nextLocalId(),
            sessionStartMillis = snap.startMillis,
            pausedAtMillis = snap.pausedAtMillis,
            firstSide = snap.firstSide,
            lastActiveMillis = snap.lastActiveMillis,
            editCount = local.editCount + 1,
            changedAtMillis = snap.changedAtMillis,
            feedingServerId = snap.feedingServerId
        )
        setFeedTimerRemoteId(remoteId)
    }

    private fun clearRemote() {
        flushJob?.cancel()
        remoteApi = null
        remoteAuthHeader = ""
        remoteChildId = null
        // Unsent entries belong to the server being left
        synchronized(pendingWrites) {
            if (pendingWrites.isNotEmpty()) {
                _syncErrors.tryEmit(AppText.plural(R.plurals.sync_discarded, pendingWrites.size, pendingWrites.size))
            }
            pendingWrites.clear()
            queueChanged()
        }
        store?.clear()
    }

    // --- Request bodies (without "child", which is added when sending) ---

    private fun feedingBody(e: FeedingEvent): Map<String, Any?> {
        val start = isoUtc(e.timeMillis)
        return if (e.type == FeedType.NURSING) {
            val method = when {
                e.leftDurationSec > 0 && e.rightDurationSec > 0 -> "both breasts"
                e.rightDurationSec > 0 -> "right breast"
                e.leftDurationSec > 0 -> "left breast"
                e.side == BreastSide.RIGHT -> "right breast"
                else -> "left breast"
            }
            mapOf(
                "start" to start,
                "end" to isoUtc(e.timeMillis + (e.leftDurationSec + e.rightDurationSec) * 1000L),
                "type" to "breast milk",
                "method" to method,
                "notes" to composeFeedingNotes(e)
            )
        } else {
            // Bottles are instantaneous on this server (start == end)
            mapOf(
                "start" to start,
                "end" to start,
                "type" to if (e.bottleFormula) "formula" else "breast milk",
                "method" to "bottle",
                "amount" to e.amountMl?.toDouble(),
                "notes" to composeFeedingNotes(e)
            )
        }
    }

    private fun changeBody(e: DiaperEvent): Map<String, Any?> {
        val solid = e.type == DiaperType.DIRTY || e.type == DiaperType.BOTH
        return mapOf(
            "time" to isoUtc(e.timeMillis),
            "wet" to (e.type == DiaperType.WET || e.type == DiaperType.BOTH),
            "solid" to solid,
            "color" to if (solid) serverDiaperColor(e.color) else "",
            "notes" to withCaregiver(composeDiaperNotes(e), e.caregiver)
        )
    }

    private fun pumpingBody(e: PumpingEvent): Map<String, Any?> = mapOf(
        "start" to isoUtc(e.timeMillis),
        "end" to isoUtc(e.timeMillis + e.durationMinutes * 60_000L),
        "amount" to e.amountMl.toDouble(),
        "notes" to withCaregiver(e.notes, e.caregiver)
    )

    private fun temperatureBody(e: TemperatureEvent): Map<String, Any?> = mapOf(
        "time" to isoUtc(e.timeMillis),
        "temperature" to e.temperature,
        "notes" to withCaregiver(e.notes, e.caregiver)
    )

    private fun growthBody(e: GrowthEvent): Map<String, Any?> = mapOf(
        "date" to localDate(e.dateMillis),
        e.kind.field to e.value,
        "notes" to withCaregiver(e.notes, e.caregiver)
    )

    private fun sleepBody(e: SleepEvent): Map<String, Any?> = mapOf(
        "start" to isoUtc(e.timeMillis),
        "end" to isoUtc(e.endMillis),
        "nap" to e.isNap,
        "notes" to withCaregiver(e.notes, e.caregiver)
    )

    private fun tummyBody(e: TummyTimeEvent): Map<String, Any?> = mapOf(
        "start" to isoUtc(e.timeMillis),
        "end" to isoUtc(e.timeMillis + e.durationMinutes * 60_000L),
        "milestone" to e.milestone,
        "notes" to withCaregiver("", e.caregiver)
    )

    // --- Queue ---

    /** Adds a write to the queue (and disk) and starts sending; no-op in demo/offline mode. */
    private fun enqueue(write: QueuedWrite) {
        val config = _connectionConfig.value
        if (remoteApi == null || !config.isConnected || config.isDemoMode) return
        synchronized(pendingWrites) {
            // A newer edit of the same entry replaces a queued one that hasn't been sent yet (index 0 may be in flight)
            val stale = if (write.action == QueuedWrite.ACTION_UPDATE) {
                pendingWrites.indexOfLast {
                    it.endpoint == write.endpoint && it.localId == write.localId && it.action == QueuedWrite.ACTION_UPDATE
                }.takeIf { it > 0 && it == pendingWrites.lastIndex }
            } else {
                null
            }
            if (stale != null) {
                pendingWrites[stale] = write.copy(serverId = write.serverId ?: pendingWrites[stale].serverId)
            } else {
                pendingWrites.add(write)
            }
            queueChanged()
        }
        flushPendingWrites()
    }

    /** Call while holding the pendingWrites lock. */
    private fun queueChanged() {
        val snapshot = pendingWrites.toList()
        pendingSnapshot = snapshot
        _pendingWriteCount.value = snapshot.size
        store?.saveQueue(snapshot)
    }

    private fun removeHead(write: QueuedWrite) = synchronized(pendingWrites) {
        val head = pendingWrites.firstOrNull()
        if (head != null && head.endpoint == write.endpoint && head.action == write.action && head.localId == write.localId) {
            pendingWrites.removeAt(0)
            queueChanged()
        }
    }

    private enum class WriteResult { SAVED, REJECTED, RETRY, PROXY_RETRY, NEEDS_LOGIN }

    /** Sends queued writes in order. A write that fails on network/server errors blocks the queue and is retried with backoff. */
    private fun flushPendingWrites() {
        if (flushJob?.isActive == true) return
        flushJob = syncScope.launch {
            while (true) {
                val api = remoteApi ?: break
                val child = remoteChildId ?: break
                val auth = remoteAuthHeader
                val write = synchronized(pendingWrites) { pendingWrites.firstOrNull() } ?: break

                when (val result = attemptWrite(write, api, auth, child)) {
                    WriteResult.SAVED, WriteResult.REJECTED -> removeHead(write)

                    WriteResult.RETRY, WriteResult.PROXY_RETRY -> {
                        val attempts = write.attempts + 1
                        synchronized(pendingWrites) {
                            if (pendingWrites.firstOrNull() == write) {
                                pendingWrites[0] = write.copy(attempts = attempts)
                                queueChanged()
                            }
                        }
                        if (attempts == 1) {
                            _syncErrors.tryEmit(
                                if (result == WriteResult.PROXY_RETRY) AppText.get(R.string.sync_proxy_rejected, describe(write)) else AppText.get(R.string.sync_unreachable)
                            )
                        }
                        // 5s, 10s, 20s ... capped at 5 min
                        delay(minOf(5_000L shl minOf(attempts - 1, 6), 300_000L))
                    }

                    WriteResult.NEEDS_LOGIN -> {
                        _syncErrors.tryEmit(AppText.plural(R.plurals.sync_token_rejected, _pendingWriteCount.value, _pendingWriteCount.value))
                        break
                    }
                }
            }
        }
    }

    private suspend fun attemptWrite(write: QueuedWrite, api: BabyBuddyApiService, auth: String, child: Int): WriteResult {
        return try {
            when (write.action) {
                QueuedWrite.ACTION_CREATE -> {
                    if (write.attempts > 0) {
                        // A previous attempt may have reached the server even though we never saw the response
                        findExisting(write, api, auth, child)?.let { id ->
                            onCreated(write, id)
                            return WriteResult.SAVED
                        }
                    }
                    val response = api.createRecord(auth, write.endpoint, sendable(write.body) + ("child" to child))
                    classify(response, write) { onCreated(write, (response.body()?.get("id") as? Number)?.toLong()) }
                }

                QueuedWrite.ACTION_UPDATE -> {
                    // No server id: the entry never reached the server, so there is nothing to change there
                    val id = resolveServerId(write) ?: return WriteResult.SAVED
                    classify(api.updateRecord(auth, write.endpoint, id, sendable(write.body)), write) {}
                }

                QueuedWrite.ACTION_DELETE -> {
                    val id = resolveServerId(write) ?: return WriteResult.SAVED
                    val response = api.deleteRecord(auth, write.endpoint, id)
                    if (response.code() == 404) WriteResult.SAVED else classify(response, write) {}
                }

                else -> WriteResult.REJECTED
            }
        } catch (e: IOException) {
            WriteResult.RETRY
        } catch (e: Exception) {
            _syncErrors.tryEmit(AppText.get(R.string.sync_could_not_save, describe(write), "${e.localizedMessage}"))
            WriteResult.REJECTED
        }
    }

    private inline fun classify(response: Response<*>, write: QueuedWrite, onSuccess: () -> Unit): WriteResult = when {
        response.isSuccessful -> {
            onSuccess()
            WriteResult.SAVED
        }

        response.code() == 401 || response.code() == 403 -> WriteResult.NEEDS_LOGIN

        response.code() == 408 || response.code() == 429 || response.code() >= 500 -> WriteResult.RETRY

        else -> {
            val detail = response.errorBody()?.string().orEmpty()
            if (!isApiReply(response, detail) && write.attempts + 1 < PROXY_RETRY_ATTEMPTS) {
                // An HTML error page comes from a proxy in front of Baby Buddy (Cloudflare, nginx…), not from the
                // server's validation, so the request may well go through next time
                WriteResult.PROXY_RETRY
            } else {
                // Validation errors won't fix themselves on retry
                _syncErrors.tryEmit(AppText.get(R.string.sync_server_rejected, describe(write), response.code(), detail.take(160)).trim())
                WriteResult.REJECTED
            }
        }
    }

    /** Baby Buddy's API answers errors in JSON; anything else was made by something in between. */
    private fun isApiReply(response: Response<*>, body: String): Boolean = response.errorBody()?.contentType()?.subtype == "json" || body.trimStart().let { it.startsWith("{") || it.startsWith("[") }

    @Suppress("UNCHECKED_CAST")
    private fun sendable(body: Map<String, Any?>): Map<String, Any> = body.filterValues { it != null } as Map<String, Any>

    private fun describe(write: QueuedWrite): String {
        val what = when (write.endpoint) {
            ENDPOINT_FEEDINGS -> AppText.get(R.string.write_feeding)
            ENDPOINT_CHANGES -> AppText.get(R.string.write_diaper_change)
            ENDPOINT_PUMPING -> AppText.get(R.string.write_pumping)
            ENDPOINT_TEMPERATURE -> AppText.get(R.string.write_temperature)
            ENDPOINT_NOTES -> AppText.get(R.string.write_note)
            ENDPOINT_SLEEP -> AppText.get(R.string.write_sleep)
            in GROWTH_ENDPOINTS -> GrowthKind.forEndpoint(write.endpoint)!!.label.lowercase()
            else -> AppText.get(R.string.write_tummy_time)
        }
        return when (write.action) {
            QueuedWrite.ACTION_UPDATE -> AppText.get(R.string.write_edit, what)
            QueuedWrite.ACTION_DELETE -> AppText.get(R.string.write_delete, what)
            else -> what
        }
    }

    private fun resolveServerId(write: QueuedWrite): Long? {
        write.serverId?.let { return it }
        serverIds[write.localId]?.let { return it }
        return when (write.endpoint) {
            ENDPOINT_FEEDINGS -> _feedings.value.firstOrNull { it.id == write.localId }?.serverId
            ENDPOINT_CHANGES -> _diapers.value.firstOrNull { it.id == write.localId }?.serverId
            ENDPOINT_PUMPING -> _pumpings.value.firstOrNull { it.id == write.localId }?.serverId
            ENDPOINT_TEMPERATURE -> _temperatures.value.firstOrNull { it.id == write.localId }?.serverId
            ENDPOINT_NOTES -> _notes.value.firstOrNull { it.id == write.localId }?.serverId
            ENDPOINT_SLEEP -> _sleeps.value.firstOrNull { it.id == write.localId }?.serverId
            in GROWTH_ENDPOINTS -> _growth.value.firstOrNull { it.id == write.localId && it.kind.endpoint == write.endpoint }?.serverId
            else -> _tummyTimes.value.firstOrNull { it.id == write.localId }?.serverId
        }
    }

    /** Looks for a record a lost-response create may already have made, by its exact start/time. */
    private suspend fun findExisting(write: QueuedWrite, api: BabyBuddyApiService, auth: String, child: Int): Long? {
        val body = write.body
        return when (write.endpoint) {
            ENDPOINT_FEEDINGS -> {
                val start = body["start"] as? String ?: return null
                api.getFeedings(auth, child, since = start, until = start).body()?.results.orEmpty()
                    .firstOrNull { it.method == body["method"] }?.id
            }

            ENDPOINT_CHANGES -> {
                val time = body["time"] as? String ?: return null
                api.getChanges(auth, child, since = time, until = time).body()?.results.orEmpty()
                    .firstOrNull { it.wet == body["wet"] && it.solid == body["solid"] }?.id
            }

            ENDPOINT_PUMPING -> {
                val start = body["start"] as? String ?: return null
                api.getPumping(auth, child, since = start, until = start).body()?.results.orEmpty().firstOrNull()?.id
            }

            ENDPOINT_TEMPERATURE -> {
                val time = body["time"] as? String ?: return null
                api.getTemperatures(auth, child, since = time, until = time).body()?.results.orEmpty().firstOrNull()?.id
            }

            ENDPOINT_NOTES -> {
                val time = body["time"] as? String ?: return null
                api.getNotes(auth, child, since = time, until = time).body()?.results.orEmpty()
                    .firstOrNull { it.note == body["note"] }?.id
            }

            ENDPOINT_SLEEP -> {
                val start = body["start"] as? String ?: return null
                api.getSleep(auth, child, since = start, until = start).body()?.results.orEmpty().firstOrNull()?.id
            }

            in GROWTH_ENDPOINTS -> {
                val kind = GrowthKind.forEndpoint(write.endpoint) ?: return null
                val date = body["date"] as? String ?: return null
                api.getGrowth(auth, kind.endpoint, child, date = date).body()?.results.orEmpty()
                    .firstOrNull { growthValue(kind, it) == body[kind.field] }?.id
            }

            else -> {
                val start = body["start"] as? String ?: return null
                api.getTummyTimes(auth, child, since = start, until = start).body()?.results.orEmpty().firstOrNull()?.id
            }
        }
    }

    /** Records the new server id on the local entry and on any queued edits/deletes of it. */
    private fun onCreated(write: QueuedWrite, serverId: Long?) {
        if (serverId == null) return
        serverIds[write.localId] = serverId
        if (write.endpoint == ENDPOINT_FEEDINGS && write.localId == _liveTimerState.value.feedingLocalId) {
            // Other phones following this feed can now update its record
            _liveTimerState.update { it.copy(feedingServerId = serverId) }
            feedTimerDirty.trySend(Unit)
        }
        when (write.endpoint) {
            ENDPOINT_FEEDINGS -> _feedings.update { l -> l.map { if (it.id == write.localId) it.copy(serverId = serverId) else it } }
            ENDPOINT_CHANGES -> _diapers.update { l -> l.map { if (it.id == write.localId) it.copy(serverId = serverId) else it } }
            ENDPOINT_PUMPING -> _pumpings.update { l -> l.map { if (it.id == write.localId) it.copy(serverId = serverId) else it } }
            ENDPOINT_TEMPERATURE -> _temperatures.update { l -> l.map { if (it.id == write.localId) it.copy(serverId = serverId) else it } }
            ENDPOINT_NOTES -> _notes.update { l -> l.map { if (it.id == write.localId) it.copy(serverId = serverId) else it } }
            ENDPOINT_SLEEP -> _sleeps.update { l -> l.map { if (it.id == write.localId) it.copy(serverId = serverId) else it } }
            in GROWTH_ENDPOINTS -> _growth.update { l -> l.map { if (it.id == write.localId && it.kind.endpoint == write.endpoint) it.copy(serverId = serverId) else it } }
            else -> _tummyTimes.update { l -> l.map { if (it.id == write.localId) it.copy(serverId = serverId) else it } }
        }
        synchronized(pendingWrites) {
            var changed = false
            pendingWrites.replaceAll { w ->
                if (w.endpoint == write.endpoint && w.localId == write.localId && w.serverId == null && w.action != QueuedWrite.ACTION_CREATE) {
                    changed = true
                    w.copy(serverId = serverId)
                } else {
                    w
                }
            }
            if (changed) queueChanged()
        }
    }

    private fun isoUtc(millis: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(millis))

    // --- Process-level setup: persistence, timer, widgets ---

    private var prefs: SharedPreferences? = null
    private var modelPrefs: SharedPreferences? = null

    /** Called once from the Application: restores the saved login and running timer, starts the timer. */
    fun attach(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("baby_buddy", Context.MODE_PRIVATE)
        prefs = p
        p.edit().remove(LEGACY_PREF_TOKEN).apply() // earlier builds stored the token in plain text
        _caregiverName.value = p.getString(PREF_CAREGIVER, "").orEmpty()
        _feedNotificationEnabled.value = p.getBoolean(PREF_FEED_NOTIFICATION, true)
        _nextUpNotification.value = p.getBoolean(PREF_NEXT_UP_NOTIFICATION, false)
        _use24h.value = p.getBoolean(PREF_USE_24H, true)
        _modelsTab.value = p.getBoolean(PREF_MODELS_TAB, false)
        val mp = context.applicationContext.getSharedPreferences(MODEL_PREFS, Context.MODE_PRIVATE)
        modelPrefs = mp
        _usedModels.value = mp.all.values.mapNotNull { (it as? String)?.let(SavedRhythmModel::fromText) }.associateBy { it.target }
        TimeFormat.use24h = _use24h.value
        TimeFormat.useWheel = p.getBoolean(PREF_WHEEL_INPUT, false)
        syncScope.launch {
            // Remember the wheel/keyboard choice made in any picker
            snapshotFlow { TimeFormat.useWheel }.collect { p.edit().putBoolean(PREF_WHEEL_INPUT, it).apply() }
        }
        _startSide.value = p.getString(PREF_START_SIDE, null)?.let { runCatching { StartSide.valueOf(it) }.getOrNull() } ?: StartSide.OPPOSITE
        _themeMode.value = p.getString(PREF_THEME_MODE, null)
            ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: ThemeMode.BY_TIME
        p.getString(PREF_QUICK_ACTIONS, null)?.let { saved ->
            _quickActions.value = saved.split(",").filter { it.isNotBlank() }
                .mapNotNull { name -> QuickAction.entries.firstOrNull { it.name == name } }
        }
        val offline = OfflineStore(File(context.applicationContext.filesDir, "offline"))
        store = offline
        restoreTimer(p)
        p.getLong(PREF_SLEEP_START, 0L).takeIf { it > 0 }?.let { start ->
            _sleepTimer.value = SleepTimerState(start, p.getString(PREF_SLEEP_NOTES, "").orEmpty(), p.getLong(PREF_SLEEP_CHANGED, 0L))
        }
        feedTimerRemoteId = p.getLong(PREF_FEED_TIMER_ID, -1L).takeIf { it >= 0 }
        sleepTimerRemoteId = p.getLong(PREF_SLEEP_TIMER_ID, -1L).takeIf { it >= 0 }
        feedEndedAt = p.getLong(PREF_FEED_ENDED_AT, 0L)
        sleepEndedAt = p.getLong(PREF_SLEEP_ENDED_AT, 0L)
        _reminders.value = ReminderSettings(
            p.getInt(PREF_REMIND_FEED, 0),
            p.getInt(PREF_REMIND_AWAKE, 0),
            p.getInt(PREF_REMIND_SLEEP, 0),
            p.getInt(PREF_REMIND_DIAPER, 0),
            p.getInt(PREF_REMIND_EARLY, 0)
        )
        _babySex.value = p.getString(PREF_BABY_SEX, null)?.let { name -> BabySex.entries.firstOrNull { it.name == name } }
        _hiddenItems.value = p.getString(PREF_HIDDEN_ITEMS, null).orEmpty().split(",")
            .mapNotNull { name -> TrackedItem.entries.firstOrNull { it.name == name } }.toSet()
        _hiddenTodayTiles.value = p.getString(PREF_HIDDEN_SUMMARY_TILES, null).orEmpty().split(",")
            .mapNotNull { name -> TodayTile.entries.firstOrNull { it.name == name } }.toSet()
        _hiddenWakeWindowParts.value = p.getString(PREF_HIDDEN_WAKE_PARTS, null).orEmpty().split(",")
            .mapNotNull { name -> WakeWindowPart.entries.firstOrNull { it.name == name } }.toSet()
        p.getString(PREF_DASHBOARD_WIDGETS, null)?.let { saved ->
            val enabled = saved.split(",").map { if (it == "WEIGHT") "GROWTH" else it }.toSet()
            // Cards added since the choice was saved keep their default (saves from before this list knew only these four)
            val known = p.getString(PREF_DASHBOARD_WIDGETS_KNOWN, null)?.split(",")?.toSet()
                ?: setOf("TUMMY_TIME_GOAL", "HEALTH_VITALS", "CAREGIVER_NOTES", "GROWTH")
            _dashboardWidgets.update { list ->
                list.map { if (it.type.name in known) it.copy(isEnabled = it.type.name in enabled) else it }
            }
        }
        startTimerPushers()
        startTimerTicker()
        syncScope.launch {
            // Save the timer whenever it starts/stops/switches; the ticks in between are recomputed from the clock
            _liveTimerState.map { listOf(it.isRunning, it.isPaused, it.activeBreast, it.editCount) }
                .distinctUntilChanged()
                .collect { saveTimer() }
        }
        if (restoreConnection(p)) {
            restoreOffline(offline)
            reformatTimes() // the cache may have been saved with the other clock format
            flushPendingWrites() // retries on its own if the server isn't reachable yet
            syncScope.launch {
                val c = _connectionConfig.value
                val result = testConnection(c.serverUrl, restoredToken.orEmpty(), c.allowSelfSignedCert, c.cfClientId, c.cfClientSecret, c.pinnedCertSha256)
                // Logins saved before pinning trusted every certificate: keep the server's current one rather than locking them out
                val untrusted = result.exceptionOrNull() as? UntrustedCertificateException
                if (untrusted != null && c.allowSelfSignedCert && c.pinnedCertSha256.isBlank()) {
                    testConnection(c.serverUrl, restoredToken.orEmpty(), true, c.cfClientId, c.cfClientSecret, untrusted.fingerprint)
                }
            }
        } else {
            offline.clear()
        }
        syncScope.launch {
            // Keep today's entries on disk so they (and unsent ones) show offline after a restart
            combine(
                combine(_feedings, _diapers, _tummyTimes) { f, d, t -> Triple(f, d, t) },
                combine(_pumpings, _temperatures, _notes) { p, temp, n -> Triple(p, temp, n) },
                _sleeps
            ) { (f, d, t), (p, temp, n), s -> TodayCache(todayKey(), f, d, t, p, temp, n, s) }.collectLatest { cache ->
                delay(500)
                val config = _connectionConfig.value
                if (remoteApi != null && config.isConnected && !config.isDemoMode) {
                    offline.saveCache(cache)
                }
            }
        }
    }

    private var restoredToken: String? = null

    private fun restoreOffline(offline: OfflineStore) {
        synchronized(pendingWrites) {
            pendingWrites.clear()
            pendingWrites.addAll(offline.loadQueue())
            queueChanged()
        }
        val cache = offline.loadCache() ?: return
        if (cache.day != todayKey()) return
        val startOfDay = startOfTodayMillis()
        _feedings.value = cache.feedings.filter { it.timeMillis >= startOfDay }
        _diapers.value = cache.diapers.filter { it.timeMillis >= startOfDay }
        _tummyTimes.value = cache.tummyTimes.filter { it.timeMillis >= startOfDay }
        _pumpings.value = cache.pumpings.filter { it.timeMillis >= startOfDay }
        _temperatures.value = cache.temperatures.filter { it.timeMillis >= startOfDay }
        _notes.value = cache.notes.filter { it.timeMillis >= startOfDay }
        _sleeps.value = cache.sleeps.filter { it.endMillis >= startOfDay }
    }

    private fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun startOfTodayMillis(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Emits whenever something home-screen widgets show changes (not on every timer tick). */
    val widgetState: Flow<Any> = combine(
        combine(_feedings, lastBreastfeed) { feeds, last -> feeds to last },
        _diapers,
        _tummyTimes,
        _babyProfile,
        combine(
            _liveTimerState.map { listOf(it.isRunning, it.isPaused, it.activeBreast, it.editCount) }.distinctUntilChanged(),
            modelNextTimes
        ) { timer, next -> timer to next }
    ) { feeds, diapers, tummies, profile, timer -> listOf(feeds, diapers, tummies, profile, timer) }

    /** Restores the saved login; returns false if there is none (or the token can't be decrypted). */
    private fun restoreConnection(p: SharedPreferences): Boolean {
        if (p.getBoolean(PREF_DEMO_MODE, false)) {
            _caregiverName.value = p.getString(PREF_CAREGIVER, "Sarah").orEmpty().ifBlank { "Sarah" }
            _connectionConfig.value = ServerConnectionConfig(
                serverUrl = "demo.babybuddy.local",
                apiToken = "demo-token",
                isConnected = true,
                isDemoMode = true,
                detectedChildName = "Sam",
                lastSyncFormatted = AppText.get(R.string.demo_mode)
            )
            _babyProfile.value = BabyProfile(
                name = "Sam",
                shortName = "Sam",
                birthDate = "2026-01-10",
                ageFormatted = AppText.get(R.string.age_months_days, 9, 3)
            )
            loadDemoData()
            return true
        }
        val url = p.getString(PREF_URL, null) ?: return false
        val token = p.getString(PREF_TOKEN_ENC, null)?.let(SecretCipher::decrypt) ?: return false
        restoredToken = token
        val selfSigned = p.getBoolean(PREF_SELF_SIGNED, false)
        val certPin = p.getString(PREF_CERT_PIN, null).orEmpty()
        val cfId = p.getString(PREF_CF_ID, null).orEmpty()
        val cfSecret = p.getString(PREF_CF_SECRET_ENC, null)?.let(SecretCipher::decrypt).orEmpty()
        val childId = p.getInt(PREF_CHILD_ID, -1).takeIf { it >= 0 }
        // Set up writes right away so entries logged while the server is unreachable get queued
        remoteApi = BabyBuddyApiClient.createService(url, selfSigned, cfId, cfSecret, certPin)
        remoteAuthHeader = if (token.startsWith("Token ")) token else "Token $token"
        remoteChildId = childId
        _connectionConfig.update {
            it.copy(
                serverUrl = url,
                apiToken = token,
                allowSelfSignedCert = selfSigned,
                pinnedCertSha256 = certPin,
                cfClientId = cfId,
                cfClientSecret = cfSecret,
                isConnected = true,
                isDemoMode = false,
                detectedChildName = p.getString(PREF_CHILD_NAME, "").orEmpty(),
                lastSyncFormatted = AppText.get(R.string.connecting)
            )
        }
        p.getString(PREF_CHILD_NAME, null)?.let { name ->
            val birthDate = p.getString(PREF_BIRTH_DATE, null)
            _babyProfile.update {
                it.copy(
                    name = name,
                    shortName = name.substringBefore(" "),
                    birthDate = birthDate ?: it.birthDate,
                    ageFormatted = birthDate?.let(::formatAge) ?: ""
                )
            }
        }
        return true
    }

    /** Advances the nursing timer from the wall clock, so it stays right even if ticks are delayed. */
    private fun startTimerTicker() {
        syncScope.launch {
            var last = System.currentTimeMillis()
            while (true) {
                delay(1000)
                val elapsedSec = ((System.currentTimeMillis() - last) / 1000).toInt()
                if (elapsedSec <= 0) continue
                last += elapsedSec * 1000L
                val t = _liveTimerState.value
                if (t.isRunning && !t.isPaused) {
                    updateLiveTimer(
                        leftIncrement = if (t.activeBreast == BreastSide.LEFT) elapsedSec else 0,
                        rightIncrement = if (t.activeBreast == BreastSide.RIGHT) elapsedSec else 0
                    )
                    // Keep the server's copy of a running feed at most a minute behind
                    val total = _liveTimerState.value.totalSeconds
                    if (total / 60 != (total - elapsedSec) / 60) syncLiveFeeding()
                } else if (t.isRunning && t.isPaused && System.currentTimeMillis() - t.pausedAtMillis > SESSION_TIMEOUT_MS) {
                    endFeed()
                }
            }
        }
    }

    private fun saveTimer() {
        val t = _liveTimerState.value
        prefs?.edit()
            ?.putBoolean("timer_running", t.isRunning)
            ?.putBoolean("timer_paused", t.isPaused)
            ?.putString("timer_side", t.activeBreast.name)
            ?.putInt("timer_left", t.leftSeconds)
            ?.putInt("timer_right", t.rightSeconds)
            ?.putString("timer_started", t.sessionStartedTime)
            ?.putLong("timer_feeding_id", t.feedingLocalId ?: -1L)
            ?.putLong("timer_session_start", t.sessionStartMillis)
            ?.putLong("timer_paused_at", t.pausedAtMillis)
            ?.putString("timer_first_side", t.firstSide?.name)
            ?.putLong("timer_last_active", t.lastActiveMillis)
            ?.putLong("timer_changed_at", t.changedAtMillis)
            ?.putLong("timer_feeding_server_id", t.feedingServerId ?: -1L)
            ?.putLong("timer_saved_at", System.currentTimeMillis())
            ?.apply()
    }

    private fun restoreTimer(p: SharedPreferences) {
        if (!p.getBoolean("timer_running", false)) return
        val paused = p.getBoolean("timer_paused", false)
        val side = runCatching { BreastSide.valueOf(p.getString("timer_side", "LEFT")!!) }.getOrDefault(BreastSide.LEFT)
        // Time that passed while the app wasn't running counts towards the active side
        val missed = if (paused) 0 else ((System.currentTimeMillis() - p.getLong("timer_saved_at", 0L)) / 1000).toInt().coerceAtLeast(0)
        _liveTimerState.value = LiveTimerState(
            isRunning = true,
            isPaused = paused,
            activeBreast = side,
            leftSeconds = p.getInt("timer_left", 0) + if (side == BreastSide.LEFT) missed else 0,
            rightSeconds = p.getInt("timer_right", 0) + if (side == BreastSide.RIGHT) missed else 0,
            sessionStartedTime = p.getString("timer_started", "").orEmpty(),
            feedingLocalId = p.getLong("timer_feeding_id", -1L).takeIf { it >= 0 },
            sessionStartMillis = p.getLong("timer_session_start", 0L),
            pausedAtMillis = p.getLong("timer_paused_at", 0L),
            firstSide = p.getString("timer_first_side", null)?.let { name -> runCatching { BreastSide.valueOf(name) }.getOrNull() },
            lastActiveMillis = p.getLong("timer_last_active", 0L),
            changedAtMillis = p.getLong("timer_changed_at", 0L),
            feedingServerId = p.getLong("timer_feeding_server_id", -1L).takeIf { it >= 0 }
        )
    }

    companion object {
        fun defaultDashboardWidgets() = listOf(
            DashboardWidgetConfig(DashboardWidgetType.LAST_FEED, DashboardWidgetType.LAST_FEED.displayName, AppText.get(R.string.card_last_feed_desc), isEnabled = true),
            DashboardWidgetConfig(DashboardWidgetType.LAST_DIAPER, DashboardWidgetType.LAST_DIAPER.displayName, AppText.get(R.string.card_last_diaper_desc), isEnabled = true),
            DashboardWidgetConfig(DashboardWidgetType.WAKE_WINDOWS, DashboardWidgetType.WAKE_WINDOWS.displayName, AppText.get(R.string.card_wake_windows_desc), isEnabled = true),
            DashboardWidgetConfig(DashboardWidgetType.TUMMY_TIME_GOAL, DashboardWidgetType.TUMMY_TIME_GOAL.displayName, AppText.get(R.string.card_tummy_time_desc), isEnabled = false),
            DashboardWidgetConfig(DashboardWidgetType.HEALTH_VITALS, DashboardWidgetType.HEALTH_VITALS.displayName, AppText.get(R.string.card_temperature_desc), isEnabled = false),
            DashboardWidgetConfig(DashboardWidgetType.CAREGIVER_NOTES, DashboardWidgetType.CAREGIVER_NOTES.displayName, AppText.get(R.string.card_notes_desc), isEnabled = false),
            DashboardWidgetConfig(DashboardWidgetType.GROWTH, DashboardWidgetType.GROWTH.displayName, AppText.get(R.string.card_growth_desc), isEnabled = false)
        )

        val instance: BabyBuddyRepository by lazy { BabyBuddyRepository() }

        private const val PREF_URL = "server_url"
        private const val PREF_TOKEN_ENC = "api_token_enc"
        private const val LEGACY_PREF_TOKEN = "api_token"
        private const val ENDPOINT_FEEDINGS = "feedings"
        private const val ENDPOINT_CHANGES = "changes"
        private const val ENDPOINT_TUMMY = "tummy-times"
        private const val ENDPOINT_PUMPING = "pumping"
        private const val ENDPOINT_TEMPERATURE = "temperature"
        private const val ENDPOINT_NOTES = "notes"
        private const val ENDPOINT_SLEEP = "sleep"
        private val GROWTH_ENDPOINTS = GrowthKind.entries.map { it.endpoint }.toSet()
        private const val PREF_BABY_SEX = "baby_sex"
        private const val PREF_DASHBOARD_WIDGETS = "dashboard_widgets"
        private const val PREF_DASHBOARD_WIDGETS_KNOWN = "dashboard_widgets_known"
        private const val ENDPOINT_TIMERS = "timers"
        private const val PROXY_RETRY_ATTEMPTS = 6 // ~2.5 min of backoff before a proxy's 4xx is treated as final
        private const val PREF_SLEEP_CHANGED = "sleep_timer_changed_at"
        private const val PREF_FEED_TIMER_ID = "feed_timer_remote_id"
        private const val PREF_SLEEP_TIMER_ID = "sleep_timer_remote_id"
        private const val PREF_FEED_ENDED_AT = "feed_ended_at"
        private const val PREF_SLEEP_ENDED_AT = "sleep_ended_at"
        private const val PREF_REMIND_FEED = "remind_feed_minutes"
        private const val PREF_HIDDEN_ITEMS = "hidden_items"
        private const val PREF_HIDDEN_SUMMARY_TILES = "hidden_summary_tiles"
        private const val PREF_HIDDEN_WAKE_PARTS = "hidden_wake_window_parts"
        private const val PREF_REMIND_SLEEP = "remind_sleep_minutes"
        private const val PREF_WHEEL_INPUT = "time_input_wheel_v2" // v2: typing became the default
        private const val PREF_REMIND_AWAKE = "remind_awake_minutes"
        private const val PREF_REMIND_DIAPER = "remind_diaper_minutes"
        private const val PREF_REMIND_EARLY = "remind_early_minutes"
        private const val PREF_MODELS_TAB = "trends_models_tab"
        private const val MODEL_PREFS = "rhythm_models"
        private const val PAGE = 1000
        private const val MAX_FETCH = 20_000
        private const val PREF_SLEEP_START = "sleep_timer_start"
        private const val PREF_SLEEP_NOTES = "sleep_timer_notes"
        private const val PREF_CAREGIVER = "caregiver_name"
        private const val PREF_FEED_NOTIFICATION = "feed_notification"
        private const val PREF_NEXT_UP_NOTIFICATION = "next_up_notification"
        private const val PREF_AWAY_SINCE = "away_since"
        private const val PREF_QUICK_ACTIONS = "quick_actions"
        private const val PREF_USE_24H = "use_24h"
        private const val PREF_THEME_MODE = "theme_mode"
        private const val PREF_START_SIDE = "start_side"
        private val SERVER_COLORS = setOf("black", "brown", "gray", "green", "orange", "red", "white", "yellow")
        private const val PREF_SELF_SIGNED = "allow_self_signed"
        private const val PREF_CERT_PIN = "pinned_cert_sha256"
        private const val PREF_CF_ID = "cf_access_client_id"
        private const val PREF_CF_SECRET_ENC = "cf_access_client_secret_enc"
        private const val PREF_CHILD_ID = "child_id"
        private const val PREF_CHILD_NAME = "child_name"
        private const val PREF_BIRTH_DATE = "birth_date"
        private const val PREF_DEMO_MODE = "is_demo_mode"
        private const val SESSION_TIMEOUT_MS = 30 * 60_000L
    }

    // Widget Management Operations
    fun toggleWidget(type: DashboardWidgetType) {
        _dashboardWidgets.update { list ->
            list.map {
                if (it.type == type) it.copy(isEnabled = !it.isEnabled) else it
            }
        }
        saveDashboardWidgets()
    }

    fun addWidget(type: DashboardWidgetType) {
        _dashboardWidgets.update { list ->
            list.map {
                if (it.type == type) it.copy(isEnabled = true) else it
            }
        }
        saveDashboardWidgets()
    }

    fun removeWidget(type: DashboardWidgetType) {
        _dashboardWidgets.update { list ->
            list.map {
                if (it.type == type) it.copy(isEnabled = false) else it
            }
        }
        saveDashboardWidgets()
    }

    private fun saveDashboardWidgets() {
        val enabled = _dashboardWidgets.value.filter { it.isEnabled }.joinToString(",") { it.type.name }
        prefs?.edit()?.putString(PREF_DASHBOARD_WIDGETS, enabled)
            ?.putString(PREF_DASHBOARD_WIDGETS_KNOWN, DashboardWidgetType.entries.joinToString(",") { it.name })?.apply()
    }

    /** Logs a tummy-time session that just ended; returns its id (for undo). */
    fun addTummyTimeMinutes(additionalMinutes: Int): Long {
        val endMs = System.currentTimeMillis()
        val startMs = endMs - additionalMinutes * 60_000L
        val event = TummyTimeEvent(
            id = nextLocalId(),
            timeMillis = startMs,
            timeFormatted = formatClock(startMs),
            durationMinutes = additionalMinutes,
            milestone = "",
            caregiver = myName()
        )
        _tummyTimes.update { (listOf(event) + it).sortedByDescending { e -> e.timeMillis } }
        enqueue(QueuedWrite(ENDPOINT_TUMMY, QueuedWrite.ACTION_CREATE, event.id, body = tummyBody(event)))
        return event.id
    }

    /** Re-fetches today's entries (e.g. ones another caregiver logged) and resends anything queued. */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        val api = remoteApi ?: return@withContext false
        syncToday(api, remoteAuthHeader, remoteChildId)
        flushPendingWrites()
        pollSharedTimers()
        loadMilkStash()
        true
    }

    /** Average time between feed starts per day for the last 7 days, plus this week vs the week before. */
    suspend fun loadFeedIntervals(): Result<FeedIntervalStats> = withContext(Dispatchers.IO) {
        if (_connectionConfig.value.isDemoMode) {
            // Sample numbers to match the demo day (a feed about every 3 hours)
            return@withContext Result.success(
                FeedIntervalStats(listOf(185, 178, 192, 181, 175, 188, 180), thisWeekAverageMinutes = 183, lastWeekAverageMinutes = 171)
            )
        }
        val api = remoteApi
        val child = remoteChildId
        if (api == null || child == null) return@withContext Result.failure(IllegalStateException(AppText.get(R.string.error_not_connected)))
        try {
            val todayStart = startOfTodayMillis()
            val day = 86_400_000L
            val from = todayStart - 14 * day
            val response = api.getFeedings(remoteAuthHeader, child, since = isoUtc(from), limit = 1000)
            if (!response.isSuccessful) error("HTTP ${response.code()} loading feedings")
            // Bottle top-ups logged right after a breastfeed aren't separate feeds
            val starts = response.body()?.results.orEmpty().mapNotNull { parseServerTime(it.start)?.time }.sorted()
                .fold(mutableListOf<Long>()) { acc, t ->
                    if (acc.isEmpty() || t - acc.last() > 20 * 60_000L) acc.add(t)
                    acc
                }
            val gaps = starts.zipWithNext { a, b -> b to (b - a) / 60_000L }
                .filter { it.second in 30..(10 * 60) } // ignore implausible gaps (missing entries, cluster feeds)

            fun averageBetween(start: Long, end: Long): Int? = gaps.filter { it.first in start until end }.map { it.second }.takeIf { it.isNotEmpty() }?.average()?.toInt()

            val daily = (6 downTo 0).map { back ->
                val start = todayStart - back * day
                averageBetween(start, start + day)
            }
            Result.success(
                FeedIntervalStats(
                    dailyAverageMinutes = daily,
                    thisWeekAverageMinutes = averageBetween(todayStart - 6 * day, todayStart + day),
                    lastWeekAverageMinutes = averageBetween(todayStart - 13 * day, todayStart - 6 * day)
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
