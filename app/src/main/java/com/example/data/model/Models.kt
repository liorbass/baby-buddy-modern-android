package com.example.data.model

import com.example.R

data class BabyProfile(
    val id: Int = 1,
    val name: String = "Leo James",
    val shortName: String = "Leo",
    val ageFormatted: String = "3m 12d",
    val birthDate: String = "2024-07-12",
    val weightKg: Double = 6.2,
    val avatarUrl: String = "https://lh3.googleusercontent.com/aida-public/AB6AXuBqhKMwHvhfsoT6IwAJ9fSRqNIifbiWVIEcSQsReFQ3_H0KX7Qlf_DGn8saBc9UuOQiLsi0XXlpihNmkCcSMx4iM9Ee-97dLHOiKB22_xgP2JN2QLNJx0srLL_n6wmK2r2tcI4fpgC4K5KB7dP-2op_TeXSBhACNzRXS7WRYywazhooNT1rjjAMGT9kmJykKTxQ1o1tBdFnbv5fWFZ15lekXqs3WL6VBwHxMkhdGehXoWUL0ohnxl7F"
)

enum class FeedType {
    NURSING,
    BOTTLE,
    SOLID
}

enum class BreastSide {
    LEFT,
    RIGHT,
    BOTH
}

data class FeedingEvent(
    val id: Long = System.currentTimeMillis(),
    val serverId: Long? = null, // Baby Buddy record id, once saved on the server
    val caregiver: String? = null, // who logged it ("Caregiver: Alex" in the server notes)
    val sides: String? = null, // side order from the server notes, e.g. "LEFT -> RIGHT"
    val timeMillis: Long = System.currentTimeMillis(), // feed start
    val bottleFormula: Boolean = false, // bottle contents: formula vs. breast milk
    val childId: Int = 1,
    val timeFormatted: String = "1:45 PM",
    val type: FeedType = FeedType.NURSING,
    val side: BreastSide? = BreastSide.LEFT,
    val leftDurationSec: Int = 14 * 60,
    val rightDurationSec: Int = 0,
    val amountMl: Int? = null,
    val notes: String = "Good sustained latch",
    val observations: List<String> = listOf("Good Latch", "Burped Well"),
    val imageUrl: String? = "https://lh3.googleusercontent.com/aida-public/AB6AXuB1LA9nfExUl_myT8vlNGyS8Xo6JH5jSfg64vbz4F9j934sD6BYs_r-nXPwzyrmQhLe-VYTczKduITneBnqyaaHnsgKqueAEKhWYHIwpeOWZ8IuqVPgTXquWyhmMjgFDusWFHSxOkpVtU53fY2Wk5zA5QjBTbxW4FjJr-KBoaaNGMDDbDdyrRPDUSPqsrAFq_XSI3WE9JqIHGLbRxHb6LchMnXgvfk0IivMvBjhvCdJrTqoValtS770"
) {
    val totalDurationMinutes: Int
        get() = (leftDurationSec + rightDurationSec) / 60
    val leftDurationMinutes: Int
        get() = leftDurationSec / 60
    val rightDurationMinutes: Int
        get() = rightDurationSec / 60

    /** The side a breastfeed ended on: the last of its side order ("LEFT -> RIGHT"), else its single side. */
    val lastSide: BreastSide?
        get() {
            if (type != FeedType.NURSING) return null
            sides?.split("->")?.last()?.trim()?.uppercase()?.let { last ->
                if (last.startsWith("LEFT")) return BreastSide.LEFT
                if (last.startsWith("RIGHT")) return BreastSide.RIGHT
            }
            return when (side) {
                BreastSide.LEFT -> BreastSide.LEFT

                BreastSide.RIGHT -> BreastSide.RIGHT

                else -> when {
                    leftDurationSec > 0 && rightDurationSec == 0 -> BreastSide.LEFT
                    rightDurationSec > 0 && leftDurationSec == 0 -> BreastSide.RIGHT
                    else -> null
                }
            }
        }
}

