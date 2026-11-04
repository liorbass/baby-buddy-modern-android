package com.example.data.repository

import com.example.R
import com.example.data.api.BabyBuddyApiService
import com.example.data.model.AppText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Writes the server hasn't accepted yet, sent oldest first through the generic create/update/delete endpoints and
 * mirrored to disk ([store]) on every change. Network and 5xx errors block the queue and retry with backoff; 4xx are
 * dropped with an error (a proxy's non-JSON 4xx is retried [PROXY_RETRY_ATTEMPTS] times first); a retried create first
 * looks for a record a lost response may already have made; a create's server id fills in later queued edits.
 *
 * What it needs from the local entries (their server ids, what to call a write) comes through [LocalEntries].
 */
internal class WriteQueue(
    private val scope: CoroutineScope,
    private val target: () -> Target?, // where to send; null while not connected
    private val local: LocalEntries,
    private val reportError: (String) -> Unit
) {
    class Target(val api: BabyBuddyApiService, val auth: String, val child: Int)

    interface LocalEntries {
        /** The server id of the local entry a write is about, if it has one. */
        fun serverIdOf(write: QueuedWrite): Long?

        /** A create succeeded: record [serverId] on the local entry. */
        fun onCreated(write: QueuedWrite, serverId: Long)

        /** A record a lost-response create may already have made, by its exact start/time. */
        suspend fun findExisting(write: QueuedWrite, target: Target): Long?

        /** "feeding", "edit of diaper change" … for error messages. */
        fun describe(write: QueuedWrite): String
    }

    /** Guarded by itself. */
    private val pending = mutableListOf<QueuedWrite>()

    @Volatile var snapshot: List<QueuedWrite> = emptyList()
        private set

    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()

    var store: OfflineStore? = null

    // Local entry id -> Baby Buddy record id, filled as creates succeed
    private val serverIds = ConcurrentHashMap<Long, Long>()
    private var flushJob: Job? = null

    fun knownServerId(localId: Long): Long? = serverIds[localId]

    /** Adds a write and starts sending. A newer edit of an entry replaces its queued, unsent one. */
    fun add(write: QueuedWrite) {
        synchronized(pending) {
            // Index 0 may be in flight, so only a later one is replaced
            val stale = if (write.action == QueuedWrite.ACTION_UPDATE) {
                pending.indexOfLast {
                    it.endpoint == write.endpoint && it.localId == write.localId && it.action == QueuedWrite.ACTION_UPDATE
                }.takeIf { it > 0 && it == pending.lastIndex }
            } else {
                null
            }
            if (stale != null) {
                pending[stale] = write.copy(serverId = write.serverId ?: pending[stale].serverId)
            } else {
                pending.add(write)
            }
            changed()
        }
        flush()
    }

    /** Puts back the writes saved on disk (at start). */
    fun restore(writes: List<QueuedWrite>) = synchronized(pending) {
        pending.clear()
        pending.addAll(writes)
        changed()
    }

    /** Stops sending and drops everything (leaving the server); returns how many writes were dropped. */
    fun clear(): Int {
        flushJob?.cancel()
        return synchronized(pending) {
            val dropped = pending.size
            pending.clear()
            changed()
            dropped
        }
    }

    /** Drops queued, unsent edits of one entry (the head of the queue may be in flight, so it stays). */
    fun dropUpdates(endpoint: String, localId: Long) = synchronized(pending) {
        val head = pending.firstOrNull()
        val dropped = pending.removeAll {
            it !== head && it.endpoint == endpoint && it.localId == localId && it.action == QueuedWrite.ACTION_UPDATE
        }
        if (dropped) changed()
    }

    /** Call while holding the [pending] lock. */
    private fun changed() {
        val list = pending.toList()
        snapshot = list
        _count.value = list.size
        store?.saveQueue(list)
    }

    private fun removeHead(write: QueuedWrite) = synchronized(pending) {
        val head = pending.firstOrNull()
        if (head != null && head.endpoint == write.endpoint && head.action == write.action && head.localId == write.localId) {
            pending.removeAt(0)
            changed()
        }
    }

    private enum class Result { SAVED, REJECTED, RETRY, PROXY_RETRY, NEEDS_LOGIN }

    /** Sends queued writes in order (one sender at a time). */
    fun flush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            while (true) {
                val to = target() ?: break
                val write = synchronized(pending) { pending.firstOrNull() } ?: break

                when (val result = attempt(write, to)) {
                    Result.SAVED, Result.REJECTED -> removeHead(write)

                    Result.RETRY, Result.PROXY_RETRY -> {
                        val attempts = write.attempts + 1
                        synchronized(pending) {
                            if (pending.firstOrNull() == write) {
                                pending[0] = write.copy(attempts = attempts)
                                changed()
                            }
                        }
                        if (attempts == 1) {
                            reportError(
                                if (result == Result.PROXY_RETRY) AppText.get(R.string.sync_proxy_rejected, local.describe(write)) else AppText.get(R.string.sync_unreachable)
                            )
                        }
                        // 5s, 10s, 20s ... capped at 5 min
                        delay(minOf(5_000L shl minOf(attempts - 1, 6), 300_000L))
                    }

                    Result.NEEDS_LOGIN -> {
                        reportError(AppText.plural(R.plurals.sync_token_rejected, _count.value, _count.value))
                        break
                    }
                }
            }
        }
    }

    private fun resolveServerId(write: QueuedWrite): Long? = write.serverId ?: serverIds[write.localId] ?: local.serverIdOf(write)

    private suspend fun attempt(write: QueuedWrite, to: Target): Result = try {
        when (write.action) {
            QueuedWrite.ACTION_CREATE -> create(write, to)

            QueuedWrite.ACTION_UPDATE -> {
                // No server id: the entry never reached the server, so there is nothing to change there
                resolveServerId(write)?.let { id -> classify(to.api.updateRecord(to.auth, write.endpoint, id, sendable(write.body)), write) {} } ?: Result.SAVED
            }

            QueuedWrite.ACTION_DELETE -> resolveServerId(write)?.let { id ->
                val response = to.api.deleteRecord(to.auth, write.endpoint, id)
                if (response.code() == 404) Result.SAVED else classify(response, write) {}
            } ?: Result.SAVED

            else -> Result.REJECTED
        }
    } catch (e: IOException) {
        Result.RETRY
    } catch (e: Exception) {
        reportError(AppText.get(R.string.sync_could_not_save, local.describe(write), "${e.localizedMessage}"))
        Result.REJECTED
    }

    private suspend fun create(write: QueuedWrite, to: Target): Result {
        if (write.attempts > 0) {
            // A previous attempt may have reached the server even though we never saw the response
            local.findExisting(write, to)?.let { id ->
                onCreated(write, id)
                return Result.SAVED
            }
        }
        val response = to.api.createRecord(to.auth, write.endpoint, sendable(write.body) + ("child" to to.child))
        return classify(response, write) { onCreated(write, (response.body()?.get("id") as? Number)?.toLong()) }
    }

    /** Records the new server id, on the local entry and on any queued edits/deletes of it. */
    private fun onCreated(write: QueuedWrite, serverId: Long?) {
        if (serverId == null) return
        serverIds[write.localId] = serverId
        local.onCreated(write, serverId)
        synchronized(pending) {
            var updated = false
            pending.replaceAll { w ->
                if (w.endpoint == write.endpoint && w.localId == write.localId && w.serverId == null && w.action != QueuedWrite.ACTION_CREATE) {
                    updated = true
                    w.copy(serverId = serverId)
                } else {
                    w
                }
            }
            if (updated) changed()
        }
    }

    private inline fun classify(response: Response<*>, write: QueuedWrite, onSuccess: () -> Unit): Result = when {
        response.isSuccessful -> {
            onSuccess()
            Result.SAVED
        }

        response.code() == 401 || response.code() == 403 -> Result.NEEDS_LOGIN

        response.code() == 408 || response.code() == 429 || response.code() >= 500 -> Result.RETRY

        else -> {
            val detail = response.errorBody()?.string().orEmpty()
            if (!isApiReply(response, detail) && write.attempts + 1 < PROXY_RETRY_ATTEMPTS) {
                // An HTML error page comes from a proxy in front of Baby Buddy (Cloudflare, nginx…), not from the
                // server's validation, so the request may well go through next time
                Result.PROXY_RETRY
            } else {
                // Validation errors won't fix themselves on retry
                reportError(AppText.get(R.string.sync_server_rejected, local.describe(write), response.code(), detail.take(160)).trim())
                Result.REJECTED
            }
        }
    }

    /** Baby Buddy's API answers errors in JSON; anything else was made by something in between. */
    private fun isApiReply(response: Response<*>, body: String): Boolean = response.errorBody()?.contentType()?.subtype == "json" || body.trimStart().let { it.startsWith("{") || it.startsWith("[") }

    @Suppress("UNCHECKED_CAST")
    private fun sendable(body: Map<String, Any?>): Map<String, Any> = body.filterValues { it != null } as Map<String, Any>

    companion object {
        const val PROXY_RETRY_ATTEMPTS = 6 // ~2.5 min of backoff before a proxy's 4xx is treated as final
    }
}
