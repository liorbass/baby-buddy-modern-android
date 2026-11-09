package com.example.data.repository

import com.example.data.model.FeedType
import com.example.data.model.FeedingEvent
import com.example.data.model.MILK_STASH_TAG
import com.example.data.model.PumpingEvent
import com.example.data.model.startOfDay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Breast milk on hand: the last amount someone entered (a note tagged [MILK_STASH_TAG], so every phone shares it),
 * plus milk pumped since then, minus breast-milk bottles given since then. Today's part comes from the live lists,
 * which include unsent entries.
 */
internal class MilkStash(scope: CoroutineScope, pumpings: StateFlow<List<PumpingEvent>>, feedings: StateFlow<List<FeedingEvent>>) {

    /** An entered amount and the totals between it and the start of today. */
    private data class Base(val ml: Int, val sinceMillis: Long, val pumpedBeforeToday: Int, val bottlesBeforeToday: Int)

    private val base = MutableStateFlow<Base?>(null)

    /** Milk on hand in ml, or null until someone enters an amount. */
    val amount: StateFlow<Int?> = combine(base, pumpings, feedings) { b, pumps, feeds ->
        if (b == null) return@combine null
        val from = maxOf(b.sinceMillis, startOfDay())
        val pumped = pumps.filter { it.timeMillis >= from }.sumOf { it.amountMl }
        val given = feeds.filter { it.timeMillis >= from && isBreastMilkBottle(it) }.sumOf { it.amountMl ?: 0 }
        (b.ml + b.pumpedBeforeToday - b.bottlesBeforeToday + pumped - given).coerceAtLeast(0)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** [ml] is on hand now; later pumping and bottles count from here. Returns the note text that records it. */
    fun set(ml: Int, nowMillis: Long = System.currentTimeMillis()): String {
        base.value = Base(ml, nowMillis, 0, 0)
        return "Milk stash: $ml ml"
    }

    /** Forgets the amount (until [load] finds one on the server). */
    fun clear() {
        base.value = null
    }

    /** Finds the latest amount on the server (last 180 days) and totals the days between it and today. */
    suspend fun load(session: RemoteSession) {
        val child = session.child ?: return
        val api = session.api
        runCatching {
            val note = api.getNotes(session.auth, child, since = isoUtc(System.currentTimeMillis() - 180L * DAY_MS), limit = PAGE).body()!!.results
                .filter { MILK_STASH_TAG in it.tags.orEmpty() }
                .maxByOrNull { it.time?.let(::parseServerTime)?.time ?: 0L }
                ?: return@runCatching null
            val ml = Regex("""(\d+)""").find(note.note.orEmpty())?.value?.toIntOrNull() ?: return@runCatching null
            val sinceMillis = note.time?.let(::parseServerTime)?.time ?: return@runCatching null
            val todayStart = startOfDay()
            if (sinceMillis >= todayStart) return@runCatching Base(ml, sinceMillis, 0, 0)
            val from = isoUtc(sinceMillis)
            val before = sinceMillis until todayStart
            val pumped = api.getPumping(session.auth, child, since = from, limit = PAGE).body()!!.results
                .map(::mapPumping).filter { it.timeMillis in before }.sumOf { it.amountMl }
            val given = api.getFeedings(session.auth, child, since = from, limit = PAGE).body()!!.results
                .map(::mapFeeding).filter { it.timeMillis in before && isBreastMilkBottle(it) }.sumOf { it.amountMl ?: 0 }
            Base(ml, sinceMillis, pumped, given)
        }.onSuccess { found ->
            // Keep an amount entered on this phone that the server hasn't returned yet
            val local = base.value
            if (found != null && (local == null || found.sinceMillis >= local.sinceMillis - 60_000L)) base.value = found
        }
    }

    private fun isBreastMilkBottle(f: FeedingEvent) = f.type == FeedType.BOTTLE && !f.bottleFormula && f.amountMl != null

    private companion object {
        const val DAY_MS = 24 * 60 * 60_000L
        const val PAGE = 1000
    }
}
