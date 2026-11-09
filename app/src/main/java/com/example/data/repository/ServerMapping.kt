package com.example.data.repository

import com.example.R
import com.example.data.api.BabyBuddyChangeDto
import com.example.data.api.BabyBuddyFeedingDto
import com.example.data.api.BabyBuddyGrowthDto
import com.example.data.api.BabyBuddyNoteDto
import com.example.data.api.BabyBuddyPumpingDto
import com.example.data.api.BabyBuddySleepDto
import com.example.data.api.BabyBuddyTemperatureDto
import com.example.data.api.BabyBuddyTummyTimeDto
import com.example.data.model.AppText
import com.example.data.model.BreastSide
import com.example.data.model.DiaperEvent
import com.example.data.model.DiaperType
import com.example.data.model.FeedType
import com.example.data.model.FeedingEvent
import com.example.data.model.GrowthEvent
import com.example.data.model.GrowthKind
import com.example.data.model.NoteEvent
import com.example.data.model.PumpingEvent
import com.example.data.model.SleepEvent
import com.example.data.model.TemperatureEvent
import com.example.data.model.TimeFormat
import com.example.data.model.TummyTimeEvent
import com.example.data.model.isNapTime
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong

/*
 * Baby Buddy's records <-> this app's entries: mapping server DTOs to entries, the request bodies sent back, and the
 * generated segments in server notes ("Caregiver: Alex"; on breastfeeds "Left: … | Right: … | Sides: …"; on diaper
 * changes "Amount: … | Rash | Cream: … | Consistency: …"), which are split into fields so notes hold only what people
 * wrote. Pure functions, apart from [newLocalId] for entries the server sent without an id.
 */

private val localIdSeq = AtomicLong(System.currentTimeMillis())

/** A new id for a local entry (unique in this process). */
internal fun newLocalId(): Long = localIdSeq.incrementAndGet()

private val SERVER_COLORS = setOf("black", "brown", "gray", "green", "orange", "red", "white", "yellow")

internal class DiaperExtras(val rash: Boolean, val creams: List<String>, val consistency: String, val amount: String, val notes: String)

/** Removes "Caregiver: X" segments (" | "-separated or on their own line) and returns (caregiver, remaining notes). */
internal fun splitCaregiver(notes: String): Pair<String?, String> {
    var caregiver: String? = null
    val segment = Regex("""^Caregiver:\s*(.+)$""")
    val lines = notes.lines().mapNotNull { line ->
        line.split(" | ").filter { seg ->
            val match = segment.find(seg.trim())
            if (match != null) caregiver = match.groupValues[1].trim()
            match == null
        }.joinToString(" | ").takeIf { it.isNotBlank() }
    }
    return caregiver to lines.joinToString("\n").trim()
}

/** Adds "Caregiver: X" as the last line. */
internal fun withCaregiver(notes: String, caregiver: String?): String = listOfNotNull(notes.ifBlank { null }, caregiver?.let { "Caregiver: $it" }).joinToString("\n")

/** Removes the generated "Left: … | Right: … | Sides: …" segments; returns (side order, remaining notes). */
internal fun splitFeedingMeta(notes: String): Pair<String?, String> {
    var sides: String? = null
    val generated = Regex("""^(Left|Right):\s*\d+m(\s*\d+s)?$""")
    val sidesSegment = Regex("""^Sides:\s*(.+)$""")
    val lines = notes.lines().mapNotNull { line ->
        line.split(" | ").filter { seg ->
            val t = seg.trim()
            sidesSegment.find(t)?.let { sides = it.groupValues[1].trim() }
            !generated.matches(t) && !sidesSegment.matches(t)
        }.joinToString(" | ").takeIf { it.isNotBlank() }
    }
    return sides to lines.joinToString("\n").trim()
}