/** A sleep or nap (Baby Buddy api/sleep/): start and end, nap or night sleep, and a note. */
data class SleepEvent(
    val id: Long = System.currentTimeMillis(),
    val serverId: Long? = null, // Baby Buddy record id, once saved on the server
    val caregiver: String? = null, // who logged it ("Caregiver: Alex" in the server notes)
    val timeMillis: Long = System.currentTimeMillis(), // fell asleep
    val endMillis: Long = timeMillis, // woke up
    val timeFormatted: String = "",
    val isNap: Boolean = true,
    val notes: String = ""
) {
    val durationMinutes: Int get() = ((endMillis - timeMillis) / 60_000L).toInt().coerceAtLeast(0)
}

/** A sleep being timed on this phone; it's saved to the server when it ends. */
data class SleepTimerState(
    val startMillis: Long = 0L,
    val notes: String = "",
    val changedAtMillis: Long = 0L // when it was started or edited, on any phone
) {
    val isRunning: Boolean get() = startMillis > 0L
}

/** Daytime starts count as naps by default (Baby Buddy's own default is similar). */
fun isNapTime(startMillis: Long): Boolean = java.util.Calendar.getInstance().apply { timeInMillis = startMillis }.get(java.util.Calendar.HOUR_OF_DAY) in 7..18

/** "45m", "2h 05m". */
fun formatMinutes(minutes: Int): String {
    // Plain JVM tests have no resources: they get the English format
    val res = AppText.resources ?: return if (minutes >= 60) "%dh %02dm".format(minutes / 60, minutes % 60) else "${minutes}m"
    return if (minutes >= 60) res.getString(R.string.duration_hours_minutes, minutes / 60, minutes % 60) else res.getString(R.string.duration_minutes, minutes)
}

enum class DiaperType {
    WET,
    DIRTY,
    BOTH,
    DRY
}

/** Diaper amounts, lightest first (kept in the server notes as "Amount: …"). */
val DIAPER_AMOUNTS = listOf("Light", "Medium", "Heavy")

data class DiaperEvent(
    val id: Long = System.currentTimeMillis(),
    val serverId: Long? = null, // Baby Buddy record id, once saved on the server
    val caregiver: String? = null, // who logged it ("Caregiver: Alex" in the server notes)
    val timeMillis: Long = System.currentTimeMillis(),
    val childId: Int = 1,
    val timeFormatted: String = "1:05 PM",
    val type: DiaperType = DiaperType.BOTH,
    val amount: String = "Medium", // Light, Medium, Heavy
    val consistency: String = "Seedy / Mustard",
    val color: String = "Mustard",
    val rashPresent: Boolean = false,
    val skinBarrierApplied: List<String> = listOf("Aquaphor / Barrier"),
    val notes: String = "Aquaphor ointment barrier applied",
    val imageUrl: String? = "https://lh3.googleusercontent.com/aida-public/AB6AXuDpv9FSE-MHQY48199iEwcNZXlxY5Jwp07B99kVFDl1PZ_PxdIknR9M7UHfYJ5RRxxg0X1GrZrYEb7WsgLx8S_mfvX1EyrUVrR30zEOlpNSEcQqQV5aSt0hXuGk0cFp_slc2OroH7bv2gJBYrLyqAlMSjmUu3comMiFoKURIJqPPH_2kHX1FxjC3ohDg6gv-IsgWgGDVctHx5QLR8ayzCOkbVn8flBUMlS57-cZlgwDXrPt0AHE68PP"
)

data class TummyTimeEvent(
    val id: Long = System.currentTimeMillis(),
    val serverId: Long? = null, // Baby Buddy record id, once saved on the server
    val caregiver: String? = null, // who logged it ("Caregiver: Alex" in the server notes)
    val timeMillis: Long = System.currentTimeMillis(), // session start
    val childId: Int = 1,
    val timeFormatted: String = "10:30 AM",
    val durationMinutes: Int = 15,
    val milestone: String = "Pushed up on forearms, tracking rattles"
)

