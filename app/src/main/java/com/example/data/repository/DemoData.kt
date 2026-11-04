package com.example.data.repository

import com.example.data.model.BATH_TAG
import com.example.data.model.BreastSide
import com.example.data.model.DIAPER_AMOUNTS
import com.example.data.model.DiaperEvent
import com.example.data.model.DiaperType
import com.example.data.model.FeedType
import com.example.data.model.FeedingEvent
import com.example.data.model.GrowthEvent
import com.example.data.model.GrowthKind
import com.example.data.model.NoteEvent
import com.example.data.model.PumpingEvent
import com.example.data.model.SleepEvent
import com.example.data.model.TummyTimeEvent
import com.example.data.model.startOfDay
import java.util.Calendar
import kotlin.math.exp
import kotlin.random.Random

/** Everything demo mode shows, newest first like the server's lists. */
class DemoHistory(
    val feeds: List<FeedingEvent>,
    val diapers: List<DiaperEvent>,
    val sleeps: List<SleepEvent>,
    val pumpings: List<PumpingEvent>,
    val tummyTimes: List<TummyTimeEvent>,
    val notes: List<NoteEvent>,
    val growth: List<GrowthEvent>
)

/**
 * Demo data: [days] days of a baby's day simulated up to [now]. The rhythm is realistic enough for every screen and
 * for the rhythm models to have something to learn: the baby feeds on waking, awake windows lengthen through the
 * day and as the baby gets older, naps after longer awake windows are longer, and nights have one waking that moves
 * later with age. Growth is monthly from [birthMillis]. Seeded, so it's the same every time.
 */
