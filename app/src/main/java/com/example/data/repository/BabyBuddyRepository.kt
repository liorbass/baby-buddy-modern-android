package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.R
import com.example.data.api.BabyBuddyApiClient
import com.example.data.api.UntrustedCertificateException
import com.example.data.ml.RhythmEvents
import com.example.data.ml.RhythmPrediction
import com.example.data.ml.RhythmTarget
import com.example.data.ml.SavedRhythmModel
import com.example.data.model.AppText
import com.example.data.model.BATH_NOTE_TEXT
import com.example.data.model.BATH_TAG
import com.example.data.model.BabyProfile
import com.example.data.model.BabySex
import com.example.data.model.BreastSide
import com.example.data.model.DayStats
import com.example.data.model.DiaperEvent
import com.example.data.model.DiaperType
import com.example.data.model.FeedIntervalStats
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
import com.example.data.model.ReminderSettings
import com.example.data.model.ServerConnectionConfig
import com.example.data.model.SleepEvent
import com.example.data.model.SleepTimerState
import com.example.data.model.TemperatureEvent
import com.example.data.model.ThemeMode
import com.example.data.model.TimeFormat
import com.example.data.model.TrackedItem
import com.example.data.model.TummyTimeEvent
import com.example.data.model.TummyTimeGoal
import com.example.data.model.startOfDay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BabyBuddyRepository {

    /** This phone's settings (Settings screens, Customize Today), saved in prefs. */
    val settings = SettingsStore(onClockFormatChanged = { reformatTimes() })

    private val _babyProfile = MutableStateFlow(BabyProfile())
    val babyProfile: StateFlow<BabyProfile> = _babyProfile.asStateFlow()

    // Set on a successful connect; writes go to the server only while these are present
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: RemoteSession? = null

    fun nextLocalId(): Long = newLocalId()

    private var store: OfflineStore? = null

    /** Errors from background writes to the server, for the UI to surface. */
    private val _syncErrors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val syncErrors: SharedFlow<String> = _syncErrors.asSharedFlow()

    /** Writes the server hasn't accepted yet ([WriteQueue]); they find their local entries through [localEntries]. */
    private val writeQueue: WriteQueue by lazy {
        WriteQueue(
            scope = syncScope,
            target = { session?.target },
            local = localEntries,
            reportError = { _syncErrors.tryEmit(it) }
        )
    }

    /** Number of entries waiting to be saved to the server. */
    val pendingWriteCount: StateFlow<Int> get() = writeQueue.count

    private val _connectionConfig = MutableStateFlow(ServerConnectionConfig())
    val connectionConfig: StateFlow<ServerConnectionConfig> = _connectionConfig.asStateFlow()

    private val _feedings = MutableStateFlow<List<FeedingEvent>>(emptyList())
    val feedings: StateFlow<List<FeedingEvent>> = _feedings.asStateFlow()

    /** The breastfeeding timer; every tap is saved to the feed's record ([NursingTimer]). */
    private val nursing = NursingTimer(
        records = FeedRecords(_feedings, nextId = ::nextLocalId, add = ::addFeeding, update = { feedStore.update(it) }),
        onChanged = { timers.feedChanged() },
        onEnded = { timers.feedEnded() }
    )
    val liveTimerState: StateFlow<LiveTimerState> = nursing.state.asStateFlow()

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
            liveTimerState.map { it.takeIf { s -> s.isRunning }?.let { s -> s.sessionStartMillis to s.feedingLocalId } }.distinctUntilChanged()
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

    private val _sleeps = MutableStateFlow<List<SleepEvent>>(emptyList())
    val sleeps: StateFlow<List<SleepEvent>> = _sleeps.asStateFlow()

    /** A sleep being timed on this phone; saved as a sleep entry when it ends ([SleepTimer]). */
    private val sleeping = SleepTimer(logSleep = ::logSleep, onChanged = { timers.sleepChanged() }, onEnded = { timers.sleepEnded() })
    val sleepTimer: StateFlow<SleepTimerState> = sleeping.state

    private val _diapers = MutableStateFlow<List<DiaperEvent>>(emptyList())
    val diapers: StateFlow<List<DiaperEvent>> = _diapers.asStateFlow()

    private val _tummyTimes = MutableStateFlow<List<TummyTimeEvent>>(emptyList())
    val tummyTimes: StateFlow<List<TummyTimeEvent>> = _tummyTimes.asStateFlow()

    private val _pumpings = MutableStateFlow<List<PumpingEvent>>(emptyList())
    val pumpings: StateFlow<List<PumpingEvent>> = _pumpings.asStateFlow()

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

    // Each kind of entry: its list and how its changes are queued ([EntryStore])
    private val entryQueue = EntryQueue(enqueue = ::enqueue, knownServerId = { writeQueue.knownServerId(it) }, pending = { writeQueue.snapshot })
    private val feedStore = EntryStore(_feedings, EntryKeys({ it.id }, { it.serverId }, { it.timeMillis }, { e, id -> e.copy(serverId = id) }), { Endpoints.FEEDINGS }, entryQueue, ::feedingBody)
    private val diaperStore = EntryStore(_diapers, EntryKeys({ it.id }, { it.serverId }, { it.timeMillis }, { e, id -> e.copy(serverId = id) }), { Endpoints.CHANGES }, entryQueue, ::changeBody)
    private val tummyStore = EntryStore(_tummyTimes, EntryKeys({ it.id }, { it.serverId }, { it.timeMillis }, { e, id -> e.copy(serverId = id) }), { Endpoints.TUMMY }, entryQueue, ::tummyBody)
    private val pumpingStore = EntryStore(_pumpings, EntryKeys({ it.id }, { it.serverId }, { it.timeMillis }, { e, id -> e.copy(serverId = id) }), { Endpoints.PUMPING }, entryQueue, ::pumpingBody)
    private val temperatureStore =
        EntryStore(_temperatures, EntryKeys({ it.id }, { it.serverId }, { it.timeMillis }, { e, id -> e.copy(serverId = id) }), { Endpoints.TEMPERATURE }, entryQueue, ::temperatureBody)
    private val noteStore = EntryStore(_notes, EntryKeys({ it.id }, { it.serverId }, { it.timeMillis }, { e, id -> e.copy(serverId = id) }), { Endpoints.NOTES }, entryQueue, ::noteBody, ::newNoteBody)
    private val sleepStore = EntryStore(_sleeps, EntryKeys({ it.id }, { it.serverId }, { it.timeMillis }, { e, id -> e.copy(serverId = id) }), { Endpoints.SLEEP }, entryQueue, ::sleepBody)
    private val growthStore = EntryStore(_growth, EntryKeys({ it.id }, { it.serverId }, { it.dateMillis }, { e, id -> e.copy(serverId = id) }), { it.kind.endpoint }, entryQueue, ::growthBody)

    private fun storeFor(endpoint: String): EntryStore<*> = when (endpoint) {
        Endpoints.FEEDINGS -> feedStore
        Endpoints.CHANGES -> diaperStore
        Endpoints.PUMPING -> pumpingStore
        Endpoints.TEMPERATURE -> temperatureStore
        Endpoints.NOTES -> noteStore
        Endpoints.SLEEP -> sleepStore
        in Endpoints.GROWTH -> growthStore
        else -> tummyStore
    }

    /** The side a one-tap feed starts on now, per the start side setting and the last breastfeed. */
    fun nextStartSide(): BreastSide = settings.startSide.value.after(lastBreastfeed.value?.lastSide)

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
        nursing.reformat()
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
        val today = TimedEvents(_feedings.value, _diapers.value, _sleeps.value)
        val events = if (from >= startOfDay()) {
            today
        } else {
            withContext(Dispatchers.IO) { runCatching { queries.fetchEvents(from) }.getOrNull() } ?: today
        }
        val summary = handoffSummary(events, from, myName(), settings.hiddenItems.value)
        if (!summary.isEmpty) _handoff.value = summary
    }

    private fun myName(): String? = settings.caregiverName.value.ifBlank { null }

    /** Demo mode's simulated history (all of it; the lists hold today's part). */
    private var demoHistory: DemoHistory? = null

    fun enterDemoMode() {
        clearRemote()
        prefs?.let { SavedLogin.clear(it, demoMode = true) }

        settings.setCaregiverName("Sarah")

        settings.setThemeMode(ThemeMode.LIGHT)
        showDemoBaby()
        // Show every feature: growth charts, reminders and the rhythm models (fitted on the demo data and in use)
        if (settings.babySex.value == null) settings.setBabySex(BabySex.BOY)
        if (!settings.reminders.value.anyOn) settings.setReminders(ReminderSettings(feedAfterMinutes = 180, awakeAfterMinutes = 120, sleepAfterMinutes = 150, diaperAfterMinutes = 180, earlyMinutes = 15))
        settings.setModelsTab(true)
        val demo = demoHistory ?: return
        syncScope.launch(Dispatchers.Default) {
            fitDemoModels(demo, parseLocalDate(_babyProfile.value.birthDate), settings.reminders.value, System.currentTimeMillis())
                .forEach { setUsedModel(it.target, it) }
        }
    }

    private fun loadDemoData() {
        val today = startOfDay()
        // Simulated up to now, so "last feed", "awake for" and the charts read naturally at any time of day
        val now = System.currentTimeMillis()
        val birth = parseLocalDate(_babyProfile.value.birthDate) ?: (today - 270 * 86_400_000L)
        val demo = generateDemoHistory(now = now, birthMillis = birth, clock = ::formatClock)
        demoHistory = demo
        _growth.value = demo.growth
        _noteTags.value = listOf(NoteTag("Vitamin D"), NoteTag("Medicine"), NoteTag("Teething"))

        _feedings.value = demo.feeds.filter { it.timeMillis >= today }
        _diapers.value = demo.diapers.filter { it.timeMillis >= today }
        _sleeps.value = demo.sleeps.filter { it.endMillis >= today }
        _pumpings.value = demo.pumpings.filter { it.timeMillis >= today }
        _tummyTimes.value = demo.tummyTimes.filter { it.timeMillis >= today }
        _notes.value = demo.notes.filter { it.timeMillis >= today }

        // The latest of each, from before today too (early in the morning today has little)
        _lastBreastfeedRemote.value = demo.feeds.firstOrNull { it.type == FeedType.NURSING }
        _recentFeedsRemote.value = demo.feeds.sortedByDescending { it.timeMillis }.take(10)
        _lastDiaperRemote.value = demo.diapers.firstOrNull()
        _lastSleepEndRemote.value = demo.sleeps.maxOfOrNull { it.endMillis }
        _lastBathRemote.value = demo.notes.firstOrNull { BATH_TAG in it.tags }?.timeMillis
        stash.set(450, now)
        // This morning's 9:30, or yesterday's before then
        val temperatureAt = (today + 9 * 3_600_000L + 30 * 60_000L).let { if (it < now) it else it - 86_400_000L }
        _temperatures.value = listOf(
            TemperatureEvent(id = 900L, temperature = 36.8, timeMillis = temperatureAt, timeFormatted = formatClock(temperatureAt), caregiver = "Sarah")
        )
        settings.showAllCards()
    }

    /**
     * Logs in to the server at [serverUrl] (finding the child entries go to), then syncs today. On success the login is
     * saved; the result is the message to show.
     */
    suspend fun testConnection(
        serverUrl: String,
        token: String,
        allowSelfSigned: Boolean = false,
        cfClientId: String = "",
        cfClientSecret: String = "",
        pinnedCertSha256: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        val pin = pinnedCertSha256.takeIf { allowSelfSigned }.orEmpty()
        try {
            val api = BabyBuddyApiClient.createService(serverUrl, allowSelfSigned, cfClientId, cfClientSecret, pin)
            val authHeader = RemoteSession.authHeader(token)
            val response = api.getChildren(authHeader)
            if (!response.isSuccessful) {
                val code = response.code()
                val message = if (code == 401 || code == 403) {
                    loginRefusedMessage(code, behindCloudflare = response.headers()["cf-ray"] != null, hasCloudflareToken = cfClientId.isNotBlank())
                } else {
                    "HTTP $code: ${response.message()}"
                }
                return@withContext Result.failure(Exception(message))
            }
            val child = response.body()?.results?.firstOrNull()
            val name = child?.let { "${it.firstName} ${it.lastName}".trim() } ?: AppText.get(R.string.profile_baby)
            val shortName = child?.firstName ?: AppText.get(R.string.profile_baby)
            val login = SavedLogin(serverUrl, token, allowSelfSigned, pin, cfClientId, cfClientSecret, child?.id, name, child?.birthDate)
            _babyProfile.update {
                it.copy(
                    name = name,
                    shortName = shortName,
                    birthDate = child?.birthDate ?: it.birthDate,
                    ageFormatted = child?.birthDate?.let(::formatAge) ?: it.ageFormatted
                )
            }
            showLogin(login)
            val connected = RemoteSession(api, authHeader, child?.id)
            session = connected
            prefs?.let(login::save)

            syncToday(connected)
            flushPendingWrites()
            pollSharedTimers()
            loadMilkStash()
            Result.success(AppText.get(R.string.connected_as, shortName))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(loginFailure(e, allowSelfSigned))
        }
    }

    /** Shows [login] as the connection in Settings. */
    private fun showLogin(login: SavedLogin) = _connectionConfig.update {
        it.copy(
            serverUrl = login.url,
            apiToken = login.token,
            isConnected = true,
            isDemoMode = false,
            allowSelfSignedCert = login.allowSelfSigned,
            pinnedCertSha256 = login.certPin,
            cfClientId = login.cfClientId,
            cfClientSecret = login.cfClientSecret,
            detectedChildName = login.childName.orEmpty()
        )
    }

    /**
     * Replaces local lists with today's (device-local midnight onwards) entries from the server, and refreshes the
     * latest feed, diaper change, sleep and bath from before today. A step that fails (offline, or an older server
     * without that endpoint) leaves its list as it was.
     */
    private suspend fun syncToday(remote: RemoteSession) {
        val api = remote.api
        val auth = remote.auth
        val child = remote.child
        val since = isoUtc(startOfDay())

        fetchOrNull { api.getFeedings(auth, child, since).body()!!.results.map(::mapFeeding) }?.let { feedStore.mergeRemote(Endpoints.FEEDINGS, it) }
        fetchOrNull { api.getChanges(auth, child, since).body()!!.results.map(::mapChange) }?.let { diaperStore.mergeRemote(Endpoints.CHANGES, it) }
        fetchOrNull { api.getTummyTimes(auth, child, since).body()!!.results.map(::mapTummyTime) }?.let { tummyStore.mergeRemote(Endpoints.TUMMY, it) }
        fetchOrNull { api.getPumping(auth, child, since).body()!!.results.map(::mapPumping) }?.let { pumpingStore.mergeRemote(Endpoints.PUMPING, it) }
        // Sleeps that ended today, so last night's sleep counts too
        fetchOrNull { api.getSleep(auth, child, endedSince = since).body()!!.results.map(::mapSleep) }?.let { sleepStore.mergeRemote(Endpoints.SLEEP, it) }
        fetchOrNull { api.getTemperatures(auth, child, since).body()!!.results.map(::mapTemperature) }?.let { temperatureStore.mergeRemote(Endpoints.TEMPERATURE, it) }
        fetchOrNull { api.getNotes(auth, child, since).body()!!.results.map(::mapNote) }?.let { noteStore.mergeRemote(Endpoints.NOTES, it) }
        if (TrackedItem.GROWTH !in settings.hiddenItems.value) {
            GrowthKind.entries.forEach { kind ->
                fetchOrNull { api.getGrowth(auth, kind.endpoint, child).body()!!.results.map { mapGrowth(kind, it) } }?.let { growthStore.mergeRemote(kind.endpoint, it) }
            }
        }

        // The latest from before today too (newest first): the last side, "last diaper", "awake for", "last bath"
        fetchOrNull { api.getFeedings(auth, child, limit = 10).body()!!.results.map(::mapFeeding) }?.let { recent ->
            _lastBreastfeedRemote.value = recent.firstOrNull { it.type == FeedType.NURSING }
            _recentFeedsRemote.value = recent.sortedByDescending { it.timeMillis }
        }
        fetchOrNull { api.getChanges(auth, child, limit = 1).body()!!.results.map(::mapChange) }?.let { _lastDiaperRemote.value = it.firstOrNull() }
        fetchOrNull { api.getSleep(auth, child, limit = 5).body()!!.results.map(::mapSleep) }?.let { recent -> _lastSleepEndRemote.value = recent.maxOfOrNull { it.endMillis } }
        fetchOrNull {
            api.getNotes(auth, child, since = isoUtc(System.currentTimeMillis() - 45L * 24 * 60 * 60_000L), limit = 1000).body()!!.results
                .filter { BATH_TAG in it.tags.orEmpty() }
                .mapNotNull { it.time?.let(::parseServerTime)?.time }
        }?.let { _lastBathRemote.value = it.maxOrNull() }
        fetchOrNull { api.getTags(auth).body()!!.results }?.let { tags ->
            _noteTags.value = tags.filter { it.name.isNotBlank() && it.name != MILK_STASH_TAG && it.name != BATH_TAG }
                .map { NoteTag(it.name, it.color, it.lastUsed?.let(::parseServerTime)?.time ?: 0L) }
                .sortedByDescending { it.lastUsedMillis }
        }
        _connectionConfig.update { it.copy(lastSyncMillis = System.currentTimeMillis()) }
    }

    /** [load]'s result, or null if it failed; a cancelled coroutine stays cancelled. */
    private inline fun <T> fetchOrNull(load: () -> T): T? = try {
        load()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    // Mapping server records to entries and back, and the generated note segments: ServerMapping.kt

    /** Read-only range queries for History, Trends and the rhythm models ([ServerQueries]). */
    private val queries = ServerQueries(
        session = { session },
        demo = { demoHistory.takeIf { _connectionConfig.value.isDemoMode } },
        sleepTimer = { sleeping.state.value },
        hiddenItems = { settings.hiddenItems.value }
    )

    /**
     * Entries for the [days] local days ending with the day containing [lastDayMillis], oldest first.
     * Today's column uses the live local lists, which include unsent entries and pending edits.
     */
    suspend fun loadHistory(lastDayMillis: Long, days: Int = 7): Result<List<HistoryDay>> = queries.loadHistory(lastDayMillis, days) { start ->
        HistoryDay(
            dayStartMillis = start,
            feedings = _feedings.value,
            diapers = _diapers.value,
            tummyTimes = _tummyTimes.value,
            pumpings = _pumpings.value,
            sleeps = _sleeps.value.filter { it.timeMillis >= start },
            baths = _notes.value.filter { BATH_TAG in it.tags && it.timeMillis >= start },
            notes = _notes.value.filter { BATH_TAG !in it.tags && it.timeMillis >= start }
        )
    }

    /** See [ServerQueries.loadRhythmEvents]. */
    suspend fun loadRhythmEvents(fromMillis: Long, toMillis: Long, birthDate: String?): Result<RhythmEvents> = queries.loadRhythmEvents(fromMillis, toMillis, birthDate)

    /** Per-day numbers for the last [days] days (today last); see [ServerQueries.loadTrends]. */
    suspend fun loadTrends(days: Int): Result<List<DayStats>> = queries.loadTrends(days)

    /** Average time between feed starts per day for the last 7 days, plus this week vs the week before. */
    suspend fun loadFeedIntervals(): Result<FeedIntervalStats> = queries.loadFeedIntervals()

    /** The models picked in Trends → Models and their predictions ([RhythmPredictor]). */
    private val predictor: RhythmPredictor by lazy {
        RhythmPredictor(
            scope = syncScope,
            settings = settings,
            anchors = RhythmAnchors(lastFeedMillis, lastDiaper, lastSleepEndMillis, liveTimerState.map { it.isRunning }, sleepTimer),
            today = { TimedEvents(_feedings.value, _diapers.value, _sleeps.value) },
            fetchEvents = { queries.fetchEvents(it) }
        )
    }
    val usedModels: StateFlow<Map<RhythmTarget, SavedRhythmModel>> get() = predictor.usedModels
    val rhythmPredictions: StateFlow<Map<RhythmTarget, RhythmPrediction>> get() = predictor.predictions

    /** See [RhythmPredictor.nextTimes]. */
    val modelNextTimes: StateFlow<Map<RhythmTarget, Long>> get() = predictor.nextTimes

    /** See [RhythmPredictor.nextUpTimes]. */
    val nextUpTimes: StateFlow<Map<RhythmTarget, Long>> get() = predictor.nextUpTimes

    fun setUsedModel(target: RhythmTarget, model: SavedRhythmModel?) = predictor.setUsedModel(target, model)

    fun modelDue(target: RhythmTarget, anchorMillis: Long): Long? = predictor.due(target, anchorMillis)

    suspend fun updatePredictions() = predictor.update()

    /** Demo mode's connection, baby (Sam) and simulated history; used on entering it and on start. */
    private fun showDemoBaby() {
        _connectionConfig.value = ServerConnectionConfig(
            serverUrl = "demo.babybuddy.local", // no token: demo mode never calls a server
            isConnected = true,
            isDemoMode = true,
            detectedChildName = "Sam"
        )
        _babyProfile.value = BabyProfile(
            name = "Sam",
            shortName = "Sam",
            birthDate = "2026-01-10",
            ageFormatted = AppText.get(R.string.age_months_days, 9, 3)
        )
        loadDemoData()
    }

    fun disconnect() {
        clearRemote()
        prefs?.let { SavedLogin.clear(it) }
        _connectionConfig.value = ServerConnectionConfig()
        _feedings.value = emptyList()
        _sleeps.value = emptyList()
        sleeping.reset()
        _diapers.value = emptyList()
        _tummyTimes.value = emptyList()
        _pumpings.value = emptyList()
        _lastBreastfeedRemote.value = null
        _recentFeedsRemote.value = emptyList()
        _lastDiaperRemote.value = null
        _lastSleepEndRemote.value = null
        _lastBathRemote.value = null
        _noteTags.value = emptyList()
        stash.clear()
        timers.reset()
        _temperatures.value = emptyList()
        _notes.value = emptyList()
        _growth.value = emptyList()
        _handoff.value = null
        prefs?.edit()?.remove(PREF_AWAY_SINCE)?.apply()
        // Saved rhythm models learned this child's (or the demo's) rhythm
        predictor.clear()
        _babyProfile.value = BabyProfile(name = AppText.get(R.string.profile_my_baby), shortName = AppText.get(R.string.profile_baby), ageFormatted = AppText.get(R.string.profile_newborn))
        nursing.reset()
    }

    // --- Breastfeeding (NursingTimer): every tap is saved and synced; there is no separate save step ---

    fun tapSide(side: BreastSide) = nursing.tapSide(side)

    fun setSideSeconds(side: BreastSide, seconds: Int) = nursing.setSideSeconds(side, seconds)

    fun setFeedStart(startMillis: Long) = nursing.setFeedStart(startMillis)

    fun endFeed() = nursing.endFeed()

    fun switchActiveSide() = nursing.switchSide()

    fun togglePause() = nursing.togglePause()

    fun startNewNursingSession(side: BreastSide) = nursing.startNew(side)

    fun setLiveFeedNotes(notes: String) = nursing.setNotes(notes)

    fun liveFeedUserNotes(): String = nursing.notes()

    /** Returns the new entry's id (for undo). */
    fun addDiaperLog(event: DiaperEvent, timeMs: Long = System.currentTimeMillis()): Long = diaperStore.add(event.copy(id = nextLocalId(), timeMillis = timeMs, timeFormatted = formatClock(timeMs), caregiver = myName()))

    fun updateDiaper(updated: DiaperEvent) {
        diaperStore.update(updated)
        _lastDiaperRemote.update { if (it?.id == updated.id) updated else it }
    }

    fun deleteDiaper(id: Long) {
        diaperStore.delete(diaperStore.find(id) ?: return)
        _lastDiaperRemote.update { if (it?.id == id) null else it }
    }

    fun addFeeding(event: FeedingEvent): Long = feedStore.add(event.copy(caregiver = event.caregiver ?: myName()))

    fun logBottle(ml: Int, formula: Boolean, timeMs: Long = System.currentTimeMillis(), notes: String = ""): Long = addFeeding(
        FeedingEvent(
            id = nextLocalId(),
            timeMillis = timeMs,
            timeFormatted = formatClock(timeMs),
            type = FeedType.BOTTLE,
            bottleFormula = formula,
            amountMl = ml,
            notes = notes
        )
    )

    fun updateFeeding(event: FeedingEvent) {
        feedStore.update(event)
        // An older feed edited from History may be the "last breastfeed" (its last side drives Today and the widget)
        _lastBreastfeedRemote.update { if (it?.id == event.id) event else it }
        _recentFeedsRemote.update { list -> list.map { if (it.id == event.id) event else it }.sortedByDescending { it.timeMillis } }
    }

    fun deleteFeeding(id: Long) {
        feedStore.delete(feedStore.find(id) ?: return)
        _recentFeedsRemote.update { list -> list.filterNot { it.id == id } }
        // The server's "last breastfeed" may have been this one: fall back to the one before it
        _lastBreastfeedRemote.update { last -> if (last?.id == id) _recentFeedsRemote.value.firstOrNull { it.type == FeedType.NURSING } else last }
    }

    fun updateTummyTime(updated: TummyTimeEvent) = tummyStore.update(updated)

    fun deleteTummyTime(id: Long) = tummyStore.find(id)?.let(tummyStore::delete)

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
        return pumpingStore.add(event)
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
        return temperatureStore.add(event)
    }

    fun deleteTemperature(id: Long) = temperatureStore.find(id)?.let(temperatureStore::delete)

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
        return growthStore.add(event)
    }

    /** Saves a changed measurement (value, day or note); the day is kept as local noon like the server's date. */
    fun updateGrowth(updated: GrowthEvent) = growthStore.update(updated.copy(dateMillis = parseLocalDateNoon(localDate(updated.dateMillis)) ?: updated.dateMillis))

    /** Each kind has its own endpoint and ids, so a weight and a length can share an id. */
    fun deleteGrowth(kind: GrowthKind, id: Long) = growthStore.find(id, kind.endpoint)?.let(growthStore::delete)

    fun addNote(text: String, tags: List<String> = emptyList(), timeMillis: Long = System.currentTimeMillis()): Long = noteStore.add(NoteEvent(id = nextLocalId(), timeMillis = timeMillis, timeFormatted = formatClock(timeMillis), text = text, tags = tags))

    // --- Baths: notes tagged BATH_TAG ---

    fun logBath(timeMillis: Long, notes: String): Long = addNote(notes.ifBlank { BATH_NOTE_TEXT }, listOf(BATH_TAG), timeMillis)

    /** Saves a changed note or bath (from today or an earlier day in History); its tags are kept. */
    fun updateNote(updated: NoteEvent) = noteStore.update(updated.copy(timeFormatted = formatClock(updated.timeMillis)))

    /** Deletes a note or bath (from today or an earlier day in History). */
    fun deleteNoteEvent(note: NoteEvent) {
        noteStore.delete(note)
        // The server's "last bath" may have been this one
        if (BATH_TAG in note.tags && _lastBathRemote.value == note.timeMillis) _lastBathRemote.value = null
    }

    // --- Milk stash: the last amount someone entered (a note tagged "milk-stash", so every phone shares it)
    // plus pumped milk since then, minus breast-milk bottles since then ---

    private val stash = MilkStash(syncScope, _pumpings, _feedings)

    /** Breast milk on hand in ml, or null until someone enters an amount ([MilkStash]). */
    val milkStash: StateFlow<Int?> get() = stash.amount

    /** Records how much milk is on hand now (a note every phone sees); later pumping and bottles count from here. */
    fun setMilkStash(ml: Int): Long = addNote(stash.set(ml), listOf(MILK_STASH_TAG))

    fun undoMilkStash(noteId: Long) {
        deleteNote(noteId)
        stash.clear()
        syncScope.launch {
            delay(5_000L) // let the delete reach the server, then use the previous amount
            loadMilkStash()
        }
    }

    private suspend fun loadMilkStash() {
        session?.let { stash.load(it) }
    }

    fun deleteNote(id: Long) = noteStore.find(id)?.let(noteStore::delete)

    fun updatePumping(updated: PumpingEvent) = pumpingStore.update(updated)

    fun deletePumping(id: Long) = pumpingStore.find(id)?.let(pumpingStore::delete)

    private fun formatClock(millis: Long): String = TimeFormat.clock(millis)

    /** Returns the new entry's id (for undo), or null if the category isn't known. */
    fun addQuickLog(category: String, amount: String? = null, note: String? = null): Long? {
        val type = when (category.lowercase()) {
            "pee", "wet" -> DiaperType.WET
            "poop", "dirty" -> DiaperType.DIRTY
            "both" -> DiaperType.BOTH
            "bottle" -> return logBottle(amount?.toIntOrNull() ?: 120, formula = false)
            else -> return null
        }
        return addDiaperLog(DiaperEvent(type = type, notes = note.orEmpty()))
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
        return sleepStore.add(event)
    }

    fun updateSleep(updated: SleepEvent) = sleepStore.update(updated)

    fun deleteSleep(id: Long) = sleepStore.find(id)?.let(sleepStore::delete)

    fun startSleep(startMillis: Long = System.currentTimeMillis()) = sleeping.start(startMillis)

    fun setSleepStart(startMillis: Long) = sleeping.setStart(startMillis)

    fun setSleepNotes(notes: String) = sleeping.setNotes(notes)

    fun stopSleep(endMillis: Long = System.currentTimeMillis(), isNap: Boolean? = null): Long? = sleeping.stop(endMillis, isNap)

    fun cancelSleep() = sleeping.cancel()

    /** Mirrors the running feed and sleep to the server's timers ([SharedTimerSync]). */
    private val timers: SharedTimerSync by lazy {
        SharedTimerSync(
            scope = syncScope,
            target = { session?.target },
            feed = nursing.state,
            sleep = sleeping.state,
            setSleep = sleeping::set,
            feedings = _feedings,
            writeQueue = writeQueue,
            syncToday = { syncToday(RemoteSession(it.api, it.auth, it.child)) }
        )
    }

    /** Picks up feeds and sleeps started, changed or ended on other phones (see [SharedTimerSync.poll]). */
    suspend fun pollSharedTimers() = timers.poll()

    private fun clearRemote() {
        // Unsent entries belong to the server being left
        val dropped = writeQueue.clear()
        if (dropped > 0) _syncErrors.tryEmit(AppText.plural(R.plurals.sync_discarded, dropped, dropped))
        session = null
        store?.clear()
    }

    // --- Queue (request bodies are in ServerMapping.kt; the queue itself is WriteQueue) ---

    /** Adds a write to the queue (and disk) and starts sending; no-op in demo/offline mode. */
    private fun enqueue(write: QueuedWrite) {
        val config = _connectionConfig.value
        if (session == null || !config.isConnected || config.isDemoMode) return
        writeQueue.add(write)
    }

    private fun flushPendingWrites() = writeQueue.flush()

    /** How the write queue reaches the local entries. */
    private val localEntries = object : WriteQueue.LocalEntries {
        override fun serverIdOf(write: QueuedWrite): Long? = storeFor(write.endpoint).serverIdOf(write.endpoint, write.localId)

        override fun onCreated(write: QueuedWrite, serverId: Long) {
            if (write.endpoint == Endpoints.FEEDINGS && write.localId == nursing.state.value.feedingLocalId) {
                // Other phones following this feed can now update its record
                nursing.state.update { it.copy(feedingServerId = serverId) }
                timers.feedRecordCreated()
            }
            storeFor(write.endpoint).setServerId(write.endpoint, write.localId, serverId)
        }

        override suspend fun findExisting(write: QueuedWrite, target: WriteQueue.Target): Long? = findExistingRecord(write, target)

        override fun describe(write: QueuedWrite): String = describeWrite(write)
    }

    // --- Process-level setup: persistence, timer, widgets ---

    private var prefs: SharedPreferences? = null

    /** Called once from the Application: restores the saved login and running timer, starts the timer. */
    fun attach(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("baby_buddy", Context.MODE_PRIVATE)
        prefs = p
        settings.load(p, syncScope)
        predictor.load(context.applicationContext.getSharedPreferences(MODEL_PREFS, Context.MODE_PRIVATE))
        val offline = OfflineStore(File(context.applicationContext.filesDir, "offline"))
        store = offline
        writeQueue.store = offline
        nursing.restore(p)
        sleeping.load(p)
        timers.load(p)
        timers.start()
        nursing.start(syncScope, p)
        if (restoreConnection(p)) {
            restoreOffline(offline)
            reformatTimes() // the cache may have been saved with the other clock format
            flushPendingWrites() // retries on its own if the server isn't reachable yet
            syncScope.launch {
                val c = _connectionConfig.value
                val result = testConnection(c.serverUrl, c.apiToken, c.allowSelfSignedCert, c.cfClientId, c.cfClientSecret, c.pinnedCertSha256)
                // Logins saved before pinning trusted every certificate: keep the server's current one rather than locking them out
                val untrusted = result.exceptionOrNull() as? UntrustedCertificateException
                if (untrusted != null && c.allowSelfSignedCert && c.pinnedCertSha256.isBlank()) {
                    testConnection(c.serverUrl, c.apiToken, true, c.cfClientId, c.cfClientSecret, untrusted.fingerprint)
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
                if (session != null && config.isConnected && !config.isDemoMode) {
                    offline.saveCache(cache)
                }
            }
        }
    }

    private fun restoreOffline(offline: OfflineStore) {
        writeQueue.restore(offline.loadQueue())
        val cache = offline.loadCache() ?: return
        if (cache.day != todayKey()) return
        val startOfDay = startOfDay()
        _feedings.value = cache.feedings.filter { it.timeMillis >= startOfDay }
        _diapers.value = cache.diapers.filter { it.timeMillis >= startOfDay }
        _tummyTimes.value = cache.tummyTimes.filter { it.timeMillis >= startOfDay }
        _pumpings.value = cache.pumpings.filter { it.timeMillis >= startOfDay }
        _temperatures.value = cache.temperatures.filter { it.timeMillis >= startOfDay }
        _notes.value = cache.notes.filter { it.timeMillis >= startOfDay }
        _sleeps.value = cache.sleeps.filter { it.endMillis >= startOfDay }
    }

    private fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** Emits whenever something home-screen widgets show changes (not on every timer tick). */
    val widgetState: Flow<Any> = combine(
        combine(_feedings, lastBreastfeed) { feeds, last -> feeds to last },
        _diapers,
        _tummyTimes,
        _babyProfile,
        combine(
            liveTimerState.map { listOf(it.isRunning, it.isPaused, it.activeBreast, it.editCount) }.distinctUntilChanged(),
            modelNextTimes
        ) { timer, next -> timer to next }
    ) { feeds, diapers, tummies, profile, timer -> listOf(feeds, diapers, tummies, profile, timer) }

    /** Restores the saved login; returns false if there is none (or the token can't be decrypted). */
    private fun restoreConnection(p: SharedPreferences): Boolean {
        if (SavedLogin.isDemoMode(p)) {
            if (settings.caregiverName.value.isBlank()) settings.setCaregiverName("Sarah", save = false)
            showDemoBaby()
            return true
        }
        val login = SavedLogin.read(p) ?: return false
        // Set up writes right away so entries logged while the server is unreachable get queued
        val api = BabyBuddyApiClient.createService(login.url, login.allowSelfSigned, login.cfClientId, login.cfClientSecret, login.certPin)
        session = RemoteSession(api, RemoteSession.authHeader(login.token), login.childId)
        showLogin(login)
        login.childName?.let { name ->
            _babyProfile.update {
                it.copy(
                    name = name,
                    shortName = name.substringBefore(" "),
                    birthDate = login.birthDate ?: it.birthDate,
                    ageFormatted = login.birthDate?.let(::formatAge).orEmpty()
                )
            }
        }
        return true
    }

    companion object {

        val instance: BabyBuddyRepository by lazy { BabyBuddyRepository() }

        private const val MODEL_PREFS = "rhythm_models"
        private const val PREF_AWAY_SINCE = "away_since"
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
            caregiver = myName()
        )
        return tummyStore.add(event)
    }

    /** Re-fetches today's entries (e.g. ones another caregiver logged) and resends anything queued. */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        val remote = session ?: return@withContext false
        syncToday(remote)
        flushPendingWrites()
        pollSharedTimers()
        loadMilkStash()
        true
    }
}