data class LiveTimerState(
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val activeBreast: BreastSide = BreastSide.RIGHT,
    val leftSeconds: Int = 0,
    val rightSeconds: Int = 0,
    val sessionStartedTime: String = "Ready",
    val softChimeEnabled: Boolean = true,
    val bottleSupplementEnabled: Boolean = false,
    val bottleAmountOz: Double = 0.0,
    val bottleType: String = "Pumped Breast Milk",
    val observations: Set<String> = emptySet(),
    val notes: String = "",
    // Live-synced session: the feeding record it updates, when it started, and pause bookkeeping
    val feedingLocalId: Long? = null,
    val sessionStartMillis: Long = 0L,
    val pausedAtMillis: Long = 0L,
    val firstSide: BreastSide? = null,
    val lastActiveMillis: Long = 0L, // a hand-set finish time on a paused feed (else the pause time)
    val changedAtMillis: Long = 0L, // last start/tap/edit, on any phone (newest wins when phones disagree)
    val feedingServerId: Long? = null, // the feed's server record, once known (lets other phones link to it)
    val editCount: Int = 0 // bumped on manual time edits, so widgets/notification redraw their clocks
) {
    val totalSeconds: Int get() = leftSeconds + rightSeconds
    val leftPercentage: Int
        get() = if (totalSeconds > 0) ((leftSeconds.toDouble() / totalSeconds) * 100).toInt() else 50
    val rightPercentage: Int
        get() = 100 - leftPercentage
}

data class ServerConnectionConfig(
    val serverUrl: String = "",
    val apiToken: String = "",
    val isConnected: Boolean = false,
    val isDemoMode: Boolean = false,
    val lastSyncFormatted: String = "Not Connected",
    val lastSyncMillis: Long = 0L, // when today's entries were last fetched from the server
    val latencyMs: Int = 0,
    val allowSelfSignedCert: Boolean = false,
    val pinnedCertSha256: String = "", // the self-signed certificate the user trusted (SHA-256), if any
    val cfClientId: String = "", // Cloudflare Access service token, optional
    val cfClientSecret: String = "",
    val syncCadence: String = "Instant", // Instant, 5 Mins, Manual
    val detectedChildName: String = "",
    val autoDetectChild: Boolean = true
)

/** Tag on the note that records how much breast milk is stored (shared by all phones). */
const val MILK_STASH_TAG = "milk-stash"

/** Baby Buddy has no bath records, so a bath is a note with this tag (shared across phones and the web UI). */
const val BATH_TAG = "bath"

/** A bath note's text when nothing else was written. */
const val BATH_NOTE_TEXT = "Bath"

/** A Baby Buddy note tag, offered as a one-tap quick note on Today. */
data class NoteTag(val name: String, val color: String? = null, val lastUsedMillis: Long = 0L)

/** Reminder notifications (Settings → Notifications); 0 minutes = off. */
data class ReminderSettings(
    val feedAfterMinutes: Int = 0, // remind when this long has passed since the last feed started
    val awakeAfterMinutes: Int = 0, // remind when the baby has been awake this long
    val sleepAfterMinutes: Int = 0, // remind when a timed sleep has lasted this long
    val diaperAfterMinutes: Int = 0, // remind when this long has passed since the last diaper change
    val earlyMinutes: Int = 0 // also a heads-up this long before each reminder is due; 0 = none
) {
    val anyOn: Boolean get() = feedAfterMinutes > 0 || awakeAfterMinutes > 0 || sleepAfterMinutes > 0 || diaperAfterMinutes > 0
}

/** What other caregivers logged while this phone was away from the app ("While you were away" on Today). */
data class HandoffSummary(
    val sinceMillis: Long,
    val feedings: List<FeedingEvent> = emptyList(),
    val diapers: List<DiaperEvent> = emptyList(),
    val sleeps: List<SleepEvent> = emptyList()
) {
    val isEmpty: Boolean get() = feedings.isEmpty() && diapers.isEmpty() && sleeps.isEmpty()
    val caregivers: List<String>
        get() = (feedings.map { it.caregiver } + diapers.map { it.caregiver } + sleeps.map { it.caregiver }).filterNotNull().distinct()
}

/** What this phone tracks (Settings → Tracked items); a hidden item disappears everywhere in the app. */
enum class TrackedItem(private val labelRes: Int, private val descriptionRes: Int) {
    BREASTFEEDING(R.string.item_breastfeeding, R.string.item_breastfeeding_desc),
    BOTTLE(R.string.item_bottle, R.string.item_bottle_desc),
    DIAPERS(R.string.item_diapers, R.string.item_diapers_desc),
    SLEEP(R.string.item_sleep, R.string.item_sleep_desc),
    PUMPING(R.string.item_pumping, R.string.item_pumping_desc),
    MILK_STASH(R.string.item_milk_stash, R.string.item_milk_stash_desc),
    TUMMY_TIME(R.string.item_tummy_time, R.string.item_tummy_time_desc),
    QUICK_NOTES(R.string.item_quick_notes, R.string.item_quick_notes_desc),
    BATH(R.string.item_bath, R.string.item_bath_desc),
    GROWTH(R.string.item_growth, R.string.item_growth_desc);