fun generateDemoHistory(now: Long, birthMillis: Long, days: Int = 90, clock: (Long) -> String): DemoHistory {
    val random = Random(42)
    val minute = 60_000L
    var id = 1000L
    val feeds = mutableListOf<FeedingEvent>()
    val diapers = mutableListOf<DiaperEvent>()
    val sleeps = mutableListOf<SleepEvent>()
    val pumpings = mutableListOf<PumpingEvent>()
    val tummies = mutableListOf<TummyTimeEvent>()
    val notes = mutableListOf<NoteEvent>()

    fun jitter(m: Int) = random.nextInt(-m, m + 1) * minute
    fun who() = if (random.nextInt(3) == 0) "Alex" else "Sarah"
    fun at(dayStart: Long, hour: Int, min: Int) = Calendar.getInstance().apply {
        timeInMillis = dayStart
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, min)
    }.timeInMillis

    fun feed(t: Long, night: Boolean = false, bedtime: Boolean = false) {
        if (t > now) return
        if (!night && !bedtime && random.nextInt(4) == 0) {
            feeds += FeedingEvent(
                id = id++, type = FeedType.BOTTLE, timeMillis = t, timeFormatted = clock(t), amountMl = 110 + random.nextInt(6) * 10,
                bottleFormula = random.nextInt(3) == 0, side = null, leftDurationSec = 0, rightDurationSec = 0,
                notes = "", observations = emptyList(), imageUrl = null, caregiver = "Alex"
            )
        } else {
            val left = (7 + random.nextInt(6) + if (bedtime) 3 else 0) * 60
            val right = (5 + random.nextInt(6) + if (bedtime) 3 else 0) * 60
            feeds += FeedingEvent(
                id = id++, type = FeedType.NURSING, timeMillis = t, timeFormatted = clock(t), leftDurationSec = left, rightDurationSec = right,
                side = BreastSide.BOTH, sides = "LEFT -> RIGHT",
                notes = if (night) {
                    "Quiet night feed"
                } else if (bedtime) {
                    "Bedtime feed"
                } else {
                    ""
                },
                observations = emptyList(), imageUrl = null, caregiver = "Sarah"
            )
        }
    }

    fun diaper(t: Long, type: DiaperType? = null) {
        if (t > now) return
        val kind = type ?: when (random.nextInt(20)) {
            in 0..11 -> DiaperType.WET
            in 12..15 -> DiaperType.BOTH
            else -> DiaperType.DIRTY
        }
        diapers += DiaperEvent(
            id = id++, type = kind, timeMillis = t, timeFormatted = clock(t), caregiver = who(),
            amount = DIAPER_AMOUNTS[((t / 60_000L) % DIAPER_AMOUNTS.size).toInt()], // varied without another random draw
            consistency = if (kind == DiaperType.WET) "" else "Soft", color = "", skinBarrierApplied = emptyList(), notes = "", imageUrl = null
        )
    }

    fun sleep(start: Long, end: Long, nap: Boolean, note: String) {
        if (end > now) return
        sleeps += SleepEvent(id = id++, caregiver = who(), timeMillis = start, endMillis = end, timeFormatted = clock(start), isNap = nap, notes = note)
    }

    val todayStart = startOfDay(now)
    var bedtime: Long? = null
    for (i in 0 until days) {
        val dayStart = Calendar.getInstance().apply {
            timeInMillis = todayStart
            add(Calendar.DAY_OF_YEAR, -(days - 1 - i))
        }.timeInMillis
        val age = i / (days - 1).toDouble() // 0 = oldest day, 1 = today

        // Night: asleep from last night's bedtime, one feed in the small hours, up for the day around 06:30
        val nightWake = at(dayStart, 2, 30) + ((age * 60).toLong() * minute) + jitter(25)
        val morning = at(dayStart, 6, 30) + jitter(20)
        bedtime?.let { sleep(it, nightWake, nap = false, note = "Night sleep") }
        feed(nightWake + 5 * minute, night = true)
        diaper(nightWake + 25 * minute, DiaperType.WET)
        sleep(nightWake + 35 * minute, morning, nap = false, note = "Back to sleep")

        // Day: feed on waking, then an awake window that grows through the day, then a nap
        var t = morning
        var napIndex = 0
        while (true) {
            feed(t + 10 * minute)
            diaper(t + 25 * minute)
            if (napIndex == 0) {
                pumpings += (t + 70 * minute).takeIf { it <= now }?.let {
                    PumpingEvent(id = id++, timeMillis = it, timeFormatted = clock(it), amountMl = 120 + random.nextInt(5) * 10, durationMinutes = 15, notes = "Morning session", caregiver = "Sarah")
                }.let { listOfNotNull(it) }
            }
            if (napIndex == 1) {
                tummies += (t + 40 * minute).takeIf { it <= now }?.let {
                    TummyTimeEvent(id = id++, timeMillis = it, timeFormatted = clock(it), durationMinutes = 10 + random.nextInt(8), milestone = if (random.nextInt(5) == 0) "Pushed up on forearms!" else "", caregiver = who())
                }.let { listOfNotNull(it) }
            }
            val window = (95 + 25 * napIndex + age * 35).toLong() * minute + jitter(12)
            val napStart = t + window
            if (napStart > at(dayStart, 17, 15)) break
            val late = napStart > at(dayStart, 15, 30)
            val napLength = if (late) 35 * minute + jitter(8) else (50 + (window / minute - 95) * 0.35 + if (napIndex == 0) 15 else 0).toLong() * minute + jitter(12)
            sleep(
                napStart,
                napStart + napLength,
                nap = true,
                note = if (napIndex == 0) {
                    "Morning nap"
                } else if (late) {
                    "Catnap"
                } else {
                    "Afternoon nap"
                }
            )
            t = napStart + napLength
            napIndex++
        }
        if (i % 2 == 1 && at(dayStart, 19, 0) <= now) {
            val b = at(dayStart, 19, 0)
            notes += NoteEvent(id = id++, text = "Warm bath before bedtime", timeMillis = b, timeFormatted = clock(b), tags = listOf(BATH_TAG))
        }
        diaper(at(dayStart, 20, 0) + jitter(10))
        feed(at(dayStart, 20, 15) + jitter(15), bedtime = true)
        bedtime = at(dayStart, 20, 45) + jitter(15)
    }

    // Monthly growth from birth, near the WHO medians
    val growth = mutableListOf<GrowthEvent>()
    val month = 30.4375 * 24 * 60 * minute
    var m = 0
    while (birthMillis + (m * month).toLong() <= now) {
        val date = Calendar.getInstance().apply {
            timeInMillis = birthMillis + (m * month).toLong()
            set(Calendar.HOUR_OF_DAY, 12)
        }.timeInMillis
        val w = 3.4 + 5.9 * (1 - exp(-m / 4.5)) + random.nextDouble(-0.1, 0.1)
        val l = 50.0 + 24.0 * (1 - exp(-m / 6.0)) + random.nextDouble(-0.4, 0.4)
        val h = 34.5 + 11.0 * (1 - exp(-m / 5.0)) + random.nextDouble(-0.2, 0.2)
        growth += GrowthEvent(id = id++, kind = GrowthKind.WEIGHT, dateMillis = date, value = Math.round(w * 100) / 100.0, caregiver = "Sarah")
        growth += GrowthEvent(id = id++, kind = GrowthKind.LENGTH, dateMillis = date, value = Math.round(l * 10) / 10.0, caregiver = "Sarah")
        growth += GrowthEvent(id = id++, kind = GrowthKind.HEAD, dateMillis = date, value = Math.round(h * 10) / 10.0, caregiver = "Sarah")
        m++
    }
    return DemoHistory(
        feeds.sortedByDescending { it.timeMillis },
        diapers.sortedByDescending { it.timeMillis },
        sleeps.sortedByDescending { it.timeMillis },
        pumpings.sortedByDescending { it.timeMillis },
        tummies.sortedByDescending { it.timeMillis },
        notes.sortedByDescending { it.timeMillis },
        growth.sortedByDescending { it.dateMillis }
    )
}
