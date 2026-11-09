package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.R
import com.example.data.api.BabyBuddyApiClient
import com.example.data.api.UntrustedCertificateException
import com.example.data.api.findInChain
import com.example.data.ml.RhythmEvents
import com.example.data.ml.RhythmPrediction
import com.example.data.ml.RhythmTarget
import com.example.data.ml.SavedRhythmModel
import com.example.data.ml.trainRhythmModels
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
import com.example.data.model.isNapTime
import com.example.data.model.startOfDay
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
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
        records = FeedRecords(_feedings, nextId = ::nextLocalId, add = ::addFeeding, queueUpdate = ::queueFeedingUpdate),
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

    /** A sleep being timed on this phone; saved as a sleep entry when it ends. */
    private val _sleepTimer = MutableStateFlow(SleepTimerState())
    val sleepTimer: StateFlow<SleepTimerState> = _sleepTimer.asStateFlow()

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
        val (feeds, changes, sleeps) = if (from >= startOfDay()) {
            today
        } else {
            withContext(Dispatchers.IO) { runCatching { queries.fetchEvents(from) }.getOrNull() } ?: today
        }
        val me = myName()
        fun byOther(caregiver: String?) = if (me != null) !caregiver.equals(me, ignoreCase = true) else !caregiver.isNullOrBlank()
        val hidden = settings.hiddenItems.value
        val summary = HandoffSummary(
            sinceMillis = from,
            feedings = feeds.filter { f ->
                f.timeMillis >= from && byOther(f.caregiver) &&
                    (if (f.type == FeedType.NURSING) TrackedItem.BREASTFEEDING else TrackedItem.BOTTLE) !in hidden
            }.sortedBy { it.timeMillis },
            diapers = changes.filter { it.timeMillis >= from && byOther(it.caregiver) && TrackedItem.DIAPERS !in hidden }.sortedBy { it.timeMillis },
            sleeps = sleeps.filter { it.id != ServerQueries.RUNNING_SLEEP_ID && it.endMillis >= from && byOther(it.caregiver) && TrackedItem.SLEEP !in hidden }.sortedBy { it.timeMillis }
        )
        if (!summary.isEmpty) _handoff.value = summary
    }

    private fun myName(): String? = settings.caregiverName.value.ifBlank { null }

    /** Demo mode's simulated history (all of it; the lists hold today's part). */
    private var demoHistory: DemoHistory? = null

    fun enterDemoMode() {
        clearRemote()
        prefs?.edit()
            ?.remove(PREF_URL)?.remove(PREF_TOKEN_ENC)?.remove(PREF_CF_ID)
            ?.remove(PREF_CF_SECRET_ENC)?.remove(PREF_CERT_PIN)?.remove(PREF_CHILD_ID)
            ?.remove(PREF_CHILD_NAME)?.remove(PREF_BIRTH_DATE)
            ?.putBoolean(PREF_DEMO_MODE, true)?.apply()

        settings.setCaregiverName("Sarah")

        settings.setThemeMode(ThemeMode.LIGHT)
        showDemoBaby()
        // Show every feature: growth charts, reminders and the rhythm models (fitted on the demo data and in use)
        if (settings.babySex.value == null) settings.setBabySex(BabySex.BOY)
        if (!settings.reminders.value.anyOn) settings.setReminders(ReminderSettings(feedAfterMinutes = 180, awakeAfterMinutes = 120, sleepAfterMinutes = 150, diaperAfterMinutes = 180, earlyMinutes = 15))
        settings.setModelsTab(true)
        val demo = demoHistory ?: return
        syncScope.launch(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            val from = now - 60 * 86_400_000L
            val events = RhythmEvents(
                demo.feeds.filter { it.timeMillis >= from },
                demo.diapers.filter { it.timeMillis >= from },
                demo.sleeps.filter { it.timeMillis >= from },
                birthMillis = parseLocalDate(_babyProfile.value.birthDate) ?: from
            )
            val r = settings.reminders.value
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
        val today = startOfDay()
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
        _stashBase.value = StashBase(450, now, 0, 0)
        val temperatureAt = clockMs(0, 9, 30).let { if (it < now) it else clockMs(1, 9, 30) }
        _temperatures.value = listOf(
            TemperatureEvent(id = 900L, temperature = 36.8, timeMillis = temperatureAt, timeFormatted = formatClock(temperatureAt), caregiver = "Sarah")
        )
        settings.showAllCards()
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
            val authHeader = RemoteSession.authHeader(token)
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
                        detectedChildName = detectedName
                    )
                }

                val connected = RemoteSession(api, authHeader, detectedChild?.id)
                session = connected
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

                syncToday(connected)
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
    private suspend fun syncToday(remote: RemoteSession) {
        val api = remote.api
        val authHeader = remote.auth
        val childId = remote.child
        val since = isoUtc(startOfDay())

        runCatching {
            api.getFeedings(authHeader, childId, since).body()!!.results.map(::mapFeeding)
        }.onSuccess { remote ->
            _feedings.value = mergeWithPending(Endpoints.FEEDINGS, remote, _feedings.value, { it.id }, { it.serverId }, { it.timeMillis })
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
            _diapers.value = mergeWithPending(Endpoints.CHANGES, remote, _diapers.value, { it.id }, { it.serverId }, { it.timeMillis })
        }

        runCatching {
            api.getTummyTimes(authHeader, childId, since).body()!!.results.map(::mapTummyTime)
        }.onSuccess { remote ->
            _tummyTimes.value = mergeWithPending(Endpoints.TUMMY, remote, _tummyTimes.value, { it.id }, { it.serverId }, { it.timeMillis })
        }

        runCatching {
            api.getPumping(authHeader, childId, since).body()!!.results.map(::mapPumping)
        }.onSuccess { remote ->
            _pumpings.value = mergeWithPending(Endpoints.PUMPING, remote, _pumpings.value, { it.id }, { it.serverId }, { it.timeMillis })
        }

        runCatching {
            // Sleeps that ended today, so last night's sleep counts too
            api.getSleep(authHeader, childId, endedSince = since).body()!!.results.map(::mapSleep)
        }.onSuccess { remote ->
            _sleeps.value = mergeWithPending(Endpoints.SLEEP, remote, _sleeps.value, { it.id }, { it.serverId }, { it.timeMillis })
        }

        runCatching {
            api.getTemperatures(authHeader, childId, since).body()!!.results.map(::mapTemperature)
        }.onSuccess { remote ->
            _temperatures.value = mergeWithPending(Endpoints.TEMPERATURE, remote, _temperatures.value, { it.id }, { it.serverId }, { it.timeMillis })
        }

        runCatching {
            api.getNotes(authHeader, childId, since).body()!!.results.map(::mapNote)
        }.onSuccess { remote ->
            _notes.value = mergeWithPending(Endpoints.NOTES, remote, _notes.value, { it.id }, { it.serverId }, { it.timeMillis })
        }

        if (TrackedItem.GROWTH !in settings.hiddenItems.value) {
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
        _connectionConfig.update { it.copy(lastSyncMillis = syncedAt) }
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
        val pending = writeQueue.snapshot.filter { it.endpoint == endpoint }
        val touchedLocalIds = pending.map { it.localId }.toSet()
        val deletedServerIds = pending.filter { it.action == QueuedWrite.ACTION_DELETE }
            .mapNotNull { it.serverId ?: writeQueue.knownServerId(it.localId) ?: it.localId }.toSet()
        val keepLocal = local.filter { id(it) in touchedLocalIds }
        val shadowed = keepLocal.mapNotNull(serverId).toSet() + keepLocal.map(id)
        val keepRemote = remote.filter { r -> serverId(r).let { it !in deletedServerIds && it !in shadowed } }
        return (keepRemote + keepLocal).sortedByDescending(time)
    }

    // Mapping server records to entries and back, and the generated note segments: ServerMapping.kt

    /** Read-only range queries for History, Trends and the rhythm models ([ServerQueries]). */
    private val queries = ServerQueries(
        session = { session },
        demo = { demoHistory.takeIf { _connectionConfig.value.isDemoMode } },
        sleepTimer = { _sleepTimer.value },
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
    fun addDiaperLog(event: DiaperEvent, timeMs: Long = System.currentTimeMillis()): Long {
        val local = event.copy(id = nextLocalId(), timeMillis = timeMs, timeFormatted = formatClock(timeMs), caregiver = myName())
        _diapers.update { (listOf(local) + it).sortedByDescending { e -> e.timeMillis } }
        enqueue(QueuedWrite(Endpoints.CHANGES, QueuedWrite.ACTION_CREATE, local.id, body = changeBody(local)))
        return local.id
    }

    fun updateDiaper(updated: DiaperEvent) {
        _diapers.update { list -> list.map { if (it.id == updated.id) updated else it }.sortedByDescending { it.timeMillis } }
        _lastDiaperRemote.update { if (it?.id == updated.id) updated else it }
        enqueue(QueuedWrite(Endpoints.CHANGES, QueuedWrite.ACTION_UPDATE, updated.id, knownServerId(updated.id, updated.serverId), changeBody(updated)))
    }

    fun deleteDiaper(id: Long) {
        val event = _diapers.value.firstOrNull { it.id == id } ?: return
        _diapers.update { list -> list.filterNot { it.id == id } }
        _lastDiaperRemote.update { if (it?.id == id) null else it }
        enqueue(QueuedWrite(Endpoints.CHANGES, QueuedWrite.ACTION_DELETE, id, knownServerId(id, event.serverId)))
    }

    fun addFeeding(event: FeedingEvent): Long {
        val local = event.copy(caregiver = event.caregiver ?: myName())
        _feedings.update { (listOf(local) + it).sortedByDescending { e -> e.timeMillis } }
        enqueue(QueuedWrite(Endpoints.FEEDINGS, QueuedWrite.ACTION_CREATE, local.id, body = feedingBody(local)))
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
            notes = notes
        )
    )

    fun updateFeeding(event: FeedingEvent) {
        _feedings.update { list -> list.map { if (it.id == event.id) event else it }.sortedByDescending { it.timeMillis } }
        // An older feed edited from History may be the "last breastfeed" (its last side drives Today and the widget)
        _lastBreastfeedRemote.update { if (it?.id == event.id) event else it }
        _recentFeedsRemote.update { list -> list.map { if (it.id == event.id) event else it }.sortedByDescending { it.timeMillis } }
        queueFeedingUpdate(event)
    }

    private fun queueFeedingUpdate(event: FeedingEvent) = enqueue(QueuedWrite(Endpoints.FEEDINGS, QueuedWrite.ACTION_UPDATE, event.id, knownServerId(event.id, event.serverId), feedingBody(event)))

    fun deleteFeeding(id: Long) {
        val event = _feedings.value.firstOrNull { it.id == id } ?: return
        _feedings.update { list -> list.filterNot { it.id == id } }
        _recentFeedsRemote.update { list -> list.filterNot { it.id == id } }
        // The server's "last breastfeed" may have been this one: fall back to the one before it
        _lastBreastfeedRemote.update { last -> if (last?.id == id) _recentFeedsRemote.value.firstOrNull { it.type == FeedType.NURSING } else last }
        enqueue(QueuedWrite(Endpoints.FEEDINGS, QueuedWrite.ACTION_DELETE, id, knownServerId(id, event.serverId)))
    }

    fun updateTummyTime(updated: TummyTimeEvent) {
        _tummyTimes.update { list -> list.map { if (it.id == updated.id) updated else it }.sortedByDescending { it.timeMillis } }
        enqueue(QueuedWrite(Endpoints.TUMMY, QueuedWrite.ACTION_UPDATE, updated.id, knownServerId(updated.id, updated.serverId), tummyBody(updated)))
    }

    fun deleteTummyTime(id: Long) {
        val event = _tummyTimes.value.firstOrNull { it.id == id } ?: return
        _tummyTimes.update { list -> list.filterNot { it.id == id } }
        enqueue(QueuedWrite(Endpoints.TUMMY, QueuedWrite.ACTION_DELETE, id, knownServerId(id, event.serverId)))
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
        enqueue(QueuedWrite(Endpoints.PUMPING, QueuedWrite.ACTION_CREATE, event.id, body = pumpingBody(event)))
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
        enqueue(QueuedWrite(Endpoints.TEMPERATURE, QueuedWrite.ACTION_CREATE, event.id, body = temperatureBody(event)))
        return event.id
    }

    fun deleteTemperature(id: Long) {
        val event = _temperatures.value.firstOrNull { it.id == id } ?: return
        _temperatures.update { list -> list.filterNot { it.id == id } }
        enqueue(QueuedWrite(Endpoints.TEMPERATURE, QueuedWrite.ACTION_DELETE, id, knownServerId(id, event.serverId)))
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
        enqueue(QueuedWrite(Endpoints.NOTES, QueuedWrite.ACTION_CREATE, event.id, body = body))
        return event.id
    }

    // --- Baths: notes tagged BATH_TAG ---

    fun logBath(timeMillis: Long, notes: String): Long = addNote(notes.ifBlank { BATH_NOTE_TEXT }, listOf(BATH_TAG), timeMillis)

    /** Saves a changed note or bath (from today or an earlier day in History); its tags are kept. */
    fun updateNote(updated: NoteEvent) {
        val event = updated.copy(timeFormatted = formatClock(updated.timeMillis))
        _notes.update { list -> list.map { if (it.id == event.id) event else it }.sortedByDescending { it.timeMillis } }
        val body = mapOf("note" to event.text, "time" to isoUtc(event.timeMillis), "tags" to event.tags)
        enqueue(QueuedWrite(Endpoints.NOTES, QueuedWrite.ACTION_UPDATE, event.id, knownServerId(event.id, event.serverId), body))
    }

    /** Deletes a note or bath (from today or an earlier day in History). */
    fun deleteNoteEvent(note: NoteEvent) {
        _notes.update { list -> list.filterNot { it.id == note.id } }
        enqueue(QueuedWrite(Endpoints.NOTES, QueuedWrite.ACTION_DELETE, note.id, knownServerId(note.id, note.serverId)))
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
            val from = maxOf(base.sinceMillis, startOfDay())
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
        val remote = session ?: return
        val api = remote.api
        val child = remote.child ?: return
        runCatching {
            val since = isoUtc(System.currentTimeMillis() - 180L * 24 * 60 * 60_000L)
            val note = api.getNotes(remote.auth, child, since = since, limit = 1000).body()!!.results
                .filter { MILK_STASH_TAG in it.tags.orEmpty() }
                .maxByOrNull { it.time?.let(::parseServerTime)?.time ?: 0L }
                ?: return@runCatching null
            val ml = Regex("""(\d+)""").find(note.note.orEmpty())?.value?.toIntOrNull() ?: return@runCatching null
            val sinceMillis = note.time?.let(::parseServerTime)?.time ?: return@runCatching null
            val todayStart = startOfDay()
            if (sinceMillis >= todayStart) return@runCatching StashBase(ml, sinceMillis, 0, 0)
            val from = isoUtc(sinceMillis)
            val pumped = api.getPumping(remote.auth, child, since = from, limit = 1000).body()!!.results
                .map(::mapPumping).filter { it.timeMillis in sinceMillis until todayStart }.sumOf { it.amountMl }
            val given = api.getFeedings(remote.auth, child, since = from, limit = 1000).body()!!.results
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
        enqueue(QueuedWrite(Endpoints.NOTES, QueuedWrite.ACTION_DELETE, id, knownServerId(id, event.serverId)))
    }

    fun updatePumping(updated: PumpingEvent) {
        _pumpings.update { list -> list.map { if (it.id == updated.id) updated else it }.sortedByDescending { it.timeMillis } }
        enqueue(QueuedWrite(Endpoints.PUMPING, QueuedWrite.ACTION_UPDATE, updated.id, knownServerId(updated.id, updated.serverId), pumpingBody(updated)))
    }

    fun deletePumping(id: Long) {
        val event = _pumpings.value.firstOrNull { it.id == id } ?: return
        _pumpings.update { list -> list.filterNot { it.id == id } }
        enqueue(QueuedWrite(Endpoints.PUMPING, QueuedWrite.ACTION_DELETE, id, knownServerId(id, event.serverId)))
    }

    private fun knownServerId(localId: Long, serverId: Long?): Long? = serverId ?: writeQueue.knownServerId(localId)

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
                        notes = note.orEmpty()
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
                        notes = note.orEmpty()
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
                        notes = note.orEmpty()
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
        enqueue(QueuedWrite(Endpoints.SLEEP, QueuedWrite.ACTION_CREATE, event.id, body = sleepBody(event)))
        return event.id
    }

    fun updateSleep(updated: SleepEvent) {
        _sleeps.update { list -> list.map { if (it.id == updated.id) updated else it }.sortedByDescending { it.timeMillis } }
        enqueue(QueuedWrite(Endpoints.SLEEP, QueuedWrite.ACTION_UPDATE, updated.id, knownServerId(updated.id, updated.serverId), sleepBody(updated)))
    }

    fun deleteSleep(id: Long) {
        val event = _sleeps.value.firstOrNull { it.id == id } ?: return
        _sleeps.update { list -> list.filterNot { it.id == id } }
        enqueue(QueuedWrite(Endpoints.SLEEP, QueuedWrite.ACTION_DELETE, id, knownServerId(id, event.serverId)))
    }

    fun startSleep(startMillis: Long = System.currentTimeMillis()) {
        if (_sleepTimer.value.isRunning) return
        setSleepTimer(SleepTimerState(startMillis = startMillis, changedAtMillis = nextChangeMillis(_sleepTimer.value.changedAtMillis)))
        timers.sleepChanged()
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
        timers.sleepChanged()
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
        timers.sleepEnded()
    }

    private fun setSleepTimer(state: SleepTimerState) {
        _sleepTimer.value = state
        prefs?.edit()
            ?.putLong(PREF_SLEEP_START, state.startMillis)
            ?.putString(PREF_SLEEP_NOTES, state.notes)
            ?.putLong(PREF_SLEEP_CHANGED, state.changedAtMillis)
            ?.apply()
    }

    /** Mirrors the running feed and sleep to the server's timers ([SharedTimerSync]). */
    private val timers: SharedTimerSync by lazy {
        SharedTimerSync(
            scope = syncScope,
            target = { session?.target },
            feed = nursing.state,
            sleep = _sleepTimer,
            setSleep = ::setSleepTimer,
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
        override fun serverIdOf(write: QueuedWrite): Long? = localStore(write.endpoint).serverIdOf(write.localId)

        override fun onCreated(write: QueuedWrite, serverId: Long) {
            if (write.endpoint == Endpoints.FEEDINGS && write.localId == nursing.state.value.feedingLocalId) {
                // Other phones following this feed can now update its record
                nursing.state.update { it.copy(feedingServerId = serverId) }
                timers.feedRecordCreated()
            }
            localStore(write.endpoint).setServerId(write.localId, serverId)
        }

        override suspend fun findExisting(write: QueuedWrite, target: WriteQueue.Target): Long? = findExistingRecord(write, target)

        override fun describe(write: QueuedWrite): String = describeWrite(write)
    }

    /** One endpoint's local entries, for filling in and looking up server ids. */
    private class LocalStore<T>(
        private val flow: MutableStateFlow<List<T>>,
        private val localId: (T) -> Long,
        private val serverId: (T) -> Long?,
        private val withServerId: (T, Long) -> T,
        private val sameKind: (T) -> Boolean = { true } // growth: one flow for several endpoints
    ) {
        fun serverIdOf(id: Long): Long? = flow.value.firstOrNull { localId(it) == id && sameKind(it) }?.let(serverId)

        fun setServerId(id: Long, server: Long) = flow.update { list -> list.map { if (localId(it) == id && sameKind(it)) withServerId(it, server) else it } }
    }

    private fun localStore(endpoint: String): LocalStore<*> = when (endpoint) {
        Endpoints.FEEDINGS -> LocalStore(_feedings, { it.id }, { it.serverId }, { e, id -> e.copy(serverId = id) })
        Endpoints.CHANGES -> LocalStore(_diapers, { it.id }, { it.serverId }, { e, id -> e.copy(serverId = id) })
        Endpoints.PUMPING -> LocalStore(_pumpings, { it.id }, { it.serverId }, { e, id -> e.copy(serverId = id) })
        Endpoints.TEMPERATURE -> LocalStore(_temperatures, { it.id }, { it.serverId }, { e, id -> e.copy(serverId = id) })
        Endpoints.NOTES -> LocalStore(_notes, { it.id }, { it.serverId }, { e, id -> e.copy(serverId = id) })
        Endpoints.SLEEP -> LocalStore(_sleeps, { it.id }, { it.serverId }, { e, id -> e.copy(serverId = id) })
        in Endpoints.GROWTH -> LocalStore(_growth, { it.id }, { it.serverId }, { e, id -> e.copy(serverId = id) }) { it.kind.endpoint == endpoint }
        else -> LocalStore(_tummyTimes, { it.id }, { it.serverId }, { e, id -> e.copy(serverId = id) })
    }

    // --- Process-level setup: persistence, timer, widgets ---

    private var prefs: SharedPreferences? = null

    /** Called once from the Application: restores the saved login and running timer, starts the timer. */
    fun attach(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("baby_buddy", Context.MODE_PRIVATE)
        prefs = p
        p.edit().remove(LEGACY_PREF_TOKEN).apply() // earlier builds stored the token in plain text
        settings.load(p, syncScope)
        predictor.load(context.applicationContext.getSharedPreferences(MODEL_PREFS, Context.MODE_PRIVATE))
        val offline = OfflineStore(File(context.applicationContext.filesDir, "offline"))
        store = offline
        writeQueue.store = offline
        nursing.restore(p)
        p.getLong(PREF_SLEEP_START, 0L).takeIf { it > 0 }?.let { start ->
            _sleepTimer.value = SleepTimerState(start, p.getString(PREF_SLEEP_NOTES, "").orEmpty(), p.getLong(PREF_SLEEP_CHANGED, 0L))
        }
        timers.load(p)
        timers.start()
        nursing.start(syncScope, p)
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
                if (session != null && config.isConnected && !config.isDemoMode) {
                    offline.saveCache(cache)
                }
            }
        }
    }

    private var restoredToken: String? = null

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
        if (p.getBoolean(PREF_DEMO_MODE, false)) {
            if (settings.caregiverName.value.isBlank()) settings.setCaregiverName("Sarah", save = false)
            showDemoBaby()
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
        session = RemoteSession(BabyBuddyApiClient.createService(url, selfSigned, cfId, cfSecret, certPin), RemoteSession.authHeader(token), childId)
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
                detectedChildName = p.getString(PREF_CHILD_NAME, "").orEmpty()
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

    companion object {

        val instance: BabyBuddyRepository by lazy { BabyBuddyRepository() }

        private const val PREF_URL = "server_url"
        private const val PREF_TOKEN_ENC = "api_token_enc"
        private const val LEGACY_PREF_TOKEN = "api_token"
        private const val PREF_SLEEP_CHANGED = "sleep_timer_changed_at"
        private const val MODEL_PREFS = "rhythm_models"
        private const val PREF_SLEEP_START = "sleep_timer_start"
        private const val PREF_SLEEP_NOTES = "sleep_timer_notes"
        private const val PREF_AWAY_SINCE = "away_since"
        private const val PREF_SELF_SIGNED = "allow_self_signed"
        private const val PREF_CERT_PIN = "pinned_cert_sha256"
        private const val PREF_CF_ID = "cf_access_client_id"
        private const val PREF_CF_SECRET_ENC = "cf_access_client_secret_enc"
        private const val PREF_CHILD_ID = "child_id"
        private const val PREF_CHILD_NAME = "child_name"
        private const val PREF_BIRTH_DATE = "birth_date"
        private const val PREF_DEMO_MODE = "is_demo_mode"
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
        enqueue(QueuedWrite(Endpoints.TUMMY, QueuedWrite.ACTION_CREATE, event.id, body = tummyBody(event)))
        return event.id
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
