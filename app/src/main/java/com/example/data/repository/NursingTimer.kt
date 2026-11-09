package com.example.data.repository

import android.content.SharedPreferences
import com.example.data.model.BreastSide
import com.example.data.model.FeedType
import com.example.data.model.FeedingEvent
import com.example.data.model.LiveTimerState
import com.example.data.model.TimeFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How the nursing timer reaches the feeding entries: the list it edits, and how new and changed entries get saved. */
class FeedRecords(
    val feedings: MutableStateFlow<List<FeedingEvent>>,
    val nextId: () -> Long,
    val add: (FeedingEvent) -> Long,
    val queueUpdate: (FeedingEvent) -> Unit
)

/**
 * The breastfeeding timer. There is no save step: the first tap creates the feed's record, and every tap, edit and
 * elapsed minute updates it. [onChanged]/[onEnded] tell the shared timer ([SharedTimerSync]) to follow.
 */
class NursingTimer(
    private val records: FeedRecords,
    private val onChanged: () -> Unit,
    private val onEnded: () -> Unit
) {
    /** The running feed (also written by [SharedTimerSync] when another phone changes it). */
    val state = MutableStateFlow(LiveTimerState())

    /**
     * Left/Right button: starts (or resumes) the feed on [side], switches to it, or pauses it if it's the running side.
     * A feed paused for longer than [SESSION_TIMEOUT_MS] has ended, so the next tap starts a new one.
     */
    fun tapSide(side: BreastSide) {
        val t = state.value
        val now = System.currentTimeMillis()
        val sessionOver = !t.isRunning || t.feedingLocalId == null || (t.isPaused && now - t.pausedAtMillis > SESSION_TIMEOUT_MS)
        when {
            sessionOver -> startFeedOn(side, now)
            !t.isPaused && t.activeBreast == side -> state.value = t.copy(isPaused = true, pausedAtMillis = now, lastActiveMillis = 0L)
            else -> state.value = t.copy(isPaused = false, activeBreast = side, pausedAtMillis = 0L, lastActiveMillis = 0L)
        }
        changed()
    }

    /** Switches to the other side (widget and notification button). */
    fun switchSide() = tapSide(if (state.value.activeBreast == BreastSide.LEFT) BreastSide.RIGHT else BreastSide.LEFT)

    /** Pauses or resumes the running side. */
    fun togglePause() = tapSide(state.value.activeBreast)

    /** Ends any running feed and starts a new one on [side]. */
    fun startNew(side: BreastSide) {
        if (state.value.feedingLocalId != null) endFeed()
        tapSide(side)
    }

    /** Manually corrects one side's time on the running feed (e.g. the timer was started late). */
    fun setSideSeconds(side: BreastSide, seconds: Int) {
        val t = state.value
        if (!t.isRunning || t.feedingLocalId == null) return
        val s = seconds.coerceAtLeast(0)
        state.value = when (side) {
            BreastSide.LEFT -> t.copy(leftSeconds = s, editCount = t.editCount + 1)
            else -> t.copy(rightSeconds = s, editCount = t.editCount + 1)
        }
        changed()
    }

    /**
     * Moves the running feed's start. Moving it earlier means the timer was started late, so the
     * difference goes to the side the feed began on (and comes off it when moved later).
     */
    fun setFeedStart(startMillis: Long) {
        val t = state.value
        if (!t.isRunning || t.feedingLocalId == null || t.sessionStartMillis <= 0L) return
        val newStart = startMillis.coerceAtMost(finishMillis(t))
        val delta = ((t.sessionStartMillis - newStart) / 1000L).toInt()
        val first = t.firstSide ?: t.activeBreast
        state.value = t.copy(
            sessionStartMillis = newStart,
            sessionStartedTime = TimeFormat.clock(newStart),
            leftSeconds = if (first == BreastSide.LEFT) (t.leftSeconds + delta).coerceAtLeast(0) else t.leftSeconds,
            rightSeconds = if (first == BreastSide.RIGHT) (t.rightSeconds + delta).coerceAtLeast(0) else t.rightSeconds,
            editCount = t.editCount + 1
        )
        changed()
    }

    /** Ends the current feed now (it's already saved), so the next tap starts a new one. */
    fun endFeed() {
        syncRecord()
        state.value = LiveTimerState()
        onEnded()
    }

    /** Forgets the running feed without saving anything (signing out). */
    fun reset() {
        state.value = LiveTimerState()
    }

    /** The running feed's note (what the user wrote, without the generated segments). */
    fun notes(): String = state.value.feedingLocalId?.let { id -> records.feedings.value.firstOrNull { it.id == id }?.notes }.orEmpty()

    /** Sets the running feed's note, synced like any edit. */
    fun setNotes(notes: String) {
        val id = state.value.feedingLocalId ?: return
        val existing = records.feedings.value.firstOrNull { it.id == id } ?: return
        val updated = existing.copy(notes = notes.trim())
        if (updated == existing) return
        records.feedings.update { list -> list.map { if (it.id == id) updated else it } }
        records.queueUpdate(updated)
    }

    /** Shows the start in the current clock format. */
    fun reformat() = state.update { t -> if (t.isRunning && t.sessionStartMillis > 0) t.copy(sessionStartedTime = TimeFormat.clock(t.sessionStartMillis)) else t }

    private fun changed() {
        syncRecord()
        onChanged()
    }

    private fun startFeedOn(side: BreastSide, now: Long) {
        if (state.value.feedingLocalId != null) syncRecord()
        val id = records.nextId()
        state.value = LiveTimerState(
            isRunning = true,
            activeBreast = side,
            sessionStartedTime = TimeFormat.clock(now),
            feedingLocalId = id,
            sessionStartMillis = now,
            firstSide = side
        )
        records.add(FeedingEvent(id = id, timeMillis = now, timeFormatted = TimeFormat.clock(now), type = FeedType.NURSING, side = side, sides = side.name))
    }

    /** Pushes the running feed's current durations/sides to its feeding record (local list + server queue). */
    private fun syncRecord() {
        val t = state.value
        val id = t.feedingLocalId ?: return
        val existing = records.feedings.value.firstOrNull { it.id == id } ?: return
        val start = t.sessionStartMillis.takeIf { it > 0 } ?: existing.timeMillis
        val updated = existing.copy(
            timeMillis = start,
            timeFormatted = TimeFormat.clock(start),
            leftDurationSec = t.leftSeconds,
            rightDurationSec = t.rightSeconds,
            side = when {
                t.leftSeconds > 0 && t.rightSeconds > 0 -> BreastSide.BOTH
                t.rightSeconds > 0 -> BreastSide.RIGHT
                t.leftSeconds > 0 -> BreastSide.LEFT
                else -> t.activeBreast
            },
            sides = (t.firstSide ?: t.activeBreast).let { first -> if (first == t.activeBreast) first.name else "${first.name} -> ${t.activeBreast.name}" }
        )
        if (updated == existing) return
        records.feedings.update { list -> list.map { if (it.id == id) updated else it }.sortedByDescending { it.timeMillis } }
        records.queueUpdate(updated)
    }

    /**
     * Keeps the feed saved in [prefs] on every start/stop/switch/edit, and ticks it from the wall clock, so it stays
     * right even if ticks are delayed.
     */
    fun start(scope: CoroutineScope, prefs: SharedPreferences) {
        scope.launch {
            // The ticks in between saves are recomputed from the clock
            state.map { listOf(it.isRunning, it.isPaused, it.activeBreast, it.editCount) }.distinctUntilChanged().collect { save(prefs) }
        }
        scope.launch {
            var last = System.currentTimeMillis()
            while (true) {
                delay(1000)
                val elapsedSec = ((System.currentTimeMillis() - last) / 1000).toInt()
                if (elapsedSec > 0) {
                    last += elapsedSec * 1000L
                    tick(elapsedSec)
                }
            }
        }
    }

    private fun tick(elapsedSec: Int) {
        val t = state.value
        if (t.isRunning && !t.isPaused) {
            state.update {
                if (it.activeBreast == BreastSide.LEFT) it.copy(leftSeconds = it.leftSeconds + elapsedSec) else it.copy(rightSeconds = it.rightSeconds + elapsedSec)
            }
            // Keep the server's copy of a running feed at most a minute behind
            val total = state.value.totalSeconds
            if (total / 60 != (total - elapsedSec) / 60) syncRecord()
        } else if (t.isRunning && t.isPaused && System.currentTimeMillis() - t.pausedAtMillis > SESSION_TIMEOUT_MS) {
            endFeed()
        }
    }

    private fun save(prefs: SharedPreferences) {
        val t = state.value
        prefs.edit()
            .putBoolean("timer_running", t.isRunning)
            .putBoolean("timer_paused", t.isPaused)
            .putString("timer_side", t.activeBreast.name)
            .putInt("timer_left", t.leftSeconds)
            .putInt("timer_right", t.rightSeconds)
            .putString("timer_started", t.sessionStartedTime)
            .putLong("timer_feeding_id", t.feedingLocalId ?: -1L)
            .putLong("timer_session_start", t.sessionStartMillis)
            .putLong("timer_paused_at", t.pausedAtMillis)
            .putString("timer_first_side", t.firstSide?.name)
            .putLong("timer_last_active", t.lastActiveMillis)
            .putLong("timer_changed_at", t.changedAtMillis)
            .putLong("timer_feeding_server_id", t.feedingServerId ?: -1L)
            .putLong("timer_saved_at", System.currentTimeMillis())
            .apply()
    }

    /** Restores a feed saved by [start]; time that passed meanwhile counts towards the running side. */
    fun restore(p: SharedPreferences) {
        if (!p.getBoolean("timer_running", false)) return
        val paused = p.getBoolean("timer_paused", false)
        val side = sideOrNull(p.getString("timer_side", null)) ?: BreastSide.LEFT
        val missed = if (paused) 0 else ((System.currentTimeMillis() - p.getLong("timer_saved_at", 0L)) / 1000).toInt().coerceAtLeast(0)
        state.value = LiveTimerState(
            isRunning = true,
            isPaused = paused,
            activeBreast = side,
            leftSeconds = p.getInt("timer_left", 0) + if (side == BreastSide.LEFT) missed else 0,
            rightSeconds = p.getInt("timer_right", 0) + if (side == BreastSide.RIGHT) missed else 0,
            sessionStartedTime = p.getString("timer_started", "").orEmpty(),
            feedingLocalId = p.getLong("timer_feeding_id", -1L).takeIf { it >= 0 },
            sessionStartMillis = p.getLong("timer_session_start", 0L),
            pausedAtMillis = p.getLong("timer_paused_at", 0L),
            firstSide = sideOrNull(p.getString("timer_first_side", null)),
            lastActiveMillis = p.getLong("timer_last_active", 0L),
            changedAtMillis = p.getLong("timer_changed_at", 0L),
            feedingServerId = p.getLong("timer_feeding_server_id", -1L).takeIf { it >= 0 }
        )
    }

    private fun sideOrNull(name: String?) = name?.let { runCatching { BreastSide.valueOf(it) }.getOrNull() }

    companion object {
        /** A feed paused this long has ended. */
        const val SESSION_TIMEOUT_MS = 30 * 60_000L

        /** The last moment a side was running: now while running, else when it was paused (or the hand-set finish). */
        fun finishMillis(t: LiveTimerState): Long = when {
            !t.isPaused -> System.currentTimeMillis()
            t.lastActiveMillis > 0 -> t.lastActiveMillis
            t.pausedAtMillis > 0 -> t.pausedAtMillis
            else -> System.currentTimeMillis()
        }
    }
}
