package com.example

import com.example.data.repository.EntryKeys
import com.example.data.repository.EntryQueue
import com.example.data.repository.EntryStore
import com.example.data.repository.QueuedWrite
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [EntryStore]: local changes are queued, and a sync keeps entries whose writes haven't reached the server yet. */
class EntryStoreTest {
    private data class Entry(val id: Long, val serverId: Long?, val time: Long, val kind: String = "a", val text: String = "")

    private val queued = mutableListOf<QueuedWrite>()
    private val known = mutableMapOf<Long, Long>()
    private val list = MutableStateFlow<List<Entry>>(emptyList())
    private val store = EntryStore(
        list = list,
        keys = EntryKeys({ it.id }, { it.serverId }, { it.time }, { e, id -> e.copy(serverId = id) }),
        endpointOf = { it.kind },
        queue = EntryQueue(enqueue = { queued += it }, knownServerId = { known[it] }, pending = { queued.toList() }),
        body = { mapOf("text" to it.text) },
        createBody = { mapOf("new" to it.text) }
    )

    @Test
    fun addUpdateDeleteQueueWritesAndKeepNewestFirst() {
        store.add(Entry(1, null, time = 10, text = "x"))
        store.add(Entry(2, null, time = 20))
        assertEquals(listOf(2L, 1L), list.value.map { it.id })
        assertEquals(mapOf("new" to "x"), queued[0].body)

        known[1] = 501 // its create was sent before the edit
        store.update(Entry(1, null, time = 30, text = "y"))
        assertEquals(listOf(1L, 2L), list.value.map { it.id })
        assertEquals(QueuedWrite.ACTION_UPDATE, queued[2].action)
        assertEquals(501L, queued[2].serverId)
        assertEquals(mapOf("text" to "y"), queued[2].body)

        store.delete(store.find(2)!!)
        assertEquals(listOf(1L), list.value.map { it.id })
        assertEquals(QueuedWrite.ACTION_DELETE, queued[3].action)
    }

    @Test
    fun idsMatchPerEndpoint() {
        list.value = listOf(Entry(7, 70, time = 1, kind = "a"), Entry(7, 71, time = 2, kind = "b"))
        assertEquals(71L, store.serverIdOf("b", 7))
        store.setServerId("a", 7, 99)
        assertEquals(listOf(99L, 71L), list.value.map { it.serverId })
        assertNull(store.find(7, "c"))
    }

    @Test
    fun mergeKeepsPendingEditsAndDeletesAndLeavesOtherEndpointsAlone() {
        list.value = listOf(
            Entry(1, 11, time = 1, text = "edited here"), // entry 2 was deleted here
            Entry(3, null, time = 3), // created here, not sent yet
            Entry(9, 90, time = 9, kind = "b")
        )
        queued += QueuedWrite("a", QueuedWrite.ACTION_UPDATE, 1, 11, emptyMap())
        queued += QueuedWrite("a", QueuedWrite.ACTION_DELETE, 2, 12)
        queued += QueuedWrite("a", QueuedWrite.ACTION_CREATE, 3, body = emptyMap())

        store.mergeRemote("a", listOf(Entry(11, 11, time = 1, text = "server"), Entry(12, 12, time = 2), Entry(13, 13, time = 4)))

        assertEquals(listOf(9L, 13L, 3L, 1L), list.value.map { it.id })
        assertEquals("edited here", list.value.single { it.id == 1L }.text)
    }
}
