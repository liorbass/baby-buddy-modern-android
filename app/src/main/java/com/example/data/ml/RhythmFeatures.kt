package com.example.data.ml

import com.example.R
import com.example.data.model.AppText
import com.example.data.model.DiaperEvent
import com.example.data.model.DiaperType
import com.example.data.model.FeedType
import com.example.data.model.FeedingEvent
import com.example.data.model.SleepEvent
import com.example.data.model.formatMinutes
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** What a rhythm model predicts, in minutes. */
enum class RhythmTarget(private val titleRes: Int, private val descriptionRes: Int) {
    FEED_GAP(R.string.rhythm_feed_gap, R.string.rhythm_feed_gap_desc),
    DIAPER_GAP(R.string.rhythm_diaper_gap, R.string.rhythm_diaper_gap_desc),

    // Falling asleep before waking up, the order they're listed in (Trends → Models)
    AWAKE_WINDOW(R.string.rhythm_awake_window, R.string.rhythm_awake_window_desc),
    SLEEP_LENGTH(R.string.rhythm_sleep_length, R.string.rhythm_sleep_length_desc);

    val title: String get() = AppText.get(titleRes)
    val description: String get() = AppText.get(descriptionRes)
}

/** A target's rows, plus the row for "now" (the latest feed / change / sleep) and when that row starts counting. */
class RhythmDataset(val target: RhythmTarget, val data: Dataset, val current: DoubleArray?, val currentAnchorMillis: Long?)

/** Feeds, changes and sleeps over the training window, plus what's running now. */
class RhythmEvents(
    val feeds: List<FeedingEvent>,
    val changes: List<DiaperEvent>,
    val sleeps: List<SleepEvent>, // finished sleeps
    val runningSleepStart: Long? = null,
    /** Where "Age (days)" counts from: the birth date, else the start of the training window (saved with a model). */
    val birthMillis: Long? = null
)

private const val CAP_MINUTES = 720.0 // "a long time ago", also for "never"
private const val MINUTE = 60_000L
private const val DAY = 24 * 60 * MINUTE

/**
 * Turns the event history into one dataset per rhythm. Each row describes the moment an interval starts
 * (a feed, a change, falling asleep, waking up) by its time of day, the baby's age, the previous intervals
 * and what the other rhythms were doing (time since the last feed / change / sleep, the last 24 hours' totals);
 * the target is how long until the next event. Intervals outside a plausible range (a missed log) are left out.
 */
object RhythmFeatures {

    fun build(events: RhythmEvents): List<RhythmDataset> {
        val feeds = events.feeds.sortedBy { it.timeMillis }
        val changes = events.changes.sortedBy { it.timeMillis }
        val sleeps = events.sleeps.filter { it.endMillis > it.timeMillis }.sortedBy { it.timeMillis }
        val firstMillis = listOfNotNull(feeds.firstOrNull()?.timeMillis, changes.firstOrNull()?.timeMillis, sleeps.firstOrNull()?.timeMillis).minOrNull() ?: 0L

        fun sinceLast(times: List<Long>, t: Long): Double {
            val last = times.lastOrNull { it < t } ?: return CAP_MINUTES
            return ((t - last) / MINUTE.toDouble()).coerceAtMost(CAP_MINUTES)
        }
        val feedTimes = feeds.map { it.timeMillis }
        val changeTimes = changes.map { it.timeMillis }
        val sleepEnds = sleeps.map { it.endMillis }.sorted()

        /** Time of day, age and the other rhythms at moment [t]. */
        fun context(t: Long, skip: String): List<Pair<String, Double>> {
            val cal = Calendar.getInstance().apply { timeInMillis = t }
            val dayFraction = (cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)) / 1440.0
            val asleepNow = sleeps.any { t in it.timeMillis until it.endMillis } || (events.runningSleepStart?.let { t >= it } ?: false)
            val sleep24 = sleeps.sumOf { s -> (minOf(s.endMillis, t) - maxOf(s.timeMillis, t - DAY)).coerceAtLeast(0L) } / MINUTE.toDouble()
            return listOfNotNull(
                "Time of day (sin)" to sin(2 * PI * dayFraction),
                "Time of day (cos)" to cos(2 * PI * dayFraction),
                "Age (days)" to ((t - (events.birthMillis ?: firstMillis)) / DAY.toDouble()),
                ("Since last feed" to sinceLast(feedTimes, t)).takeIf { skip != "feed" },
                ("Since last diaper" to sinceLast(changeTimes, t)).takeIf { skip != "diaper" },
                ("Since last wake-up" to if (asleepNow) 0.0 else sinceLast(sleepEnds, t)).takeIf { skip != "sleep" },
                "Feeds in last 24h" to feedTimes.count { it in (t - DAY) until t }.toDouble(),
                "Diapers in last 24h" to changeTimes.count { it in (t - DAY) until t }.toDouble(),
                "Sleep in last 24h (min)" to sleep24
            )
        }

