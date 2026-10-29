package com.example.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.data.api.UntrustedCertificateException
import com.example.data.ml.RhythmModel
import com.example.data.ml.RhythmTarget
import com.example.data.ml.SavedRhythmModel
import com.example.data.ml.Score
import com.example.data.ml.trainRhythmModels
import com.example.data.model.AppText
import com.example.data.model.BATH_TAG
import com.example.data.model.BabyProfile
import com.example.data.model.BabySex
import com.example.data.model.BreastSide
import com.example.data.model.DashboardWidgetConfig
import com.example.data.model.DashboardWidgetType
import com.example.data.model.DayStats
import com.example.data.model.DiaperEvent
import com.example.data.model.DiaperType
import com.example.data.model.FeedIntervalStats
import com.example.data.model.FeedingEvent
import com.example.data.model.GrowthEvent
import com.example.data.model.GrowthKind
import com.example.data.model.HandoffSummary
import com.example.data.model.HistoryDay
import com.example.data.model.LiveTimerState
import com.example.data.model.NoteEvent
import com.example.data.model.NoteTag
import com.example.data.model.PumpingEvent
import com.example.data.model.QuickAction
import com.example.data.model.ReminderSettings
import com.example.data.model.ServerConnectionConfig
import com.example.data.model.SleepEvent
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
import com.example.data.model.formatGrowth
import com.example.data.model.formatMinutes
import com.example.data.repository.BabyBuddyRepository
import com.example.widget.BabyBuddyQuickLogWidget
import com.example.widget.BabyBuddySummaryWidget
import com.example.widget.BabyBuddyTimerWidget
import com.example.widget.WidgetManagerHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ScreenDestination {
    TODAY_DASHBOARD,
    TRENDS,
    ACTIVE_NURSING_TIMER,
    LOG_DIAPER,
    QUICK_LOG_MODAL,
    HISTORY,
    SLEEP,
    CONNECT_SERVER
}

/** Which entries the History list shows. */
enum class HistoryFilter(private val labelRes: Int) {
    ALL(R.string.history_filter_all),
    FEEDS(R.string.history_filter_feeds),
    DIAPERS(R.string.history_filter_diapers),
    PUMPING(R.string.history_filter_pumping),
    TUMMY(R.string.history_filter_tummy),
    SLEEP(R.string.history_filter_sleep),
    BATH(R.string.history_filter_bath),
    GROWTH(R.string.history_filter_growth), // every measurement, not just the week's
    NOTES(R.string.history_filter_notes);

    val label: String get() = AppText.get(labelRes)
}

data class UiNotification(
    val message: String,
    val isSuccess: Boolean = true,
    val timestamp: Long = System.currentTimeMillis(),
    /** When set, the toast shows an Undo button that runs this. */
    val undo: (() -> Unit)? = null
)

/** A login waiting for the user to trust the server's self-signed certificate; [changed] if a different one was trusted before. */
data class CertificatePrompt(
    val url: String,
    val token: String,
    val cfClientId: String,
    val cfClientSecret: String,
    val fingerprint: String,
    val changed: Boolean
)