/** Server notes for a feed: the generated line (breastfeeds) with the caregiver, then the user's notes. */
internal fun composeFeedingNotes(e: FeedingEvent): String {
    if (e.type != FeedType.NURSING) return withCaregiver(e.notes, e.caregiver)
    val line = listOfNotNull(
        "Left: ${e.leftDurationSec / 60}m ${e.leftDurationSec % 60}s",
        "Right: ${e.rightDurationSec / 60}m ${e.rightDurationSec % 60}s",
        e.sides?.let { "Sides: $it" },
        e.caregiver?.let { "Caregiver: $it" }
    ).joinToString(" | ")
    return listOfNotNull(line, e.notes.ifBlank { null }).joinToString("\n")
}

/**
 * Amount (Light/Medium/Heavy), rash, cream and consistency have no server field; they live in one
 * "Amount: … | Rash | Cream: … | Consistency: …" notes line.
 */
internal fun composeDiaperNotes(e: DiaperEvent): String {
    val solid = e.type == DiaperType.DIRTY || e.type == DiaperType.BOTH
    val extras = buildList {
        if (e.type != DiaperType.DRY && e.amount.isNotBlank()) add("Amount: ${e.amount}")
        if (e.rashPresent) add("Rash")
        if (e.skinBarrierApplied.isNotEmpty()) add("Cream: ${e.skinBarrierApplied.joinToString(", ")}")
        if (solid && e.consistency.isNotBlank()) add("Consistency: ${e.consistency}")
    }.joinToString(" | ")
    return listOf(e.notes, extras).filter { it.isNotBlank() }.joinToString("\n")
}

internal fun parseDiaperNotes(notes: String): DiaperExtras {
    val segment = Regex("""^(Amount: .+|Rash|Cream: .+|Consistency: .+)$""")
    val lines = notes.lines()
    val extrasLine = lines.lastOrNull { line ->
        line.isNotBlank() && line.split(" | ").all { segment.matches(it.trim()) }
    } ?: return DiaperExtras(false, emptyList(), "", "", notes)
    val segments = extrasLine.split(" | ").map { it.trim() }
    return DiaperExtras(
        rash = "Rash" in segments,
        creams = segments.firstOrNull { it.startsWith("Cream: ") }?.removePrefix("Cream: ")?.split(", ").orEmpty(),
        consistency = segments.firstOrNull { it.startsWith("Consistency: ") }?.removePrefix("Consistency: ").orEmpty(),
        amount = segments.firstOrNull { it.startsWith("Amount: ") }?.removePrefix("Amount: ").orEmpty(),
        notes = (lines - extrasLine).joinToString("\n").trim()
    )
}

// App colour names <-> the server's colour choices
internal fun appDiaperColor(server: String?): String = when (server) {
    "yellow" -> "Mustard"
    "brown" -> "Tan/Brown"
    "green" -> "Greenish"
    "black" -> "Dark"
    else -> server.orEmpty()
}

internal fun serverDiaperColor(app: String): String = when (app) {
    "Mustard" -> "yellow"
    "Tan/Brown" -> "brown"
    "Greenish" -> "green"
    "Dark" -> "black"
    in SERVER_COLORS -> app
    else -> ""
}

internal fun mapChange(dto: BabyBuddyChangeDto): DiaperEvent {
    val (caregiver, rest) = splitCaregiver(dto.notes.orEmpty())
    val extras = parseDiaperNotes(rest)
    return DiaperEvent(
        id = dto.id ?: newLocalId(),
        serverId = dto.id,
        timeMillis = parseServerTime(dto.time)?.time ?: System.currentTimeMillis(),
        timeFormatted = formatServerTime(dto.time),
        type = when {
            dto.wet && dto.solid -> DiaperType.BOTH
            dto.solid -> DiaperType.DIRTY
            dto.wet -> DiaperType.WET
            else -> DiaperType.DRY
        },
        amount = extras.amount,
        consistency = extras.consistency,
        color = appDiaperColor(dto.color),
        rashPresent = extras.rash,
        skinBarrierApplied = extras.creams,
        notes = extras.notes,
        caregiver = caregiver
    )
}