    val label: String get() = AppText.get(labelRes)
    val description: String get() = AppText.get(descriptionRes)
}

/** Which side a one-tap breastfeed starts on (timer widget, Quick Settings tile); Settings → You. */
enum class StartSide(private val labelRes: Int, private val descriptionRes: Int) {
    OPPOSITE(R.string.start_side_opposite, R.string.start_side_opposite_desc),
    SAME(R.string.start_side_same, R.string.start_side_same_desc),
    LEFT(R.string.start_side_left, R.string.start_side_left_desc),
    RIGHT(R.string.start_side_right, R.string.start_side_right_desc);

    val label: String get() = AppText.get(labelRes)
    val description: String get() = AppText.get(descriptionRes)

    /** The side to start on, given the side the last breastfeed ended on (null if unknown: left). */
    fun after(lastSide: BreastSide?): BreastSide = when (this) {
        OPPOSITE -> if (lastSide == BreastSide.LEFT) BreastSide.RIGHT else BreastSide.LEFT
        SAME -> if (lastSide == BreastSide.RIGHT) BreastSide.RIGHT else BreastSide.LEFT
        LEFT -> BreastSide.LEFT
        RIGHT -> BreastSide.RIGHT
    }
}

/** Light/dark choice (Settings → Appearance). */
enum class ThemeMode(private val labelRes: Int, private val descriptionRes: Int) {
    BY_TIME(R.string.theme_by_time, R.string.theme_by_time_desc),
    SYSTEM(R.string.theme_system, R.string.theme_system_desc),
    LIGHT(R.string.theme_light, R.string.theme_light_desc),
    DARK(R.string.theme_dark, R.string.theme_dark_desc);

    val label: String get() = AppText.get(labelRes)
    val description: String get() = AppText.get(descriptionRes)
}

/** Shortcut buttons at the top of Today; which ones show, and their order, is up to the user. */
enum class QuickAction(private val labelRes: Int) {
    FEEDING(R.string.action_feeding),
    DIAPER(R.string.action_diaper),
    PUMP(R.string.action_pump),
    TUMMY_TIME(R.string.action_tummy_time),
    SLEEP(R.string.action_sleep),
    BATH(R.string.action_bath);

    val label: String get() = AppText.get(labelRes)

    companion object {
        val DEFAULT = listOf(FEEDING, DIAPER)
    }
}

enum class DashboardWidgetType(private val nameRes: Int, private val categoryRes: Int) {
    LAST_FEED(R.string.card_last_feed, R.string.card_category_feeding),
    LAST_DIAPER(R.string.card_last_diaper, R.string.card_category_diapers),
    WAKE_WINDOWS(R.string.card_wake_windows, R.string.card_category_sleep),
    TUMMY_TIME_GOAL(R.string.card_tummy_time, R.string.card_category_activity),
    HEALTH_VITALS(R.string.card_temperature, R.string.card_category_health),
    CAREGIVER_NOTES(R.string.card_notes, R.string.card_category_family),
    GROWTH(R.string.card_growth, R.string.card_category_health);

    val displayName: String get() = AppText.get(nameRes)
    val category: String get() = AppText.get(categoryRes)
}

/** The tiles in Today's summary grid; which show is chosen in Customize Today (and tracked items still apply). */
enum class TodayTile(private val labelRes: Int, val item: TrackedItem?) {
    FEEDS(R.string.tile_feeds, null), // shown while breast or bottle feeds are tracked
    DIAPERS(R.string.tile_diapers, TrackedItem.DIAPERS),
    SLEEP(R.string.tile_sleep, TrackedItem.SLEEP),
    WAKE_WINDOW(R.string.tile_wake_window, TrackedItem.SLEEP),
    PUMPED(R.string.tile_pumped, TrackedItem.PUMPING),
    MILK_STASH(R.string.tile_milk_stash, TrackedItem.MILK_STASH),
    TUMMY_TIME(R.string.tile_tummy_time, TrackedItem.TUMMY_TIME),
    BATH(R.string.tile_last_bath, TrackedItem.BATH);

    val label: String get() = AppText.get(labelRes)
}