class MainViewModel(
    private val repository: BabyBuddyRepository = BabyBuddyRepository.instance
) : ViewModel() {

    private val _currentScreen = MutableStateFlow(ScreenDestination.TODAY_DASHBOARD)
    val currentScreen: StateFlow<ScreenDestination> = _currentScreen.asStateFlow()

    private val _notification = MutableStateFlow<UiNotification?>(null)
    val notification: StateFlow<UiNotification?> = _notification.asStateFlow()

    private val _certificatePrompt = MutableStateFlow<CertificatePrompt?>(null)
    val certificatePrompt: StateFlow<CertificatePrompt?> = _certificatePrompt.asStateFlow()

    private val _showAddWidgetModal = MutableStateFlow(false)
    val showAddWidgetModal: StateFlow<Boolean> = _showAddWidgetModal.asStateFlow()

    val babyProfile: StateFlow<BabyProfile> = repository.babyProfile
    val connectionConfig: StateFlow<ServerConnectionConfig> = repository.connectionConfig
    val liveTimerState: StateFlow<LiveTimerState> = repository.liveTimerState
    val feedings: StateFlow<List<FeedingEvent>> = repository.feedings
    val sleeps: StateFlow<List<SleepEvent>> = repository.sleeps
    val sleepTimer: StateFlow<SleepTimerState> = repository.sleepTimer
    val diapers: StateFlow<List<DiaperEvent>> = repository.diapers
    val tummyTimes: StateFlow<List<TummyTimeEvent>> = repository.tummyTimes
    val pumpings: StateFlow<List<PumpingEvent>> = repository.pumpings
    val lastBreastfeed: StateFlow<FeedingEvent?> = repository.lastBreastfeed
    val lastFeed: StateFlow<FeedingEvent?> = repository.lastFeed
    val previousFeedMillis: StateFlow<Long?> = repository.previousFeedMillis
    val lastDiaper: StateFlow<DiaperEvent?> = repository.lastDiaper
    val quickActions: StateFlow<List<QuickAction>> = repository.quickActions
    fun setQuickActions(actions: List<QuickAction>) = repository.setQuickActions(actions)

    private val _historyFilter = MutableStateFlow(HistoryFilter.ALL)
    val historyFilter: StateFlow<HistoryFilter> = _historyFilter.asStateFlow()
    fun setHistoryFilter(filter: HistoryFilter) {
        _historyFilter.value = filter
    }

    /** Opens History's list on this week, showing only [filter] entries. */
    fun openHistory(filter: HistoryFilter) {
        _historyFilter.value = filter
        navigateTo(ScreenDestination.HISTORY)
    }
    val isServerDisconnectedView: StateFlow<Boolean> = repository.isServerDisconnectedView
    val pendingWriteCount: StateFlow<Int> = repository.pendingWriteCount

    // Trends (loaded from the server when the Trends tab opens)
    private val _trendsRange = MutableStateFlow(7)
    val trendsRange: StateFlow<Int> = _trendsRange.asStateFlow()
    private val _trendsDays = MutableStateFlow<List<DayStats>?>(null)
    val trendsDays: StateFlow<List<DayStats>?> = _trendsDays.asStateFlow()
    private val _trendsLoading = MutableStateFlow(false)
    val trendsLoading: StateFlow<Boolean> = _trendsLoading.asStateFlow()
    private val _trendsError = MutableStateFlow<String?>(null)
    val trendsError: StateFlow<String?> = _trendsError.asStateFlow()
    private var trendsJob: Job? = null

    // History week calendar
    private val _historyLastDay = MutableStateFlow(System.currentTimeMillis())
    private val _historyLoaded = MutableStateFlow<List<HistoryDay>?>(null)
    private val _historyLoading = MutableStateFlow(false)
    val historyLoading: StateFlow<Boolean> = _historyLoading.asStateFlow()
    private val _historyError = MutableStateFlow<String?>(null)
    val historyError: StateFlow<String?> = _historyError.asStateFlow()
    private var historyJob: Job? = null

    /** Loaded week, with today's column always reflecting the live local entries (including unsent ones). */
    val historyDays: StateFlow<List<HistoryDay>?> = combine(
        _historyLoaded,
        combine(repository.feedings, repository.diapers, repository.tummyTimes) { f, d, t -> Triple(f, d, t) },
        combine(repository.pumpings, repository.sleeps, repository.notes) { p, s, n -> Triple(p, s, n) }
    ) { days, (feeds, diapers, tummies), (pumps, sleeps, notes) ->
        val todayStart = startOfDay(System.currentTimeMillis())
        days?.map { day ->
            if (day.dayStartMillis == todayStart) {
                day.copy(
                    feedings = feeds,
                    diapers = diapers,
                    tummyTimes = tummies,
                    pumpings = pumps,
                    sleeps = sleeps.filter { it.timeMillis >= todayStart },
                    baths = notes.filter { BATH_TAG in it.tags && it.timeMillis >= todayStart },
                    notes = notes.filter { BATH_TAG !in it.tags && it.timeMillis >= todayStart }
                )
            } else {
                day
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val historyIsCurrentWeek: StateFlow<Boolean> = _historyLastDay
        .map { startOfDay(it) >= startOfDay(System.currentTimeMillis()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun loadHistory() {
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            _historyLoading.value = true
            _historyError.value = null
            repository.loadHistory(_historyLastDay.value)
                .onSuccess { _historyLoaded.value = it }
                .onFailure { _historyError.value = it.localizedMessage ?: AppText.get(R.string.error_load_history) }
            _historyLoading.value = false
        }
    }

    fun shiftHistoryWeek(weeks: Int) {
        val next = _historyLastDay.value + weeks * 7L * 86_400_000L
        _historyLastDay.value = minOf(next, System.currentTimeMillis())
        loadHistory()
    }

    /** Applies an edit/delete to the loaded week right away (older days aren't in the live lists). */
    private fun patchHistory(transform: (HistoryDay) -> HistoryDay) {
        _historyLoaded.value = _historyLoaded.value?.map(transform)
    }

    private fun startOfDay(millis: Long): Long = java.util.Calendar.getInstance().apply {
        timeInMillis = millis
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun loadTrends(days: Int = _trendsRange.value) {
        _trendsRange.value = days
        trendsJob?.cancel()
        trendsJob = viewModelScope.launch {
            _trendsLoading.value = true
            _trendsError.value = null
            repository.loadTrends(days)
                .onSuccess { _trendsDays.value = it }
                .onFailure { _trendsError.value = it.localizedMessage ?: AppText.get(R.string.error_load_trends) }
            _trendsLoading.value = false
        }
    }

    // Trends → Models: rhythm models trained on the phone from the last [modelsDays] days
    val modelsTab: StateFlow<Boolean> = repository.modelsTab

    fun setModelsTab(enabled: Boolean) = repository.setModelsTab(enabled)

    sealed interface ModelsState {
        data object Idle : ModelsState

        data class Training(val step: String, val progress: Float) : ModelsState

        data class Ready(val models: List<RhythmModel>, val fromMillis: Long, val toMillis: Long, val trainedAt: Long) : ModelsState

        data class Failed(val message: String) : ModelsState
    }

    private val _modelsState = MutableStateFlow<ModelsState>(ModelsState.Idle)
    val modelsState: StateFlow<ModelsState> = _modelsState.asStateFlow()
    private var modelsJob: Job? = null

    /** The models saved for reminders, Today and the widget, and what they predict next. */
    val usedModels: StateFlow<Map<RhythmTarget, SavedRhythmModel>> = repository.usedModels
    val modelNextTimes: StateFlow<Map<RhythmTarget, Long>> = repository.modelNextTimes

    /** Fits every rhythm's models on the entries from [fromMillis] to [toMillis]. */
    fun trainModels(fromMillis: Long, toMillis: Long) {
        modelsJob?.cancel()
        modelsJob = viewModelScope.launch {
            val days = ((toMillis - fromMillis) / 86_400_000L).coerceAtLeast(1)
            _modelsState.value = ModelsState.Training(AppText.plural(R.plurals.models_loading_days, days.toInt(), days), 0f)
            val events = repository.loadRhythmEvents(fromMillis, toMillis, repository.babyProfile.value.birthDate).getOrElse {
                _modelsState.value = ModelsState.Failed(it.localizedMessage ?: AppText.get(R.string.error_load_entries))
                return@launch
            }
            val targets = RhythmTarget.entries.size
            val r = repository.reminders.value
            val defaults = mapOf(
                RhythmTarget.FEED_GAP to r.feedAfterMinutes,
                RhythmTarget.DIAPER_GAP to r.diaperAfterMinutes,
                RhythmTarget.SLEEP_LENGTH to r.sleepAfterMinutes,
                RhythmTarget.AWAKE_WINDOW to r.awakeAfterMinutes
            )
            val models = withContext(Dispatchers.Default) {
                trainRhythmModels(events, defaults) { target, fraction ->
                    _modelsState.value = ModelsState.Training(AppText.get(R.string.models_fitting, target.title.lowercase()), (target.ordinal + fraction) / targets)
                }
            }
            _modelsState.value = ModelsState.Ready(models, fromMillis, toMillis, System.currentTimeMillis())
        }
    }

    /** Saves [score]'s model (refitted on all of [model]'s entries) as the estimate for its rhythm, replacing any saved one. */
    fun saveModel(model: RhythmModel, score: Score) {
        val result = model.result ?: return
        viewModelScope.launch {
            val fitted = withContext(Dispatchers.Default) { result.refit(score) }
            val saved = SavedRhythmModel(
                target = model.target,
                family = score.family,
                settings = score.settings,
                mae = score.mae,
                model = fitted,
                ageOriginMillis = model.ageOriginMillis ?: System.currentTimeMillis(),
                trainedAt = (_modelsState.value as? ModelsState.Ready)?.trainedAt ?: System.currentTimeMillis(),
                inputs = model.inputs,
                residuals = score.residuals
            )
            repository.setUsedModel(model.target, saved)
            showToast(AppText.get(R.string.models_now_estimated_by, model.target.title, score.family.lowercase()))
        }
    }

    /** Back to the default estimate (the fixed interval / weekly average) for [target], or for every rhythm. */
    fun resetModel(target: RhythmTarget? = null) {
        (if (target == null) RhythmTarget.entries else listOf(target)).forEach { repository.setUsedModel(it, null) }
        showToast(if (target == null) AppText.get(R.string.models_all_reset) else AppText.get(R.string.models_reset, target.title))
    }

    // Widgets State
    val dashboardWidgets: StateFlow<List<DashboardWidgetConfig>> = repository.dashboardWidgets
    val tummyTimeGoal: StateFlow<TummyTimeGoal> = repository.tummyTimeGoal
    val temperatures: StateFlow<List<TemperatureEvent>> = repository.temperatures
    val notes: StateFlow<List<NoteEvent>> = repository.notes
    val growth: StateFlow<List<GrowthEvent>> = repository.growth
    val babySex: StateFlow<BabySex?> = repository.babySex

    fun setBabySex(sex: BabySex) = repository.setBabySex(sex)

    // Settings
    val caregiverName: StateFlow<String> = repository.caregiverName
    val feedNotificationEnabled: StateFlow<Boolean> = repository.feedNotificationEnabled
    val use24h: StateFlow<Boolean> = repository.use24h
    fun setUse24h(enabled: Boolean) {
        repository.setUse24h(enabled)
        if (_historyLoaded.value != null) loadHistory() // past days carry formatted times too
    }
    fun setCaregiverName(name: String) = repository.setCaregiverName(name)
    val themeMode: StateFlow<ThemeMode> = repository.themeMode
    fun setThemeMode(mode: ThemeMode) = repository.setThemeMode(mode)
    fun setFeedNotificationEnabled(enabled: Boolean) = repository.setFeedNotificationEnabled(enabled)
    val nextUpNotification: StateFlow<Boolean> = repository.nextUpNotification
    val startSide: StateFlow<StartSide> = repository.startSide
    fun setStartSide(side: StartSide) = repository.setStartSide(side)
    fun setNextUpNotification(enabled: Boolean) = repository.setNextUpNotification(enabled)

    /** "While you were away" on Today: what other caregivers logged since this phone left the app. */
    val handoff: StateFlow<HandoffSummary?> = repository.handoff
    fun dismissHandoff() = repository.dismissHandoff()
    fun markAway() = repository.markAway()

    // Feed-interval card on Today
    private val _feedIntervals = MutableStateFlow<FeedIntervalStats?>(null)
    val feedIntervals: StateFlow<FeedIntervalStats?> = _feedIntervals.asStateFlow()

    /** Pulls entries other caregivers logged, plus the feed-interval card. Called on resume, periodically and on pull-to-refresh. */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()
    private var refreshJob: Job? = null

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _isRefreshing.value = true
            if (repository.refresh() || repository.connectionConfig.value.isDemoMode) {
                repository.checkHandoff()
                repository.loadFeedIntervals().onSuccess { _feedIntervals.value = it }
            }
            _isRefreshing.value = false
        }
    }

    init {
        viewModelScope.launch {
            repository.syncErrors.collect { showToast(it, isSuccess = false) }
        }
    }

    fun navigateTo(destination: ScreenDestination) {
        _currentScreen.value = destination
        if (destination == ScreenDestination.TRENDS) loadTrends()
        if (destination == ScreenDestination.HISTORY) {
            _historyLastDay.value = System.currentTimeMillis()
            loadHistory()
        }
    }

    fun logBottle(ml: Int, formula: Boolean, notes: String = "", timeMillis: Long? = null) {
        val id = repository.logBottle(ml, formula, timeMs = timeMillis ?: System.currentTimeMillis(), notes = notes)
        showToast(AppText.get(if (formula) R.string.toast_bottle_logged_formula else R.string.toast_bottle_logged_breast_milk, ml), undo = { repository.deleteFeeding(id) })
    }

    fun logPumping(ml: Int, minutes: Int, notes: String, startMillis: Long? = null) {
        val id = repository.logPumping(ml, minutes, notes, startMillis)
        showToast(AppText.get(R.string.toast_pumping_logged, ml), undo = { repository.deletePumping(id) })
    }

    fun updatePumping(pumping: PumpingEvent) {
        repository.updatePumping(pumping)
        patchHistory { d -> d.copy(pumpings = d.pumpings.map { if (it.id == pumping.id) pumping else it }) }
        showToast(AppText.get(R.string.toast_pumping_updated))
    }

    fun deletePumping(id: Long) {
        repository.deletePumping(id)
        patchHistory { d -> d.copy(pumpings = d.pumpings.filterNot { it.id == id }) }
        showToast(AppText.get(R.string.toast_pumping_deleted))
    }

    fun setLiveFeedNotes(notes: String) = repository.setLiveFeedNotes(notes)

    fun liveFeedNotes(): String = repository.liveFeedUserNotes()

    fun updateFeeding(feeding: FeedingEvent) {
        repository.updateFeeding(feeding)
        patchHistory { d -> d.copy(feedings = d.feedings.map { if (it.id == feeding.id) feeding else it }) }
        showToast(AppText.get(R.string.toast_feeding_updated))
    }

    fun deleteFeeding(id: Long) {
        repository.deleteFeeding(id)
        patchHistory { d -> d.copy(feedings = d.feedings.filterNot { it.id == id }) }
        showToast(AppText.get(R.string.toast_feeding_deleted))
    }

    fun updateDiaper(diaper: DiaperEvent) {
        repository.updateDiaper(diaper)
        patchHistory { d -> d.copy(diapers = d.diapers.map { if (it.id == diaper.id) diaper else it }) }
        showToast(AppText.get(R.string.toast_diaper_updated))
    }

    fun updateTummyTime(tummyTime: TummyTimeEvent) {
        repository.updateTummyTime(tummyTime)
        patchHistory { d -> d.copy(tummyTimes = d.tummyTimes.map { if (it.id == tummyTime.id) tummyTime else it }) }
        showToast(AppText.get(R.string.toast_tummy_updated))
    }

    fun deleteTummyTime(id: Long) {
        repository.deleteTummyTime(id)
        patchHistory { d -> d.copy(tummyTimes = d.tummyTimes.filterNot { it.id == id }) }
        showToast(AppText.get(R.string.toast_tummy_deleted))
    }

    fun deleteDiaper(id: Long) {
        repository.deleteDiaper(id)
        patchHistory { d -> d.copy(diapers = d.diapers.filterNot { it.id == id }) }
        showToast(AppText.get(R.string.toast_diaper_deleted))
    }

    fun showToast(message: String, isSuccess: Boolean = true, undo: (() -> Unit)? = null) {
        val notification = UiNotification(message, isSuccess, undo = undo)
        _notification.value = notification
        viewModelScope.launch {
            delay(if (undo != null) 5000 else 3000) // longer when there's something to undo
            if (_notification.value === notification) {
                _notification.value = null
            }
        }
    }

    fun runUndo() {
        val undo = _notification.value?.undo ?: return
        _notification.value = null
        undo()
        showToast(AppText.get(R.string.toast_undone))
    }

    fun dismissToast() {
        _notification.value = null
    }

    // Timer controls

    /** Left/Right button: start, switch to, or pause that side. Saved and synced on every tap. */
    fun tapSide(side: BreastSide) {
        repository.tapSide(side)
    }

    fun setSideSeconds(side: BreastSide, seconds: Int) {
        repository.setSideSeconds(side, seconds)
        showToast(AppText.get(if (side == BreastSide.LEFT) R.string.toast_left_side_set else R.string.toast_right_side_set, seconds / 60, seconds % 60))
    }

    fun setFeedStart(millis: Long) {
        repository.setFeedStart(millis)
        showToast(AppText.get(R.string.toast_feed_start_set, TimeFormat.clock(repository.liveTimerState.value.sessionStartMillis)))
    }

    // Sleep
    fun startSleep() {
        repository.startSleep()
        showToast(AppText.get(R.string.toast_sleep_started))
    }

    fun setSleepStart(millis: Long) = repository.setSleepStart(millis)

    fun setSleepNotes(notes: String) = repository.setSleepNotes(notes)

    fun stopSleep(isNap: Boolean) {
        val start = sleepTimer.value.startMillis
        val id = repository.stopSleep(isNap = isNap) ?: return
        val minutes = ((System.currentTimeMillis() - start) / 60_000L).toInt()
        showToast(AppText.get(if (isNap) R.string.toast_nap_saved else R.string.toast_sleep_saved, formatMinutes(minutes)), undo = { repository.deleteSleep(id) })
    }

    fun cancelSleep() {
        repository.cancelSleep()
        showToast(AppText.get(R.string.toast_sleep_discarded))
    }

    fun logSleep(startMillis: Long, endMillis: Long, isNap: Boolean, notes: String) {
        val id = repository.logSleep(startMillis, endMillis, isNap, notes)
        showToast(
            AppText.get(if (isNap) R.string.toast_nap_logged else R.string.toast_sleep_logged, formatMinutes(((endMillis - startMillis) / 60_000L).toInt())),
            undo = { repository.deleteSleep(id) }
        )
    }

    fun updateSleep(sleep: SleepEvent) {
        repository.updateSleep(sleep)
        patchHistory { d -> d.copy(sleeps = d.sleeps.map { if (it.id == sleep.id) sleep else it }) }
        showToast(AppText.get(R.string.toast_sleep_updated))
    }

    fun deleteSleep(id: Long) {
        repository.deleteSleep(id)
        patchHistory { d -> d.copy(sleeps = d.sleeps.filterNot { it.id == id }) }
        showToast(AppText.get(R.string.toast_sleep_deleted))
    }

    fun endFeed() {
        repository.endFeed()
        showToast(AppText.get(R.string.toast_feed_ended))
    }

    fun switchSide() {
        repository.switchActiveSide()
    }

    fun togglePauseTimer() {
        repository.togglePause()
    }

    fun toggleChime() {
        repository.toggleChime()
        val active = liveTimerState.value.softChimeEnabled
        showToast(AppText.get(if (active) R.string.toast_chime_on else R.string.toast_chime_muted))
    }

    fun toggleObservationTag(tag: String) {
        repository.toggleObservation(tag)
    }

    fun startNewNursing(side: BreastSide = BreastSide.RIGHT) {
        repository.startNewNursingSession(side)
    }

    fun finishAndSaveNursing(
        supplementOz: Double = 0.0,
        supplementType: String = "Pumped Breast Milk",
        notes: String = ""
    ) {
        repository.finishAndSaveNursingSession(supplementOz, supplementType, notes)
        showToast(AppText.get(R.string.toast_feeding_saved))
        navigateTo(ScreenDestination.TODAY_DASHBOARD)
    }

    fun saveDiaperChange(
        type: DiaperType,
        amount: String,
        consistency: String,
        color: String,
        rashPresent: Boolean,
        skinBarriers: List<String>,
        timeMillis: Long?, // null = now
        notes: String,
        startNursingAfter: Boolean = false
    ) {
        val event = DiaperEvent(
            timeFormatted = "",
            type = type,
            amount = amount,
            consistency = consistency,
            color = color,
            rashPresent = rashPresent,
            skinBarrierApplied = skinBarriers,
            notes = notes
        )
        val id = repository.addDiaperLog(event, timeMillis ?: System.currentTimeMillis())

        if (startNursingAfter) {
            showToast(AppText.get(R.string.toast_diaper_saved_start_timer))
            navigateTo(ScreenDestination.ACTIVE_NURSING_TIMER)
        } else {
            showToast(AppText.get(R.string.toast_diaper_saved), undo = { repository.deleteDiaper(id) })
            navigateTo(ScreenDestination.TODAY_DASHBOARD)
        }
    }

    fun quickLog(category: String, amount: String? = null, note: String? = null) {
        val id = repository.addQuickLog(category, amount, note) ?: return
        val isBottle = category.equals("bottle", ignoreCase = true)
        showToast(
            if (isBottle) {
                AppText.get(R.string.toast_bottle_logged)
            } else {
                when (category.lowercase()) {
                    "pee" -> AppText.get(R.string.toast_quick_pee)
                    "wet" -> AppText.get(R.string.toast_quick_wet)
                    "poop" -> AppText.get(R.string.toast_quick_poop)
                    "dirty" -> AppText.get(R.string.toast_quick_dirty)
                    else -> AppText.get(R.string.toast_quick_both)
                }
            },
            undo = { if (isBottle) repository.deleteFeeding(id) else repository.deleteDiaper(id) }
        )
    }

    fun testBackendConnection(url: String, token: String, allowSelfSigned: Boolean, cfClientId: String, cfClientSecret: String) {
        // Reconnecting to the same server keeps the certificate trusted before
        val config = connectionConfig.value
        val pin = config.pinnedCertSha256.takeIf { config.serverUrl == url }.orEmpty()
        connect(url, token, allowSelfSigned, cfClientId, cfClientSecret, pin)
    }

    fun trustCertificate() {
        val prompt = _certificatePrompt.value ?: return
        _certificatePrompt.value = null
        connect(prompt.url, prompt.token, true, prompt.cfClientId, prompt.cfClientSecret, prompt.fingerprint)
    }

    fun dismissCertificatePrompt() {
        _certificatePrompt.value = null
    }

    private fun connect(url: String, token: String, allowSelfSigned: Boolean, cfClientId: String, cfClientSecret: String, pin: String) {
        viewModelScope.launch {
            showToast(AppText.get(R.string.toast_connecting), isSuccess = true)
            val result = repository.testConnection(url, token, allowSelfSigned, cfClientId, cfClientSecret, pin)
            val untrusted = result.exceptionOrNull() as? UntrustedCertificateException
            if (result.isSuccess) {
                showToast(result.getOrDefault(AppText.get(R.string.toast_connected)))
                navigateTo(ScreenDestination.TODAY_DASHBOARD)
                refresh()
            } else if (untrusted != null) {
                _notification.value = null
                _certificatePrompt.value = CertificatePrompt(url, token, cfClientId, cfClientSecret, untrusted.fingerprint, changed = pin.isNotBlank())
            } else {
                showToast(AppText.get(R.string.toast_connection_failed, result.exceptionOrNull()?.localizedMessage.toString()), isSuccess = false)
            }
        }
    }

    fun disconnectServer() {
        repository.disconnect()
        navigateTo(ScreenDestination.TODAY_DASHBOARD) // the login screen shows until reconnected
        showToast(AppText.get(R.string.toast_logged_out))
    }

    fun enterDemoMode() {
        repository.enterDemoMode()
        navigateTo(ScreenDestination.TODAY_DASHBOARD)
        showToast(AppText.get(R.string.toast_demo_mode))
    }

    fun toggleServerDisconnectedSimulation(disconnected: Boolean) {
        repository.toggleServerDisconnectedView(disconnected)
        if (disconnected) {
            showToast(AppText.get(R.string.toast_simulating_disconnected), isSuccess = false)
        } else {
            showToast(AppText.get(R.string.toast_server_connected))
        }
    }

    // Widget Management Operations
    fun setAddWidgetModalVisible(visible: Boolean) {
        _showAddWidgetModal.value = visible
    }

    fun toggleDashboardWidget(type: DashboardWidgetType) {
        repository.toggleWidget(type)
        val isNowEnabled = repository.dashboardWidgets.value.find { it.type == type }?.isEnabled == true
        if (isNowEnabled) {
            showToast(AppText.get(R.string.toast_card_added, type.displayName))
        } else {
            showToast(AppText.get(R.string.toast_card_removed, type.displayName))
        }
    }

    fun addDashboardWidget(type: DashboardWidgetType) {
        repository.addWidget(type)
        showToast(AppText.get(R.string.toast_card_added, type.displayName))
    }

    fun removeDashboardWidget(type: DashboardWidgetType) {
        repository.removeWidget(type)
        showToast(AppText.get(R.string.toast_card_removed_short, type.displayName))
    }

    fun addTummyTimeMinutes(minutes: Int) {
        val id = repository.addTummyTimeMinutes(minutes)
        showToast(AppText.get(R.string.toast_tummy_logged, minutes), undo = { repository.deleteTummyTime(id) })
    }

    fun logTemperature(value: Double, notes: String = "") {
        val id = repository.logTemperature(value, notes)
        showToast(AppText.get(R.string.toast_temperature_logged, value), undo = { repository.deleteTemperature(id) })
    }

    /** Logs the measurements taken together (any of weight, length, head) as one entry each. */
    fun logGrowth(values: Map<GrowthKind, Double>, dateMillis: Long?, notes: String = "") {
        if (values.isEmpty()) return
        val ids = values.map { (kind, value) -> kind to repository.logGrowth(kind, value, dateMillis ?: System.currentTimeMillis(), notes) }
        val text = values.entries.joinToString(", ") { (kind, value) -> formatGrowth(kind, value) }
        showToast(AppText.get(R.string.toast_growth_logged, text), undo = { ids.forEach { (kind, id) -> repository.deleteGrowth(kind, id) } })
    }

    /** Saves a changed measurement (from History or the Trends chart). */
    fun updateGrowth(event: GrowthEvent) {
        repository.updateGrowth(event)
        showToast(AppText.get(R.string.toast_growth_updated, event.kind.label))
    }

    fun deleteGrowth(event: GrowthEvent) {
        repository.deleteGrowth(event.kind, event.id)
        showToast(AppText.get(R.string.toast_growth_deleted, event.kind.label))
    }

    // Baths (notes tagged "bath")
    val lastBathMillis: StateFlow<Long?> = repository.lastBathMillis

    fun logBath(timeMillis: Long?, notes: String) {
        val id = repository.logBath(timeMillis ?: System.currentTimeMillis(), notes)
        showToast(AppText.get(R.string.toast_bath_logged), undo = { repository.deleteNote(id) })
    }

    /** Saves a changed note or bath from History. */
    fun updateNote(note: NoteEvent) {
        repository.updateNote(note)
        patchHistory { d ->
            d.copy(
                baths = d.baths.map { if (it.id == note.id) note else it },
                notes = d.notes.map { if (it.id == note.id) note else it }
            )
        }
        showToast(AppText.get(if (BATH_TAG in note.tags) R.string.toast_bath_updated else R.string.toast_note_updated))
    }

    fun deleteNoteEntry(note: NoteEvent) {
        repository.deleteNoteEvent(note)
        patchHistory { d -> d.copy(baths = d.baths.filterNot { it.id == note.id }, notes = d.notes.filterNot { it.id == note.id }) }
        showToast(AppText.get(if (BATH_TAG in note.tags) R.string.toast_bath_deleted else R.string.toast_note_deleted))
    }

    fun addNote(text: String, tags: List<String> = emptyList()) {
        val id = repository.addNote(text, tags)
        showToast(if (tags.isEmpty()) AppText.get(R.string.toast_note_added) else AppText.get(R.string.toast_tagged_note_logged, text.trim()), undo = { repository.deleteNote(id) })
    }

    // Quick notes, milk stash, reminders
    val noteTags: StateFlow<List<NoteTag>> = repository.noteTags
    val milkStash: StateFlow<Int?> = repository.milkStash
    val lastFeedMillis: StateFlow<Long?> = repository.lastFeedMillis
    val lastSleepEndMillis: StateFlow<Long?> = repository.lastSleepEndMillis
    val reminders: StateFlow<ReminderSettings> = repository.reminders
    val hiddenItems: StateFlow<Set<TrackedItem>> = repository.hiddenItems
    fun setItemTracked(item: TrackedItem, tracked: Boolean) = repository.setItemTracked(item, tracked)
    val hiddenTodayTiles: StateFlow<Set<TodayTile>> = repository.hiddenTodayTiles
    fun setTodayTileShown(tile: TodayTile, shown: Boolean) = repository.setTodayTileShown(tile, shown)
    val hiddenWakeWindowParts: StateFlow<Set<WakeWindowPart>> = repository.hiddenWakeWindowParts
    fun setWakeWindowPartShown(part: WakeWindowPart, shown: Boolean) = repository.setWakeWindowPartShown(part, shown)
    fun setReminders(settings: ReminderSettings) = repository.setReminders(settings)

    fun setMilkStash(ml: Int) {
        val id = repository.setMilkStash(ml)
        showToast(AppText.get(R.string.toast_milk_stash_set, ml), undo = { repository.undoMilkStash(id) })
    }

    /** Picks up feeds/sleeps started or ended on other phones; called every few seconds while the app is open. */
    fun pollTimers() {
        viewModelScope.launch { repository.pollSharedTimers() }
    }

    fun pinHomeScreenWidget(context: Context, widgetType: String) {
        val providerClass = when (widgetType) {
            "QUICK_LOG" -> BabyBuddyQuickLogWidget::class.java
            "TIMER" -> BabyBuddyTimerWidget::class.java
            "SUMMARY" -> BabyBuddySummaryWidget::class.java
            else -> BabyBuddyQuickLogWidget::class.java
        }
        val sent = WidgetManagerHelper.pinWidget(context, providerClass)
        if (sent) {
            showToast(AppText.get(R.string.toast_requesting_widget), isSuccess = true)
        } else {
            showToast(AppText.get(R.string.toast_widget_added_hint), isSuccess = true)
        }
    }
}
