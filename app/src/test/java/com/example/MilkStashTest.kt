package com.example

import com.example.data.model.FeedType
import com.example.data.model.FeedingEvent
import com.example.data.model.PumpingEvent
import com.example.data.repository.MilkStash
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Milk on hand: the entered amount, plus pumping since, minus breast-milk bottles since. */
class MilkStashTest {
    private val pumpings = MutableStateFlow<List<PumpingEvent>>(emptyList())
    private val feedings = MutableStateFlow<List<FeedingEvent>>(emptyList())
    private val stash = MilkStash(CoroutineScope(Dispatchers.Unconfined), pumpings, feedings)

    @Test
    fun countsPumpingAndBreastMilkBottlesAfterTheAmountWasEntered() {
        assertNull(stash.amount.value)
        val now = System.currentTimeMillis()
        assertEquals("Milk stash: 300 ml", stash.set(300, nowMillis = now - 60_000L))

        pumpings.value = listOf(PumpingEvent(timeMillis = now, amountMl = 120), PumpingEvent(timeMillis = now - 120_000L, amountMl = 999))
        feedings.value = listOf(
            FeedingEvent(timeMillis = now, type = FeedType.BOTTLE, amountMl = 90),
            FeedingEvent(timeMillis = now, type = FeedType.BOTTLE, amountMl = 60, bottleFormula = true), // formula: not from the stash
            FeedingEvent(timeMillis = now, type = FeedType.NURSING)
        )
        assertEquals(300 + 120 - 90, stash.amount.value)

        stash.clear()
        assertNull(stash.amount.value)
    }

    @Test
    fun neverBelowZero() {
        val now = System.currentTimeMillis()
        stash.set(50, nowMillis = now - 1_000L)
        feedings.value = listOf(FeedingEvent(timeMillis = now, type = FeedType.BOTTLE, amountMl = 90))
        assertEquals(0, stash.amount.value)
    }
}
