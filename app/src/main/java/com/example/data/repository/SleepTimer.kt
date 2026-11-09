package com.example.data.repository

import android.content.SharedPreferences
import com.example.data.model.SleepTimerState
import com.example.data.model.isNapTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A sleep timed on this phone, saved in prefs while it runs; stopping it saves the sleep entry ([logSleep]).
 * [onChanged]/[onEnded] tell the shared timer ([SharedTimerSync]) to follow.
 */
class SleepTimer(
    private val logSleep: (startMillis: Long, endMillis: Long, isNap: Boolean, notes: String) -> Long,
    private val onChanged: () -> Unit,
    private val onEnded: () -> Unit
) {
    private var prefs: SharedPreferences? = null
    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    /** Restores a sleep saved by [set]. */
    fun load(p: SharedPreferences) {
        prefs = p
        p.getLong(PREF_START, 0L).takeIf { it > 0 }?.let { start ->
            _state.value = SleepTimerState(start, p.getString(PREF_NOTES, "").orEmpty(), p.getLong(PREF_CHANGED, 0L))
        }
    }

    /** Shows and saves [state] (also how [SharedTimerSync] applies another phone's change). */
    fun set(state: SleepTimerState) {
        _state.value = state
        prefs?.edit()
            ?.putLong(PREF_START, state.startMillis)
            ?.putString(PREF_NOTES, state.notes)
            ?.putLong(PREF_CHANGED, state.changedAtMillis)
            ?.apply()
    }

    fun start(startMillis: Long = System.currentTimeMillis()) {
        if (_state.value.isRunning) return
        set(SleepTimerState(startMillis = startMillis, changedAtMillis = nextChangeMillis(_state.value.changedAtMillis)))
        onChanged()
    }

    /** Corrects when the running sleep started (never in the future). */
    fun setStart(startMillis: Long) {
        val t = _state.value
        if (!t.isRunning) return
        set(t.copy(startMillis = startMillis.coerceAtMost(System.currentTimeMillis()), changedAtMillis = nextChangeMillis(t.changedAtMillis)))
        onChanged()
    }

    fun setNotes(notes: String) {
        val t = _state.value
        if (t.isRunning) set(t.copy(notes = notes))
    }

    /** Ends the running sleep at [endMillis] and saves it; returns the entry id, or null if none was running. */
    fun stop(endMillis: Long = System.currentTimeMillis(), isNap: Boolean? = null): Long? {
        val t = _state.value
        if (!t.isRunning) return null
        cancel()
        return logSleep(t.startMillis, endMillis, isNap ?: isNapTime(t.startMillis), t.notes)
    }

    /** Ends the running sleep without saving it. */
    fun cancel() {
        set(SleepTimerState())
        onEnded()
    }

    /** Forgets the running sleep (signing out; the shared timer is reset separately). */
    fun reset() = set(SleepTimerState())

    private companion object {
        const val PREF_START = "sleep_timer_start"
        const val PREF_NOTES = "sleep_timer_notes"
        const val PREF_CHANGED = "sleep_timer_changed_at"
    }
}
