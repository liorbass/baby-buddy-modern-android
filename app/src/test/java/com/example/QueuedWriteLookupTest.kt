package com.example

import com.example.data.api.BabyBuddyApiClient
import com.example.data.model.AppText
import com.example.data.repository.Endpoints
import com.example.data.repository.QueuedWrite
import com.example.data.repository.WriteQueue
import com.example.data.repository.describeWrite
import com.example.data.repository.findExistingRecord
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A retried create finds the record its lost response made, by exact time and kind; writes read well in errors. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QueuedWriteLookupTest {
    private val server = MockWebServer()

    private fun page(results: String) = MockResponse.Builder().addHeader("Content-Type", "application/json")
        .body("""{"count":0,"next":null,"previous":null,"results":$results}""").build()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/api/feedings/" -> page(
                    """[{"id":5,"child":1,"start":"2026-03-01T08:00:00Z","end":"2026-03-01T08:10:00Z","method":"bottle","type":"formula"},
                       {"id":6,"child":1,"start":"2026-03-01T08:00:00Z","end":"2026-03-01T08:10:00Z","method":"left breast","type":"breast milk"}]"""
                )

                "/api/changes/" -> page("""[{"id":9,"child":1,"time":"2026-03-01T09:00:00Z","wet":true,"solid":false}]""")

                else -> page("[]")
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun target() = WriteQueue.Target(BabyBuddyApiClient.createService(server.url("/").toString(), false, "", "", ""), "Token t", 1)

    @Test
    fun findsTheMatchingRecordOrNone() = runBlocking {
        val feed = QueuedWrite(Endpoints.FEEDINGS, QueuedWrite.ACTION_CREATE, 1, body = mapOf("start" to "2026-03-01T08:00:00Z", "method" to "left breast"))
        assertEquals(6L, findExistingRecord(feed, target()))

        val wet = QueuedWrite(Endpoints.CHANGES, QueuedWrite.ACTION_CREATE, 2, body = mapOf("time" to "2026-03-01T09:00:00Z", "wet" to true, "solid" to false))
        assertEquals(9L, findExistingRecord(wet, target()))
        assertNull(findExistingRecord(wet.copy(body = wet.body + ("solid" to true)), target()))

        assertNull(findExistingRecord(QueuedWrite(Endpoints.PUMPING, QueuedWrite.ACTION_CREATE, 3, body = emptyMap()), target())) // no time to look for
    }

    @Test
    fun describesWhatAWriteIsAbout() {
        val feeding = AppText.get(R.string.write_feeding)
        assertEquals(feeding, describeWrite(QueuedWrite(Endpoints.FEEDINGS, QueuedWrite.ACTION_CREATE, 1)))
        assertEquals(AppText.get(R.string.write_delete, feeding), describeWrite(QueuedWrite(Endpoints.FEEDINGS, QueuedWrite.ACTION_DELETE, 1)))
        assertEquals(AppText.get(R.string.write_edit, AppText.get(R.string.write_sleep)), describeWrite(QueuedWrite(Endpoints.SLEEP, QueuedWrite.ACTION_UPDATE, 1)))
    }
}
