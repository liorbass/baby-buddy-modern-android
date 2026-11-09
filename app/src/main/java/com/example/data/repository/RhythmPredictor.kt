package com.example.data.repository

import android.content.SharedPreferences
import com.example.data.ml.RhythmEvents
import com.example.data.ml.RhythmPrediction
import com.example.data.ml.RhythmTarget
import com.example.data.ml.SavedRhythmModel
import com.example.data.ml.predictRhythms
import com.example.data.model.DiaperEvent
import com.example.data.model.SleepTimerState
import com.example.data.model.TrackedItem
import com.example.data.model.startOfDay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** What the predictor reads about now: the anchors predictions count from, and the running feed and sleep. */
class RhythmAnchors(
    val lastFeedMillis: StateFlow<Long?>,
    val lastDiaper: StateFlow<DiaperEvent?>,
    val lastSleepEndMillis: StateFlow<Long?>,
    val feedRunning: Flow<Boolean>,
    val sleepTimer: StateFlow<SleepTimerState>
)

/**
 * Trends → Models: the models picked for reminders, Today and the summary widget (saved in prefs "rhythm_models"),
 * and when each expects the next event, from today's entries ([today]) and the day before ([fetchEvents], refetched
 * every half hour).
 */
class RhythmPredictor(
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val anchors: RhythmAnchors,
    private val today: () -> TimedEvents,
    private val fetchEvents: suspend (fromMillis: Long) -> TimedEvents
) {
    private var prefs: SharedPreferences? = null
    private val _usedModels = MutableStateFlow<Map<RhythmTarget, SavedRhythmModel>>(emptyMap())
    val usedModels: StateFlow<Map<RhythmTarget, SavedRhythmModel>> = _usedModels.asStateFlow()
    private val _predictions = MutableStateFlow<Map<RhythmTarget, RhythmPrediction>>(emptyMap())
    val predictions: StateFlow<Map<RhythmTarget, RhythmPrediction>> = _predictions.asStateFlow()
    private var recentEvents: TimedEvents? = null
    private var recentFetchedAt = 0L
    private val mutex = Mutex()

    /** Restores the saved models. */
    fun load(modelPrefs: SharedPreferences) {
        prefs = modelPrefs
        _usedModels.value = modelPrefs.all.values.mapNotNull { (it as? String)?.let(SavedRhythmModel::fromText) }.associateBy { it.target }
    }

    /** Uses [model] for its rhythm from now on, or stops using a model for [target] when null. */
    fun setUsedModel(target: RhythmTarget, model: SavedRhythmModel?) {
        _usedModels.update { if (model == null) it - target else it + (target to model) }
        prefs?.edit()?.apply { if (model == null) remove(target.name) else putString(target.name, model.toText()) }?.apply()
        scope.launch { update() }
    }

    /** Forgets every saved model (they learned the rhythm of the child, or demo, being left). */
    fun clear() = RhythmTarget.entries.forEach { setUsedModel(it, null) }

    /** The model's expected time for the next event counted from [anchorMillis], if a model is used for [target]. */
    fun due(target: RhythmTarget, anchorMillis: Long): Long? = predictionFrom(target, anchorMillis)?.dueMillis

    /** Like [due], but once that time has passed, when the event now looks most likely (see [RhythmPrediction.expectedMillis]). */
    private fun expected(target: RhythmTarget, anchorMillis: Long, now: Long): Long? = predictionFrom(target, anchorMillis)?.expectedMillis(now)

    private fun predictionFrom(target: RhythmTarget, anchorMillis: Long) = _predictions.value[target]?.takeIf { abs(it.anchorMillis - anchorMillis) < 60_000L }

    /**
     * The picked models' times for what's next from here: the next feed (unless one is running), the next diaper
     * change, and the wake-up while a sleep is timed, else the next sleep. Untracked items are left out. An overdue
     * time is re-estimated every minute.
     */
    val nextTimes: StateFlow<Map<RhythmTarget, Long>> by lazy {
        val minutes = flow {
            while (true) {
                emit(System.currentTimeMillis())
                delay(60_000L)
            }
        }
        combine(_predictions, settings.hiddenItems, anchors.feedRunning, minutes) { _, hidden, feeding, now ->
            val sleep = anchors.sleepTimer.value
            buildMap {
                fun putExpected(target: RhythmTarget, anchor: Long?) {
                    anchor?.let { expected(target, it, now) }?.let { put(target, it) }
                }
                if (feedsShown(hidden) && !feeding) putExpected(RhythmTarget.FEED_GAP, anchors.lastFeedMillis.value)
                if (TrackedItem.DIAPERS !in hidden) putExpected(RhythmTarget.DIAPER_GAP, anchors.lastDiaper.value?.timeMillis)
                if (TrackedItem.SLEEP !in hidden) {
                    if (sleep.isRunning) {
                        putExpected(RhythmTarget.SLEEP_LENGTH, sleep.startMillis)
                    } else {
                        putExpected(RhythmTarget.AWAKE_WINDOW, anchors.lastSleepEndMillis.value)
                    }
                }
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())
    }

    /**
     * What's next for the next-up notification: [nextTimes], else the next feed and diaper change by the reminder
     * intervals (Settings → Notifications).
     */
    val nextUpTimes: StateFlow<Map<RhythmTarget, Long>> by lazy {
        combine(
            nextTimes,
            settings.reminders,
            combine(anchors.lastFeedMillis, anchors.lastDiaper.map { it?.timeMillis }) { feed, diaper -> feed to diaper },
            anchors.feedRunning,
            settings.hiddenItems
        ) { model, reminders, (lastFeed, lastChange), feeding, hidden ->
            buildMap {
                putAll(model)
                if (RhythmTarget.FEED_GAP !in model && feedsShown(hidden) && !feeding && lastFeed != null && reminders.feedAfterMinutes > 0) {
                    put(RhythmTarget.FEED_GAP, lastFeed + reminders.feedAfterMinutes * 60_000L)
                }
                if (RhythmTarget.DIAPER_GAP !in model && TrackedItem.DIAPERS !in hidden && lastChange != null && reminders.diaperAfterMinutes > 0) {
                    put(RhythmTarget.DIAPER_GAP, lastChange + reminders.diaperAfterMinutes * 60_000L)
                }
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())
    }

    private fun feedsShown(hidden: Set<TrackedItem>) = TrackedItem.BREASTFEEDING !in hidden || TrackedItem.BOTTLE !in hidden

    /** Recomputes [predictions]: today's entries from memory, the day before from the server. */
    suspend fun update() = mutex.withLock {
        val models = _usedModels.value.values
        if (models.isEmpty()) {
            _predictions.value = emptyMap()
            return@withLock
        }
        val now = System.currentTimeMillis()
        val midnight = startOfDay()
        if (recentEvents == null || recentFetchedAt < midnight || now - recentFetchedAt > 30 * 60_000L) {
            withContext(Dispatchers.IO) { runCatching { fetchEvents(now - 2 * 24 * 60 * 60_000L) } }.onSuccess {
                recentEvents = it
                recentFetchedAt = now
            }
        }
        val old = recentEvents ?: TimedEvents()
        val current = today()
        val events = RhythmEvents(
            feeds = old.feeds.filter { it.timeMillis < midnight } + current.feeds.filter { it.timeMillis >= midnight },
            changes = old.changes.filter { it.timeMillis < midnight } + current.changes.filter { it.timeMillis >= midnight },
            sleeps = old.sleeps.filter { it.id != ServerQueries.RUNNING_SLEEP_ID && it.endMillis < midnight } + current.sleeps.filter { it.endMillis >= midnight },
            runningSleepStart = anchors.sleepTimer.value.takeIf { it.isRunning }?.startMillis
        )
        _predictions.value = withContext(Dispatchers.Default) { predictRhythms(events, models) }
    }
}