        fun dataset(target: RhythmTarget, rows: List<Pair<List<Pair<String, Double>>, Double>>, current: List<Pair<String, Double>>?, anchor: Long?): RhythmDataset {
            val names = (rows.firstOrNull()?.first ?: current)?.map { it.first }.orEmpty()
            val x = rows.map { r -> r.first.map { it.second }.toDoubleArray() }.toTypedArray()
            val y = rows.map { it.second }.toDoubleArray()
            return RhythmDataset(target, Dataset(x, y, names), current?.map { it.second }?.toDoubleArray(), anchor)
        }

        fun minutes(from: Long, to: Long) = (to - from) / MINUTE.toDouble()

        /** Previous intervals before index [i] of [times], or the typical one where there are none. */
        fun lags(times: List<Long>, i: Int, typical: Double, name: String) = listOf(
            "Previous $name" to (if (i >= 1) minutes(times[i - 1], times[i]).coerceAtMost(CAP_MINUTES) else typical),
            "One before that" to (if (i >= 2) minutes(times[i - 2], times[i - 1]).coerceAtMost(CAP_MINUTES) else typical)
        )

        // Feeds: from one feed's start to the next
        val feedGaps = feedTimes.zipWithNext { a, b -> minutes(a, b) }
        val typicalFeedGap = median(feedGaps.toDoubleArray()).takeIf { it > 0 } ?: 180.0
        fun feedRow(i: Int) = listOf(
            "Feed length (min)" to (feeds[i].leftDurationSec + feeds[i].rightDurationSec) / 60.0,
            "Bottle (ml)" to (feeds[i].amountMl ?: 0).toDouble(),
            "Is bottle" to if (feeds[i].type == FeedType.BOTTLE) 1.0 else 0.0
        ) + lags(feedTimes, i, typicalFeedGap, "feed gap") + context(feeds[i].timeMillis, "feed")
        val feedSet = dataset(
            RhythmTarget.FEED_GAP,
            feedGaps.indices.filter { feedGaps[it] in 15.0..720.0 }.map { feedRow(it) to feedGaps[it] },
            feeds.indices.lastOrNull()?.let(::feedRow),
            feeds.lastOrNull()?.timeMillis
        )

        // Diapers: from one change to the next
        val changeGaps = changeTimes.zipWithNext { a, b -> minutes(a, b) }
        val typicalChangeGap = median(changeGaps.toDoubleArray()).takeIf { it > 0 } ?: 180.0
        fun changeRow(i: Int) = listOf(
            "Wet" to if (changes[i].type == DiaperType.WET || changes[i].type == DiaperType.BOTH) 1.0 else 0.0,
            "Dirty" to if (changes[i].type == DiaperType.DIRTY || changes[i].type == DiaperType.BOTH) 1.0 else 0.0
        ) + lags(changeTimes, i, typicalChangeGap, "diaper gap") + context(changes[i].timeMillis, "diaper")
        val changeSet = dataset(
            RhythmTarget.DIAPER_GAP,
            changeGaps.indices.filter { changeGaps[it] in 10.0..720.0 }.map { changeRow(it) to changeGaps[it] },
            changes.indices.lastOrNull()?.let(::changeRow),
            changes.lastOrNull()?.timeMillis
        )