data class DashboardWidgetConfig(
    val type: DashboardWidgetType,
    val title: String,
    val subtitle: String,
    val isEnabled: Boolean = true,
    val order: Int = 0
)

data class TummyTimeGoal(
    val dailyGoalMinutes: Int = 20,
    val completedMinutes: Int = 0,
    val isLiveTiming: Boolean = false,
    val liveTimerSeconds: Int = 0
) {
    val progressFraction: Float
        get() = (completedMinutes.toFloat() / dailyGoalMinutes.toFloat()).coerceIn(0f, 1f)
}

/** A temperature reading, in the unit the Baby Buddy server uses. */
data class TemperatureEvent(
    val id: Long = System.currentTimeMillis(),
    val serverId: Long? = null,
    val timeMillis: Long = System.currentTimeMillis(),
    val timeFormatted: String = "",
    val temperature: Double = 0.0,
    val notes: String = "",
    val caregiver: String? = null
)

/** Used only to pick the WHO reference (boys' or girls' charts); Baby Buddy has no such field. Settings → You. */
enum class BabySex(private val labelRes: Int) {
    BOY(R.string.sex_boy),
    GIRL(R.string.sex_girl);

    val label: String get() = AppText.get(labelRes)
}

/**
 * A growth measurement and its Baby Buddy endpoint ([endpoint]) and value field ([field]).
 * Length is the lying-down length the WHO uses under 2 (the server calls it "height").
 */
enum class GrowthKind(
    private val labelRes: Int,
    private val shortLabelRes: Int,
    val unit: String,
    val endpoint: String,
    val field: String,
    val plausible: ClosedFloatingPointRange<Double>,
    val example: String
) {
    WEIGHT(R.string.growth_weight, R.string.growth_weight, "kg", "weight", "weight", 0.5..40.0, "4.2"),
    LENGTH(R.string.growth_length, R.string.growth_length, "cm", "height", "height", 30.0..130.0, "55"),
    HEAD(R.string.growth_head, R.string.growth_head_short, "cm", "head-circumference", "head_circumference", 20.0..60.0, "37");

    val label: String get() = AppText.get(labelRes)
    val shortLabel: String get() = AppText.get(shortLabelRes)

    companion object {
        fun forEndpoint(endpoint: String): GrowthKind? = entries.firstOrNull { it.endpoint == endpoint }
    }
}

/** One measurement. The server keeps only the date; [dateMillis] is local noon of that day. */
data class GrowthEvent(
    val id: Long = System.currentTimeMillis(),
    val serverId: Long? = null,
    val kind: GrowthKind = GrowthKind.WEIGHT,
    val dateMillis: Long = System.currentTimeMillis(),
    val value: Double = 0.0, // kg or cm, the server's units
    val notes: String = "",
    val caregiver: String? = null
)

/** "6.1 kg", "57.5 cm" (up to two decimals, no trailing zeros; the phone's decimal separator, "6,1 kg" in French). */
fun formatGrowth(kind: GrowthKind, value: Double): String = java.math.BigDecimal(value).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    .replace('.', java.text.DecimalFormatSymbols.getInstance().decimalSeparator) + " " + kind.unit

/** Age in days at [atMillis] for a "yyyy-MM-dd" birth date, or null if it can't be read. */
fun ageInDays(birthDate: String, atMillis: Long): Double? = runCatching {
    val birth = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(birthDate)!!.time
    (atMillis - birth) / 86_400_000.0
}.getOrNull()?.takeIf { it >= 0 }

/** WHO percentile of a measurement, or null without sex/birth date or past 2 years. */
fun growthPercentile(event: GrowthEvent, birthDate: String?, sex: BabySex?): Double? {
    if (sex == null || birthDate == null) return null
    val age = ageInDays(birthDate, event.dateMillis)?.takeIf { it <= WhoGrowthStandards.MAX_DAYS } ?: return null
    return WhoGrowthStandards.percentile(event.kind, sex, age, event.value)
}

/** "52nd", "3rd", "<1st", ">99th" (in the phone's language). */
fun ordinalPercentile(p: Double): String {
    if (p < 1) return AppText.get(R.string.percentile_below, ordinal(1))
    if (p > 99) return AppText.get(R.string.percentile_above, ordinal(99))
    return ordinal(kotlin.math.round(p).toInt())
}

