package com.example.data.repository

import com.example.R
import com.example.data.model.AppText
import com.example.data.model.GrowthKind

/**
 * Looks on the server for the record a create may already have made before its response was lost, by its exact
 * start/time (and kind); returns its id, or null if there is none.
 */
internal suspend fun findExistingRecord(write: QueuedWrite, target: WriteQueue.Target): Long? {
    val api = target.api
    val auth = target.auth
    val child = target.child
    val body = write.body
    fun at(key: String) = body[key] as? String
    return when (write.endpoint) {
        Endpoints.FEEDINGS -> at("start")?.let { start ->
            api.getFeedings(auth, child, since = start, until = start).body()?.results.orEmpty()
                .firstOrNull { it.method == body["method"] }?.id
        }

        Endpoints.CHANGES -> at("time")?.let { time ->
            api.getChanges(auth, child, since = time, until = time).body()?.results.orEmpty()
                .firstOrNull { it.wet == body["wet"] && it.solid == body["solid"] }?.id
        }

        Endpoints.PUMPING -> at("start")?.let { start ->
            api.getPumping(auth, child, since = start, until = start).body()?.results.orEmpty().firstOrNull()?.id
        }

        Endpoints.TEMPERATURE -> at("time")?.let { time ->
            api.getTemperatures(auth, child, since = time, until = time).body()?.results.orEmpty().firstOrNull()?.id
        }

        Endpoints.NOTES -> at("time")?.let { time ->
            api.getNotes(auth, child, since = time, until = time).body()?.results.orEmpty()
                .firstOrNull { it.note == body["note"] }?.id
        }

        Endpoints.SLEEP -> at("start")?.let { start ->
            api.getSleep(auth, child, since = start, until = start).body()?.results.orEmpty().firstOrNull()?.id
        }

        in Endpoints.GROWTH -> {
            val kind = GrowthKind.forEndpoint(write.endpoint) ?: return null
            at("date")?.let { date ->
                api.getGrowth(auth, kind.endpoint, child, date = date).body()?.results.orEmpty()
                    .firstOrNull { growthValue(kind, it) == body[kind.field] }?.id
            }
        }

        else -> at("start")?.let { start ->
            api.getTummyTimes(auth, child, since = start, until = start).body()?.results.orEmpty().firstOrNull()?.id
        }
    }
}

/** What a queued write is about, in words, for sync error messages ("feeding", "edit to diaper change", …). */
internal fun describeWrite(write: QueuedWrite): String {
    val what = when (write.endpoint) {
        Endpoints.FEEDINGS -> AppText.get(R.string.write_feeding)
        Endpoints.CHANGES -> AppText.get(R.string.write_diaper_change)
        Endpoints.PUMPING -> AppText.get(R.string.write_pumping)
        Endpoints.TEMPERATURE -> AppText.get(R.string.write_temperature)
        Endpoints.NOTES -> AppText.get(R.string.write_note)
        Endpoints.SLEEP -> AppText.get(R.string.write_sleep)
        in Endpoints.GROWTH -> GrowthKind.forEndpoint(write.endpoint)!!.label.lowercase()
        else -> AppText.get(R.string.write_tummy_time)
    }
    return when (write.action) {
        QueuedWrite.ACTION_UPDATE -> AppText.get(R.string.write_edit, what)
        QueuedWrite.ACTION_DELETE -> AppText.get(R.string.write_delete, what)
        else -> what
    }
}
