package com.example.data.repository

import com.example.data.api.BabyBuddyApiService
import com.example.data.model.DiaperEvent
import com.example.data.model.FeedingEvent
import com.example.data.model.GrowthKind
import com.example.data.model.SleepEvent

/** The server's list endpoints (`api/<name>/`) that entries are read from and queued writes go to. */
object Endpoints {
    const val FEEDINGS = "feedings"
    const val CHANGES = "changes"
    const val TUMMY = "tummy-times"
    const val PUMPING = "pumping"
    const val TEMPERATURE = "temperature"
    const val NOTES = "notes"
    const val SLEEP = "sleep"
    const val TIMERS = "timers"

    /** Weight, length and head circumference: one endpoint per [GrowthKind]. */
    val GROWTH: Set<String> = GrowthKind.entries.map { it.endpoint }.toSet()
}

/** A logged-in server: its API, the `Token …` header, and the child entries belong to (null if it has none). */
internal class RemoteSession(val api: BabyBuddyApiService, val auth: String, val child: Int?) {
    /** Where queued writes and shared timers go; null without a child. */
    val target: WriteQueue.Target? get() = child?.let { WriteQueue.Target(api, auth, it) }

    companion object {
        /** The `Authorization` header for [token], which may already start with "Token ". */
        fun authHeader(token: String): String = if (token.startsWith("Token ")) token else "Token $token"
    }
}

/** Feeds, diaper changes and sleeps over some time range, as History, handoff and the rhythm models read them. */
data class TimedEvents(
    val feeds: List<FeedingEvent> = emptyList(),
    val changes: List<DiaperEvent> = emptyList(),
    val sleeps: List<SleepEvent> = emptyList()
)
