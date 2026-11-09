package com.example.data.repository

import com.example.data.model.FeedType
import com.example.data.model.HandoffSummary
import com.example.data.model.TrackedItem

/**
 * The feeds, changes and sleeps in [events] that someone else logged from [fromMillis] on, for "While you were
 * away". With [myName] set, everything not carrying it counts; without one, only entries that name someone.
 * [hidden] items are left out.
 */
internal fun handoffSummary(events: TimedEvents, fromMillis: Long, myName: String?, hidden: Set<TrackedItem>): HandoffSummary {
    fun byOther(caregiver: String?) = if (myName != null) !caregiver.equals(myName, ignoreCase = true) else !caregiver.isNullOrBlank()
    return HandoffSummary(
        sinceMillis = fromMillis,
        feedings = events.feeds.filter { f ->
            val item = if (f.type == FeedType.NURSING) TrackedItem.BREASTFEEDING else TrackedItem.BOTTLE
            f.timeMillis >= fromMillis && byOther(f.caregiver) && item !in hidden
        }.sortedBy { it.timeMillis },
        diapers = events.changes.filter { it.timeMillis >= fromMillis && byOther(it.caregiver) && TrackedItem.DIAPERS !in hidden }.sortedBy { it.timeMillis },
        sleeps = events.sleeps.filter {
            it.id != ServerQueries.RUNNING_SLEEP_ID && it.endMillis >= fromMillis && byOther(it.caregiver) && TrackedItem.SLEEP !in hidden
        }.sortedBy { it.timeMillis }
    )
}