internal fun mapPumping(dto: BabyBuddyPumpingDto): PumpingEvent {
    val start = dto.start ?: dto.end
    val (caregiver, rest) = splitCaregiver(dto.notes.orEmpty())
    return PumpingEvent(
        id = dto.id ?: newLocalId(),
        serverId = dto.id,
        timeMillis = start?.let(::parseServerTime)?.time ?: System.currentTimeMillis(),
        timeFormatted = start?.let(::formatServerTime).orEmpty(),
        durationMinutes = parseDurationSeconds(dto.duration) / 60,
        amountMl = dto.amount?.toInt() ?: 0,
        notes = rest,
        caregiver = caregiver
    )
}

internal fun mapSleep(dto: BabyBuddySleepDto): SleepEvent {
    val (caregiver, rest) = splitCaregiver(dto.notes.orEmpty())
    val start = parseServerTime(dto.start)?.time ?: System.currentTimeMillis()
    val end = dto.end?.let(::parseServerTime)?.time ?: (start + parseDurationSeconds(dto.duration) * 1000L)
    return SleepEvent(
        id = dto.id ?: newLocalId(),
        serverId = dto.id,
        caregiver = caregiver,
        timeMillis = start,
        endMillis = end,
        timeFormatted = formatServerTime(dto.start),
        isNap = dto.nap ?: isNapTime(start),
        notes = rest
    )
}

internal fun mapTummyTime(dto: BabyBuddyTummyTimeDto): TummyTimeEvent = TummyTimeEvent(
    id = dto.id ?: newLocalId(),
    serverId = dto.id,
    timeMillis = parseServerTime(dto.start)?.time ?: System.currentTimeMillis(),
    timeFormatted = formatServerTime(dto.start),
    durationMinutes = parseDurationSeconds(dto.duration) / 60,
    milestone = dto.milestone.orEmpty(),
    caregiver = splitCaregiver(dto.notes.orEmpty()).first
)

internal fun mapTemperature(dto: BabyBuddyTemperatureDto): TemperatureEvent {
    val (caregiver, rest) = splitCaregiver(dto.notes.orEmpty())
    return TemperatureEvent(
        id = dto.id ?: newLocalId(),
        serverId = dto.id,
        timeMillis = dto.time?.let(::parseServerTime)?.time ?: System.currentTimeMillis(),
        timeFormatted = dto.time?.let(::formatServerTime).orEmpty(),
        temperature = dto.temperature ?: 0.0,
        notes = rest,
        caregiver = caregiver
    )
}

internal fun mapGrowth(kind: GrowthKind, dto: BabyBuddyGrowthDto): GrowthEvent {
    val (caregiver, rest) = splitCaregiver(dto.notes.orEmpty())
    return GrowthEvent(
        id = dto.id ?: newLocalId(),
        serverId = dto.id,
        kind = kind,
        dateMillis = dto.date?.let(::parseLocalDateNoon) ?: System.currentTimeMillis(),
        value = growthValue(kind, dto) ?: 0.0,
        notes = rest,
        caregiver = caregiver
    )
}

internal fun growthValue(kind: GrowthKind, dto: BabyBuddyGrowthDto): Double? = when (kind) {
    GrowthKind.WEIGHT -> dto.weight
    GrowthKind.LENGTH -> dto.height
    GrowthKind.HEAD -> dto.headCircumference
}

/** "2026-10-12" → local midnight starting that day, or null if it doesn't parse. */
internal fun parseLocalDate(date: String): Long? = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)?.time }.getOrNull()

/** "2026-10-12" → local noon of that day (so the day never shifts with time zones). */
internal fun parseLocalDateNoon(date: String): Long? = parseLocalDate(date)?.plus(12 * 3_600_000L)

internal fun localDate(millis: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))

internal fun mapNote(dto: BabyBuddyNoteDto): NoteEvent = NoteEvent(
    id = dto.id ?: newLocalId(),
    serverId = dto.id,
    timeMillis = dto.time?.let(::parseServerTime)?.time ?: System.currentTimeMillis(),
    timeFormatted = dto.time?.let(::formatServerTime).orEmpty(),
    text = dto.note.orEmpty(),
    tags = dto.tags.orEmpty()
)