        // Sleep length: rows describe falling asleep
        val lengths = sleeps.map { minutes(it.timeMillis, it.endMillis) }
        val typicalLength = median(lengths.toDoubleArray()).takeIf { it > 0 } ?: 60.0
        val typicalAwake = median(sleeps.zipWithNext { a, b -> minutes(a.endMillis, b.timeMillis) }.filter { it > 0 }.toDoubleArray()).takeIf { it > 0 } ?: 90.0
        fun sleepStartRow(start: Long, i: Int) = listOf(
            "Previous sleep (min)" to (if (i >= 1) lengths[i - 1] else typicalLength),
            "Awake before (min)" to (if (i >= 1) minutes(sleeps[i - 1].endMillis, start).coerceIn(0.0, CAP_MINUTES) else typicalAwake)
        ) + context(start, "sleep")
        val lengthSet = dataset(
            RhythmTarget.SLEEP_LENGTH,
            sleeps.indices.filter { lengths[it] in 5.0..900.0 }.map { sleepStartRow(sleeps[it].timeMillis, it) to lengths[it] },
            events.runningSleepStart?.let { sleepStartRow(it, sleeps.size) },
            events.runningSleepStart
        )

        // Awake window: rows describe waking up
        fun wakeRow(i: Int) = listOf(
            "Sleep just ended (min)" to lengths[i],
            "Awake before it (min)" to (if (i >= 1) minutes(sleeps[i - 1].endMillis, sleeps[i].timeMillis).coerceIn(0.0, CAP_MINUTES) else typicalAwake)
        ) + context(sleeps[i].endMillis, "sleep")
        val awake = sleeps.zipWithNext { a, b -> minutes(a.endMillis, b.timeMillis) }
        val awakeSet = dataset(
            RhythmTarget.AWAKE_WINDOW,
            awake.indices.filter { awake[it] in 5.0..720.0 }.map { wakeRow(it) to awake[it] },
            sleeps.indices.lastOrNull()?.takeIf { events.runningSleepStart == null }?.let(::wakeRow),
            sleeps.lastOrNull()?.endMillis?.takeIf { events.runningSleepStart == null }
        )
        return listOf(feedSet, changeSet, awakeSet, lengthSet)
    }
}

/** A rhythm's trained model: null [result] when there's too little data; [predictedMinutes] for the current row. */
class RhythmModel(
    val target: RhythmTarget,
    val samples: Int,
    val result: AutoMlResult?,
    val predictedMinutes: Double?,
    val anchorMillis: Long?,
    val ageOriginMillis: Long?,
    val inputs: Int
)

/**
 * Builds every rhythm's dataset and runs [AutoMl] on each (CPU-bound: call off the main thread). [defaultMinutes]
 * is the app's default estimate per rhythm, scored alongside for comparison: a fixed interval from Settings, or
 * when there's none (null), the average interval.
 */
fun trainRhythmModels(
    events: RhythmEvents,
    defaultMinutes: Map<RhythmTarget, Int?> = emptyMap(),
    onProgress: (target: RhythmTarget, fraction: Float) -> Unit = { _, _ -> }
): List<RhythmModel> = RhythmFeatures.build(events).map { set ->
    if (set.data.size < AutoMl.MIN_ROWS) {
        RhythmModel(set.target, set.data.size, null, null, null, events.birthMillis, set.data.features.size)
    } else {
        val fixed = defaultMinutes[set.target]?.takeIf { it > 0 }
        val default = if (fixed != null) {
            Candidate("Default", "fixed ${formatMinutes(fixed)} from Settings", FixedValue(fixed.toDouble()))
        } else {
            Candidate("Default", "average interval", MeanBaseline)
        }
        val result = AutoMl.fit(set.data, default = default) { onProgress(set.target, it) }
        val predicted = set.current?.let { result.model.predict(it).coerceAtLeast(0.0) }
        RhythmModel(
            set.target,
            set.data.size,
            result,
            predicted,
            set.currentAnchorMillis.takeIf { predicted != null },
            events.birthMillis,
            set.data.features.size
        )
    }
}

