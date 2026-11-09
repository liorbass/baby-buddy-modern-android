package com.example.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** How an entry type is keyed: its local id, its server id once saved, the time lists sort by, and a copy with a server id. */
internal class EntryKeys<T>(
    val id: (T) -> Long,
    val serverId: (T) -> Long?,
    val time: (T) -> Long,
    val withServerId: (T, Long) -> T
)

/** How entry stores reach the write queue: adding a write, and what is still waiting to be sent. */
internal class EntryQueue(
    val enqueue: (QueuedWrite) -> Unit,
    val knownServerId: (localId: Long) -> Long?,
    val pending: () -> List<QueuedWrite>
)

/**
 * One kind of entry on this phone: its list (newest first) and how changes to it are queued for the server.
 * [endpointOf] is an entry's endpoint (growth keeps one list with an endpoint per kind, whose ids may repeat across
 * kinds, so entries match on both). [createBody] defaults to [body].
 */
internal class EntryStore<T>(
    val list: MutableStateFlow<List<T>>,
    private val keys: EntryKeys<T>,
    private val endpointOf: (T) -> String,
    private val queue: EntryQueue,
    private val body: (T) -> Map<String, Any?>,
    private val createBody: (T) -> Map<String, Any?> = body
) {
    private fun T.matches(id: Long, endpoint: String) = keys.id(this) == id && endpointOf(this) == endpoint

    private fun sorted(entries: List<T>) = entries.sortedByDescending(keys.time)

    /** The server id of [entry], also when only the queue knows it yet (its create was sent after it was shown). */
    private fun serverIdOf(entry: T): Long? = keys.serverId(entry) ?: queue.knownServerId(keys.id(entry))

    /** Shows [entry] and queues its create; returns its id. */
    fun add(entry: T): Long {
        list.update { sorted(listOf(entry) + it) }
        queue.enqueue(QueuedWrite(endpointOf(entry), QueuedWrite.ACTION_CREATE, keys.id(entry), body = createBody(entry)))
        return keys.id(entry)
    }

    /** Replaces the entry with [entry]'s id (if shown) and queues the update. */
    fun update(entry: T) {
        val id = keys.id(entry)
        val endpoint = endpointOf(entry)
        list.update { entries -> sorted(entries.map { if (it.matches(id, endpoint)) entry else it }) }
        queue.enqueue(QueuedWrite(endpoint, QueuedWrite.ACTION_UPDATE, id, serverIdOf(entry), body(entry)))
    }

    /** Removes [entry] and queues its delete. */
    fun delete(entry: T) {
        val id = keys.id(entry)
        val endpoint = endpointOf(entry)
        list.update { entries -> entries.filterNot { it.matches(id, endpoint) } }
        queue.enqueue(QueuedWrite(endpoint, QueuedWrite.ACTION_DELETE, id, serverIdOf(entry)))
    }

    /** The shown entry with local id [id] (on [endpoint], for stores with several), or null. */
    fun find(id: Long, endpoint: String? = null): T? = list.value.firstOrNull { keys.id(it) == id && (endpoint == null || endpointOf(it) == endpoint) }

    /** The server id of the shown entry a write on [endpoint] is about. */
    fun serverIdOf(endpoint: String, localId: Long): Long? = find(localId, endpoint)?.let(keys.serverId)

    /** Records the server id a create got. */
    fun setServerId(endpoint: String, localId: Long, serverId: Long) = list.update { entries -> entries.map { if (it.matches(localId, endpoint)) keys.withServerId(it, serverId) else it } }

    /**
     * Replaces the entries of [endpoint] with [remote] (the server's), except where a queued write hasn't reached the
     * server yet: entries with a pending create/update keep their local version, and pending deletes stay deleted.
     */
    fun mergeRemote(endpoint: String, remote: List<T>) {
        val pending = queue.pending().filter { it.endpoint == endpoint }
        val touchedLocalIds = pending.map { it.localId }.toSet()
        val deletedServerIds = pending.filter { it.action == QueuedWrite.ACTION_DELETE }
            .map { it.serverId ?: queue.knownServerId(it.localId) ?: it.localId }.toSet()
        list.update { entries ->
            val (local, others) = entries.partition { endpointOf(it) == endpoint }
            val keepLocal = local.filter { keys.id(it) in touchedLocalIds }
            val shadowed = keepLocal.mapNotNull(keys.serverId).toSet() + keepLocal.map(keys.id)
            val keepRemote = remote.filter { r -> keys.serverId(r).let { it !in deletedServerIds && it !in shadowed } }
            sorted(others + keepRemote + keepLocal)
        }
    }
}
