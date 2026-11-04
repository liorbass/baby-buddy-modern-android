package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.data.repository.BabyBuddyRepository
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The write queue and shared timers against a fake Baby Buddy server: a created entry gets its server id (and so do
 * edits queued before the create finished), and a sleep whose shared timer is removed elsewhere ends here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SyncQueueTest {
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<String>() // "METHOD /path"

    @Volatile private var timers = "[]" // what GET api/timers/ returns

    @Volatile private var postedTimerName: String? = null // the name (live state) of the timer this phone created

    private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    private fun page(results: String) = json("""{"count":0,"next":null,"previous":null,"results":$results}""")

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                requests += "${request.method} $path"
                return when {
                    request.method == "GET" && path == "/api/children/" ->
                        page("""[{"id":1,"first_name":"Sam","last_name":"","birth_date":"2026-01-01","slug":"sam"}]""")

                    request.method == "GET" && path == "/api/timers/" -> page(timers)

                    request.method == "GET" -> page("[]")

                    request.method == "POST" && path == "/api/pumping/" -> json("""{"id":42}""")

                    request.method == "POST" && path == "/api/timers/" -> {
                        postedTimerName = Regex(""""name"\s*:\s*("(?:[^"\\]|\\.)*")""").find(request.body?.utf8().orEmpty())?.groupValues?.get(1)
                        json("""{"id":7}""")
                    }

                    else -> json("""{"id":0}""")
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun waitFor(what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000L
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "Timed out waiting for $what; requests: $requests" }
            Thread.sleep(20)
        }
    }

    private fun connectedRepository(): BabyBuddyRepository {
        val repository = BabyBuddyRepository()
        repository.attach(ApplicationProvider.getApplicationContext()) // as at app start: saved state, timer pushers
        val result = runBlocking { repository.testConnection(server.url("/").toString(), "test") }
        assertTrue("connect failed: ${result.exceptionOrNull()}", result.isSuccess)
        return repository
    }

    @Test
    fun createdEntryGetsItsServerIdAndQueuedEditsGoToIt() {
        val repository = connectedRepository()
        val id = repository.logPumping(ml = 90, minutes = 15, notes = "")
        // Edited before the create's reply arrives: the queued update has no server id yet
        val local = repository.pumpings.value.first { it.id == id }
        repository.updatePumping(local.copy(amountMl = 100))

        waitFor("the update") { "PATCH /api/pumping/42/" in requests }
        assertEquals(1, requests.count { it == "POST /api/pumping/" })
        waitFor("the local entry's server id") { repository.pumpings.value.first { it.id == id }.serverId == 42L }
    }

    @Test
    fun sleepEndsWhenItsSharedTimerIsRemovedElsewhere() {
        val repository = connectedRepository()
        repository.startSleep()
        waitFor("the shared timer") { "POST /api/timers/" in requests }
        // Give the push a moment to record the timer's id
        Thread.sleep(200)

        // Still on the server: nothing changes
        val name = checkNotNull(postedTimerName) { "no timer name in the create" }
        timers = """[{"id":7,"child":1,"name":$name}]"""
        runBlocking { repository.pollSharedTimers() }
        assertTrue(repository.sleepTimer.value.isRunning)

        // Removed on another phone: the sleep ends here, without saving an entry from this phone
        timers = "[]"
        runBlocking { repository.pollSharedTimers() }
        assertFalse(repository.sleepTimer.value.isRunning)
        assertFalse("POST /api/sleep/" in requests)
    }
}