/** A model picked in Trends → Models for reminders, Today and the summary widget. */
class SavedRhythmModel(
    val target: RhythmTarget,
    val family: String,
    val settings: String,
    val mae: Double,
    val model: Regressor,
    val ageOriginMillis: Long,
    val trainedAt: Long,
    val inputs: Int, // how many input columns it was trained on
    residuals: List<Double> = emptyList() // its validation errors in minutes (actual − predicted), for overdue estimates
) {
    /** At most 49 evenly spread [residuals], so the saved text stays small. */
    val residuals: List<Double> = residuals.sorted().let { r ->
        if (r.size <= 49) r else List(49) { r[(it * (r.size - 1)) / 48] }
    }

    fun toText(): String = listOf(target.name, family, settings, mae, ageOriginMillis, trainedAt, inputs, model.toText(), residuals.joinToString(","))
        .joinToString(SEP)

    companion object {
        private const val SEP = "\u001F"

        fun fromText(text: String): SavedRhythmModel? = runCatching {
            val f = text.split(SEP)
            SavedRhythmModel(
                RhythmTarget.valueOf(f[0]), f[1], f[2], f[3].toDouble(), Regressor.fromText(f[7]), f[4].toLong(), f[5].toLong(), f[6].toInt(),
                // Saved before overdue estimates: none
                f.getOrNull(8)?.takeIf { it.isNotEmpty() }?.split(',')?.map(String::toDouble).orEmpty()
            )
        }.getOrNull()
    }
}

/**
 * When a saved model expects the next event: [anchorMillis] (the feed, change, sleep start or wake-up it counts
 * from) + its prediction. [residuals] are the model's validation errors in minutes.
 */
data class RhythmPrediction(val anchorMillis: Long, val dueMillis: Long, val residuals: List<Double> = emptyList()) {
    /**
     * When the event now looks most likely: [dueMillis] until it passes; after that, the middle of the model's past
     * outcomes that ran longer than this one has so far (else now). Reminders keep [dueMillis].
     */
    fun expectedMillis(now: Long): Long {
        if (now <= dueMillis || residuals.isEmpty()) return dueMillis
        val later = residuals.map { dueMillis + (it * 60_000).toLong() }.filter { it > now }
        return if (later.isEmpty()) now else median(later.map(Long::toDouble).toDoubleArray()).toLong()
    }
}

/** Predicts with each saved model from recent events (a day or two is enough for its inputs). */
fun predictRhythms(events: RhythmEvents, models: Collection<SavedRhythmModel>): Map<RhythmTarget, RhythmPrediction> = models.mapNotNull { saved ->
    val set = RhythmFeatures.build(RhythmEvents(events.feeds, events.changes, events.sleeps, events.runningSleepStart, saved.ageOriginMillis))
        .first { it.target == saved.target }
    val row = set.current ?: return@mapNotNull null
    val anchor = set.currentAnchorMillis ?: return@mapNotNull null
    // A model saved before an update changed the inputs can't read the new row: no prediction until retrained
    if (row.size != saved.inputs) return@mapNotNull null
    val minutes = runCatching { saved.model.predict(row) }.getOrNull()?.takeIf { it.isFinite() }?.coerceIn(1.0, 24 * 60.0)
        ?: return@mapNotNull null
    saved.target to RhythmPrediction(anchor, anchor + (minutes * 60_000).toLong(), saved.residuals)
}.toMap()