internal fun mapFeeding(dto: BabyBuddyFeedingDto): FeedingEvent {
    val method = dto.method.lowercase()
    val totalSec = parseDurationSeconds(dto.duration)
    val side = when (method) {
        "left breast" -> BreastSide.LEFT
        "right breast" -> BreastSide.RIGHT
        "both breasts" -> BreastSide.BOTH
        else -> null
    }
    // The server only stores a total duration. For "both breasts", use a
    // "Left: 7m 44s | Right: 6m 40s" note if present, else split evenly.
    val (leftSec, rightSec) = when (side) {
        BreastSide.LEFT -> totalSec to 0
        BreastSide.RIGHT -> 0 to totalSec
        BreastSide.BOTH -> parseSideSplit(dto.notes) ?: (totalSec / 2 to totalSec - totalSec / 2)
        null -> totalSec to 0
    }
    val (caregiver, withoutCaregiver) = splitCaregiver(dto.notes.orEmpty())
    val (sides, userNotes) = splitFeedingMeta(withoutCaregiver)
    return FeedingEvent(
        id = dto.id ?: newLocalId(),
        serverId = dto.id,
        timeMillis = parseServerTime(dto.start)?.time ?: System.currentTimeMillis(),
        bottleFormula = dto.type.lowercase() == "formula",
        timeFormatted = formatServerTime(dto.start),
        type = when {
            dto.type.lowercase() == "solid food" -> FeedType.SOLID
            side != null -> FeedType.NURSING
            else -> FeedType.BOTTLE
        },
        side = side,
        leftDurationSec = leftSec,
        rightDurationSec = rightSec,
        amountMl = dto.amount?.toInt(),
        notes = userNotes,
        caregiver = caregiver,
        sides = sides
    )
}

internal fun parseSideSplit(notes: String?): Pair<Int, Int>? {
    if (notes == null) return null
    fun side(name: String): Int? = Regex("""$name:\s*(?:(\d+)m)?\s*(?:(\d+)s)?""", RegexOption.IGNORE_CASE).find(notes)?.let { m ->
        val min = m.groupValues[1].toIntOrNull()
        val sec = m.groupValues[2].toIntOrNull()
        if (min == null && sec == null) null else (min ?: 0) * 60 + (sec ?: 0)
    }
    val left = side("Left") ?: return null
    val right = side("Right") ?: return null
    return left to right
}

/** Parses Django REST DurationField output: "[D ]HH:MM:SS[.ffffff]". */
internal fun parseDurationSeconds(duration: String?): Int {
    if (duration.isNullOrBlank()) return 0
    val parts = duration.trim().split(" ")
    val days = if (parts.size > 1) parts[0].toIntOrNull() ?: 0 else 0
    val hms = parts.last().substringBefore(".").split(":").map { it.toIntOrNull() ?: 0 }
    val (h, m, sec) = when (hms.size) {
        3 -> Triple(hms[0], hms[1], hms[2])
        2 -> Triple(0, hms[0], hms[1])
        else -> Triple(0, 0, hms.firstOrNull() ?: 0)
    }
    return days * 86400 + h * 3600 + m * 60 + sec
}

internal fun parseServerTime(isoString: String): Date? {
    val normalized = isoString.replace(Regex("""\.\d+"""), "").replace(Regex("Z$"), "+00:00")
    return runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(normalized) }.getOrNull()
}

internal fun formatServerTime(isoString: String): String {
    // Server timestamps are UTC ("...Z") or carry an offset; show them in device-local time
    val date = parseServerTime(isoString) ?: return isoString
    return TimeFormat.clock(date)
}

internal fun formatAge(birthDate: String): String? {
    val birth = parseLocalDate(birthDate) ?: return null
    val b = Calendar.getInstance().apply { timeInMillis = birth }
    val now = Calendar.getInstance()
    var months = (now.get(Calendar.YEAR) - b.get(Calendar.YEAR)) * 12 + now.get(Calendar.MONTH) - b.get(Calendar.MONTH)
    if (now.get(Calendar.DAY_OF_MONTH) < b.get(Calendar.DAY_OF_MONTH)) months--
    if (months < 0) return null
    b.add(Calendar.MONTH, months)
    val days = ((now.timeInMillis - b.timeInMillis) / 86_400_000L).toInt()
    return if (months == 0) AppText.get(R.string.age_days, days) else AppText.get(R.string.age_months_days, months, days)
}