/** "52nd" in English, "52e" in French. */
fun ordinal(n: Int): String = android.icu.text.MessageFormat("{0,ordinal}", java.util.Locale.getDefault()).format(arrayOf(n))

/** A date pattern for [skeleton] ("MMMd", "EEEEMMMd") in the phone's language and field order. */
fun localDatePattern(skeleton: String): String = android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), skeleton)

/** Parses a typed value ("4,55" too); null unless it's plausible for a baby. */
fun parseGrowth(kind: GrowthKind, text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in kind.plausible }

/** A Baby Buddy note (e.g. "Bath"), with its tags. */
data class NoteEvent(
    val id: Long = System.currentTimeMillis(),
    val serverId: Long? = null,
    val timeMillis: Long = System.currentTimeMillis(),
    val timeFormatted: String = "",
    val text: String = "",
    val tags: List<String> = emptyList()
)

/** Average time between feed starts, per day, for the feed-interval card on Today. */
data class FeedIntervalStats(
    val dailyAverageMinutes: List<Int?>, // last 7 days, oldest first; null = fewer than 2 feeds
    val thisWeekAverageMinutes: Int?,
    val lastWeekAverageMinutes: Int?
)

/** One local calendar day of server data, for the Trends screen. */
data class DayStats(
    val label: String, // e.g. "Mon 6"
    val feeds: Int,
    val nursingMinutes: Int,
    val bottleMl: Int,
    val wet: Int,
    val dirty: Int,
    val isToday: Boolean,
    val leftMinutes: Int = 0,
    val rightMinutes: Int = 0,
    val feedMarks: List<FeedMark> = emptyList(),
    /** Longest gap between feeds that starts on this day (may run past midnight), or null. */
    val longestGapStartMinute: Int? = null,
    val longestGapMinutes: Int = 0,
    val diaperMarks: List<DiaperMark> = emptyList(),
    /** Sleep within this day; a sleep across midnight is split between the two days. */
    val sleepMarks: List<SleepMark> = emptyList(),
    val sleepMinutes: Int = 0,
    val naps: Int = 0, // naps that started this day
    val longestSleepMinutes: Int = 0 // longest sleep that started this day
)

/** One diaper change's position within its day, for the diaper-rhythm chart. */
data class DiaperMark(val minute: Int, val wet: Boolean, val dirty: Boolean)

/** One stretch of sleep within a day, for the sleep-rhythm chart. */
data class SleepMark(val startMinute: Int, val durationMinutes: Int, val isNap: Boolean)

/** One feed's position within its day, for the feeding-rhythm chart. */
data class FeedMark(val startMinute: Int, val durationMinutes: Int, val isBottle: Boolean)

/** One local calendar day of entries, for the History week calendar. */
data class HistoryDay(
    val dayStartMillis: Long, // local midnight
    val feedings: List<FeedingEvent> = emptyList(),
    val diapers: List<DiaperEvent> = emptyList(),
    val tummyTimes: List<TummyTimeEvent> = emptyList(),
    val pumpings: List<PumpingEvent> = emptyList(),
    val sleeps: List<SleepEvent> = emptyList(),
    val baths: List<NoteEvent> = emptyList(), // notes tagged BATH_TAG
    val notes: List<NoteEvent> = emptyList() // all other notes
)

data class PumpingEvent(
    val id: Long = System.currentTimeMillis(),
    val serverId: Long? = null, // Baby Buddy record id, once saved on the server
    val caregiver: String? = null, // who logged it ("Caregiver: Alex" in the server notes)
    val timeMillis: Long = System.currentTimeMillis(), // session start
    val timeFormatted: String = "",
    val durationMinutes: Int = 0,
    val amountMl: Int = 0,
    val notes: String = ""
)

/** "7:06 PM" for today, "Yesterday 7:06 PM", else "Oct 8, 7:06 PM". */
fun dayAndTime(timeMillis: Long, clock: String): String {
    val start = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    return when {
        timeMillis >= start -> clock
        timeMillis >= start - 24 * 60 * 60_000L -> AppText.get(R.string.yesterday_at, clock)
        else -> AppText.get(R.string.date_at, java.text.SimpleDateFormat(localDatePattern("MMMd"), java.util.Locale.getDefault()).format(java.util.Date(timeMillis)), clock)
    }
}
