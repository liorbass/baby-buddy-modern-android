package com.example.data.repository

import android.content.SharedPreferences
import com.example.data.model.BreastSide
import com.example.data.model.FeedingEvent
import com.example.data.model.LiveTimerState
import com.example.data.model.SleepTimerState
import com.example.data.model.TimeFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** A change time later than now and than the last known change, so it wins even if this phone's clock is behind. */
internal fun nextChangeMillis(previous: Long): Long = maxOf(System.currentTimeMillis(), previous + 1)

/**
 * Shared timers: a running feed or sleep is mirrored to a Baby Buddy timer (names encoded by [SharedTimers]),
 * so every phone shows it and can continue or end it. Newest change wins.
 *
 * Local changes call [feedChanged] / [sleepChanged] (or [feedEnded] / [sleepEnded]); a conflated channel per
 * timer then creates, renames or deletes the server timer. [poll] adopts newer remote state and ends a feed or
 * sleep whose timer was removed on another phone.
 */
internal class SharedTimerSync(
    private val scope: CoroutineScope,
    private val target: () -> WriteQueue.Target?,
    private val feed: MutableStateFlow<LiveTimerState>,
    private val sleep: StateFlow<SleepTimerState>,
    private val setSleep: (SleepTimerState) -> Unit,
    private val feedings: StateFlow<List<FeedingEvent>>,
    private val writeQueue: WriteQueue,
    /** Re-fetches today's entries (the feed/sleep finished on another phone). */
    private val syncToday: suspend (WriteQueue.Target) -> Unit
) {
    private var prefs: SharedPreferences? = null

    private var feedTimerRemoteId: Long? = null
    private var sleepTimerRemoteId: Long? = null

    // The timer of a feed/sleep that ended here, until its delete goes through. Kept apart from the
    // current id so a new feed never takes it as its own (its 404 or absence would end the new feed).
    private var feedTimerToDelete: Long? = null
    private var sleepTimerToDelete: Long? = null
    private var feedEndedAt = 0L // a remote snapshot older than this is a feed already ended here
    private var sleepEndedAt = 0L
    private val feedTimerDirty = Channel<Unit>(Channel.CONFLATED)
    private val sleepTimerDirty = Channel<Unit>(Channel.CONFLATED)
    private val timerMutex = Mutex()

    /** Restores the saved timer ids and end times (at app start). */
    fun load(p: SharedPreferences) {
        prefs = p
        feedTimerRemoteId = p.getLong(PREF_FEED_TIMER_ID, -1L).takeIf { it >= 0 }
        sleepTimerRemoteId = p.getLong(PREF_SLEEP_TIMER_ID, -1L).takeIf { it >= 0 }
        feedTimerToDelete = p.getLong(PREF_FEED_TIMER_TO_DELETE, -1L).takeIf { it >= 0 }
        sleepTimerToDelete = p.getLong(PREF_SLEEP_TIMER_TO_DELETE, -1L).takeIf { it >= 0 }
        feedEndedAt = p.getLong(PREF_FEED_ENDED_AT, 0L)
        sleepEndedAt = p.getLong(PREF_SLEEP_ENDED_AT, 0L)
    }

    /** Starts sending local changes to the server. */
    fun start() {
        scope.launch { for (signal in feedTimerDirty) timerMutex.withLock { pushFeedTimer() } }
        scope.launch { for (signal in sleepTimerDirty) timerMutex.withLock { pushSleepTimer() } }
    }

    /** Signed out: forget the server's timers. */
    fun reset() {
        setFeedTimerRemoteId(null)
        setSleepTimerRemoteId(null)
        setFeedTimerToDelete(null)
        setSleepTimerToDelete(null)
    }

    /** The running feed changed here: stamp it as the newest state and push it. */
    fun feedChanged() {
        feed.update { it.copy(changedAtMillis = nextChangeMillis(it.changedAtMillis)) }
        feedTimerDirty.trySend(Unit)
    }

    /** The feed's record got its server id, so other phones following it can update it: push it again. */
    fun feedRecordCreated() {
        feedTimerDirty.trySend(Unit)
    }

    /** The feed ended here (the timer state is already cleared): delete its timer. */
    fun feedEnded() {
        feedEndedAt = System.currentTimeMillis()
        prefs?.edit()?.putLong(PREF_FEED_ENDED_AT, feedEndedAt)?.apply()
        feedTimerDirty.trySend(Unit)
    }

    /** The running sleep changed here (already stamped with [nextChangeMillis]): push it. */
    fun sleepChanged() {
        sleepTimerDirty.trySend(Unit)
    }

    /** The sleep ended here (the timer state is already cleared): delete its timer. */
    fun sleepEnded() {
        sleepEndedAt = System.currentTimeMillis()
        prefs?.edit()?.putLong(PREF_SLEEP_ENDED_AT, sleepEndedAt)?.apply()
        sleepTimerDirty.trySend(Unit)
    }

    private fun setFeedTimerRemoteId(id: Long?) {
        feedTimerRemoteId = id
        prefs?.edit()?.putLong(PREF_FEED_TIMER_ID, id ?: -1L)?.apply()
    }

    private fun setSleepTimerRemoteId(id: Long?) {
        sleepTimerRemoteId = id
        prefs?.edit()?.putLong(PREF_SLEEP_TIMER_ID, id ?: -1L)?.apply()
    }

    private fun setFeedTimerToDelete(id: Long?) {
        feedTimerToDelete = id
        prefs?.edit()?.putLong(PREF_FEED_TIMER_TO_DELETE, id ?: -1L)?.apply()
    }

    private fun setSleepTimerToDelete(id: Long?) {
        sleepTimerToDelete = id
        prefs?.edit()?.putLong(PREF_SLEEP_TIMER_TO_DELETE, id ?: -1L)?.apply()
    }

    private fun retryTimerPush(channel: Channel<Unit>) {
        scope.launch {
            delay(15_000L)
            channel.trySend(Unit)
        }
    }

    private fun feedSnapshot(t: LiveTimerState) = FeedSnapshot(
        feedingServerId = t.feedingServerId ?: t.feedingLocalId?.let { id ->
            writeQueue.knownServerId(id) ?: feedings.value.firstOrNull { it.id == id }?.serverId
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
        val t = feed.value
        val running = t.isRunning && t.feedingLocalId != null
        pushTimer(
            name = if (running) SharedTimers.encodeFeed(feedSnapshot(t)) else null,
            startMillis = t.sessionStartMillis,
            remoteId = feedTimerRemoteId,
            isOurs = { SharedTimers.decodeFeed(it) != null },
            setRemoteId = ::setFeedTimerRemoteId,
            toDelete = feedTimerToDelete,
            setToDelete = ::setFeedTimerToDelete,
            channel = feedTimerDirty,
            onGone = { feedEndedElsewhere(System.currentTimeMillis()) }
        )
    }

    private suspend fun pushSleepTimer() {
        val t = sleep.value
        pushTimer(
            name = if (t.isRunning) SharedTimers.encodeSleep(SleepSnapshot(t.startMillis, t.changedAtMillis)) else null,
            startMillis = t.startMillis,
            remoteId = sleepTimerRemoteId,
            isOurs = { SharedTimers.decodeSleep(it) != null },
            setRemoteId = ::setSleepTimerRemoteId,
            toDelete = sleepTimerToDelete,
            setToDelete = ::setSleepTimerToDelete,
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
        toDelete: Long?,
        setToDelete: (Long?) -> Unit,
        channel: Channel<Unit>,
        onGone: () -> Unit
    ) {
        val server = target() ?: return
        val api = server.api
        val auth = server.auth
        var pendingDelete = toDelete
        var currentId = remoteId
        if (name == null && currentId != null) {
            // The feed/sleep ended: its timer is no longer the current one, even before the delete succeeds
            pendingDelete = currentId
            setToDelete(currentId)
            setRemoteId(null)
            currentId = null
        }
        try {
            if (pendingDelete != null) {
                val response = api.deleteRecord(auth, ENDPOINT_TIMERS, pendingDelete)
                when {
                    response.isSuccessful || response.code() == 404 -> setToDelete(null)

                    response.code() >= 500 -> {
                        retryTimerPush(channel)
                        return
                    }

                    else -> setToDelete(null) // refused for good; don't keep retrying
                }
            }
            if (name == null) return
            // Reuse our timer, or one another phone made, before creating a new one
            val id = currentId ?: api.getTimers(auth, server.child).body()?.results.orEmpty()
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
                if (id == currentId) {
                    // Our timer was deleted: the feed/sleep was ended on another phone. Recreating it would bring
                    // it back on every phone, so end it here too and fetch the finished entry.
                    onGone()
                    syncToday(server)
                    return
                }
            }
            // The server rejects starts in the future, so allow for this phone's clock being ahead
            val now = System.currentTimeMillis()
            val start = minOf(startMillis.takeIf { it > 0 } ?: now, now - 60_000L)
            val response = api.createRecord(auth, ENDPOINT_TIMERS, mapOf("child" to server.child, "name" to name, "start" to isoUtc(start)))
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
    suspend fun poll() {
        val server = target() ?: return
        // A timer pushed while the list below is being fetched is missing from it. Only a timer that was
        // already ours before the fetch, and still is, can have been ended elsewhere.
        val feedIdAtFetch = feedTimerRemoteId
        val sleepIdAtFetch = sleepTimerRemoteId
        val timers = try {
            server.api.getTimers(server.auth, server.child).takeIf { it.isSuccessful }?.body()?.results
        } catch (e: Exception) {
            null
        } ?: return
        val remoteFeed = timers.firstNotNullOfOrNull { dto -> SharedTimers.decodeFeed(dto.name)?.let { dto.id to it } }
        val remoteSleep = timers.firstNotNullOfOrNull { dto -> SharedTimers.decodeSleep(dto.name)?.let { dto.id to it } }

        // A feed started elsewhere: make sure its record is in the local list so taps here update it
        val feedServerId = remoteFeed?.second?.feedingServerId
        if (feedServerId != null && feedings.value.none { it.serverId == feedServerId }) {
            syncToday(server)
        }

        val endedElsewhere = timerMutex.withLock {
            val now = System.currentTimeMillis()
            // Both run: a feed and a sleep may change on the same poll
            val feedEnded = applyRemoteFeed(remoteFeed, feedIdAtFetch, now)
            val sleepEnded = applyRemoteSleep(remoteSleep, sleepIdAtFetch, now)
            feedEnded || sleepEnded
        }
        if (endedElsewhere) syncToday(server) // fetch the finished feed/sleep
    }

    /**
     * The shared feed timer as just fetched ([remote]: its id and state, or null when there is none): adopt newer
     * remote state, or end the local feed when our timer is gone. Returns whether it ended elsewhere.
     * Called under [timerMutex].
     */
    private fun applyRemoteFeed(remote: Pair<Long?, FeedSnapshot>?, feedIdAtFetch: Long?, now: Long): Boolean {
        val local = feed.value
        if (remote == null) {
            if (!local.isRunning || feedTimerRemoteId == null || feedTimerRemoteId != feedIdAtFetch) return false
            feedEndedElsewhere(now)
            return true
        }
        val (remoteId, snap) = remote
        val fresh = now - snap.changedAtMillis < 6 * 60 * 60_000L // ignore timers abandoned long ago
        if (fresh && snap.changedAtMillis > local.changedAtMillis && snap.changedAtMillis > feedEndedAt) {
            adoptFeed(remoteId, snap, now)
        } else if (local.isRunning && snap.feedingServerId != null && feedings.value.none { it.id == local.feedingLocalId }) {
            // Adopted before its record arrived: link to it now
            feedings.value.firstOrNull { it.serverId == snap.feedingServerId }?.let { record ->
                feed.update { it.copy(feedingLocalId = record.id, feedingServerId = record.serverId) }
            }
        }
        return false
    }

    /** Like [applyRemoteFeed], for the shared sleep timer. Called under [timerMutex]. */
    private fun applyRemoteSleep(remote: Pair<Long?, SleepSnapshot>?, sleepIdAtFetch: Long?, now: Long): Boolean {
        val localSleep = sleep.value
        if (remote == null) {
            if (!localSleep.isRunning || sleepTimerRemoteId == null || sleepTimerRemoteId != sleepIdAtFetch) return false
            sleepEndedElsewhere(now)
            return true
        }
        val (remoteId, snap) = remote
        val fresh = now - snap.changedAtMillis < 24 * 60 * 60_000L
        if (fresh && snap.changedAtMillis > localSleep.changedAtMillis && snap.changedAtMillis > sleepEndedAt) {
            setSleep(
                SleepTimerState(
                    startMillis = snap.startMillis,
                    notes = if (localSleep.startMillis == snap.startMillis) localSleep.notes else "",
                    changedAtMillis = snap.changedAtMillis
                )
            )
            setSleepTimerRemoteId(remoteId)
        }
        return false
    }

    /**
     * The feed's shared timer is gone: another phone ended it and saved its final times. Stop here without
     * writing anything, and drop this phone's queued minute-saves, which would overwrite those times.
     */
    private fun feedEndedElsewhere(now: Long) {
        val local = feed.value
        local.feedingLocalId?.let { writeQueue.dropUpdates(ENDPOINT_FEEDINGS, it) }
        setFeedTimerRemoteId(null)
        feedEndedAt = now
        feed.value = LiveTimerState(softChimeEnabled = local.softChimeEnabled)
    }

    /** The sleep's shared timer is gone: it was stopped (and saved) on another phone. */
    private fun sleepEndedElsewhere(now: Long) {
        setSleepTimerRemoteId(null)
        setSleep(SleepTimerState())
        sleepEndedAt = now
    }

    /** Takes over a feed from another phone, counting the running side forward from the snapshot. */
    private fun adoptFeed(remoteId: Long?, snap: FeedSnapshot, now: Long) {
        val local = feed.value
        val elapsed = if (snap.paused) 0 else ((now - snap.changedAtMillis) / 1000L).toInt().coerceAtLeast(0)
        val record = snap.feedingServerId?.let { sid -> feedings.value.firstOrNull { it.serverId == sid } }
        feed.value = LiveTimerState(
            isRunning = true,
            isPaused = snap.paused,
            activeBreast = snap.side,
            leftSeconds = snap.leftSeconds + if (snap.side == BreastSide.LEFT) elapsed else 0,
            rightSeconds = snap.rightSeconds + if (snap.side == BreastSide.RIGHT) elapsed else 0,
            sessionStartedTime = TimeFormat.clock(snap.startMillis),
            softChimeEnabled = local.softChimeEnabled,
            // Without the record yet, a placeholder id; it's linked on a later poll
            feedingLocalId = record?.id ?: local.feedingLocalId?.takeIf { local.isRunning } ?: newLocalId(),
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

    private companion object {
        const val ENDPOINT_TIMERS = "timers"
        const val ENDPOINT_FEEDINGS = "feedings"
        const val PREF_FEED_TIMER_ID = "feed_timer_remote_id"
        const val PREF_SLEEP_TIMER_ID = "sleep_timer_remote_id"
        const val PREF_FEED_TIMER_TO_DELETE = "feed_timer_to_delete"
        const val PREF_SLEEP_TIMER_TO_DELETE = "sleep_timer_to_delete"
        const val PREF_FEED_ENDED_AT = "feed_ended_at"
        const val PREF_SLEEP_ENDED_AT = "sleep_ended_at"
    }
}