internal fun feedingBody(e: FeedingEvent): Map<String, Any?> {
    val start = isoUtc(e.timeMillis)
    return if (e.type == FeedType.NURSING) {
        val method = when {
            e.leftDurationSec > 0 && e.rightDurationSec > 0 -> "both breasts"
            e.rightDurationSec > 0 -> "right breast"
            e.leftDurationSec > 0 -> "left breast"
            e.side == BreastSide.RIGHT -> "right breast"
            else -> "left breast"
        }
        mapOf(
            "start" to start,
            "end" to isoUtc(e.timeMillis + (e.leftDurationSec + e.rightDurationSec) * 1000L),
            "type" to "breast milk",
            "method" to method,
            "notes" to composeFeedingNotes(e)
        )
    } else {
        // Bottles are instantaneous on this server (start == end)
        mapOf(
            "start" to start,
            "end" to start,
            "type" to if (e.bottleFormula) "formula" else "breast milk",
            "method" to "bottle",
            "amount" to e.amountMl?.toDouble(),
            "notes" to composeFeedingNotes(e)
        )
    }
}

internal fun changeBody(e: DiaperEvent): Map<String, Any?> {
    val solid = e.type == DiaperType.DIRTY || e.type == DiaperType.BOTH
    return mapOf(
        "time" to isoUtc(e.timeMillis),
        "wet" to (e.type == DiaperType.WET || e.type == DiaperType.BOTH),
        "solid" to solid,
        "color" to if (solid) serverDiaperColor(e.color) else "",
        "notes" to withCaregiver(composeDiaperNotes(e), e.caregiver)
    )
}

internal fun pumpingBody(e: PumpingEvent): Map<String, Any?> = mapOf(
    "start" to isoUtc(e.timeMillis),
    "end" to isoUtc(e.timeMillis + e.durationMinutes * 60_000L),
    "amount" to e.amountMl.toDouble(),
    "notes" to withCaregiver(e.notes, e.caregiver)
)

internal fun temperatureBody(e: TemperatureEvent): Map<String, Any?> = mapOf(
    "time" to isoUtc(e.timeMillis),
    "temperature" to e.temperature,
    "notes" to withCaregiver(e.notes, e.caregiver)
)

internal fun growthBody(e: GrowthEvent): Map<String, Any?> = mapOf(
    "date" to localDate(e.dateMillis),
    e.kind.field to e.value,
    "notes" to withCaregiver(e.notes, e.caregiver)
)

internal fun sleepBody(e: SleepEvent): Map<String, Any?> = mapOf(
    "start" to isoUtc(e.timeMillis),
    "end" to isoUtc(e.endMillis),
    "nap" to e.isNap,
    "notes" to withCaregiver(e.notes, e.caregiver)
)

internal fun tummyBody(e: TummyTimeEvent): Map<String, Any?> = mapOf(
    "start" to isoUtc(e.timeMillis),
    "end" to isoUtc(e.timeMillis + e.durationMinutes * 60_000L),
    "milestone" to e.milestone,
    "notes" to withCaregiver("", e.caregiver)
)

/** A note's fields; its tags are sent as they are, so an edit keeps them (and can clear them). */
internal fun noteBody(e: NoteEvent): Map<String, Any?> = mapOf("note" to e.text, "time" to isoUtc(e.timeMillis), "tags" to e.tags)

/** A new note leaves out empty tags. */
internal fun newNoteBody(e: NoteEvent): Map<String, Any?> = noteBody(e) + ("tags" to e.tags.takeIf { it.isNotEmpty() })

internal fun isoUtc(millis: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    .apply { timeZone = TimeZone.getTimeZone("UTC") }
    .format(Date(millis))
