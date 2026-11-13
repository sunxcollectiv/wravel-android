//
//  ItineraryAPI.kt
//  outbound
//

package earth.outbound

import earth.outbound.networking.Endpoint
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** `Itinerary` from `GET /trips/{id}/itinerary`: the trip plus a day-by-day breakdown. */
@Serializable
data class Itinerary(
    val trip: Trip,
    /**
     * Every trip merged into this view: `trip` and the user's other trips in its group,
     * by start date. Each item sits on one of them, with that trip's members. Just
     * `trip` from an older server that doesn't send it.
     */
    val trips: List<Trip> = listOf(trip),
    val days: List<ItineraryDay>,
    /**
     * Places whose Google data is due a refresh. The itinerary is usable as is; the
     * client asks `POST /places/refresh` for these and patches its cards by id. None
     * from an older server that doesn't send it.
     */
    val stalePlaceIds: List<String> = emptyList(),
)

/** One calendar day of a trip, including any lodging that spans it and the day's entries. */
@Serializable(with = ItineraryDaySerializer::class)
data class ItineraryDay(
    val date: String,
    val dayNumber: Int,
    val lodging: List<LodgingStay>,
    /**
     * The day's timeline. An item can appear more than once across the itinerary (its
     * departure and arrival, check-in and check-out, layovers), sharing one `item.id`.
     */
    val entries: List<ItineraryEntry>,
    /**
     * A day between the user's trips in a group with nothing on it, as the server
     * decides. Shown only as a date in the day picker, not as a page. False from a
     * server that doesn't send it.
     */
    val isGap: Boolean = false,
    /**
     * The trip this date belongs to (on a date two trips share, the one the other was
     * added to). Null for a date none covers, or from a server that doesn't send it.
     */
    val tripId: String? = null,
) {
    val id: Int get() = dayNumber

    /** This day without any entries or lodging for the given item, e.g. once it's deleted. */
    fun removing(itemId: String): ItineraryDay =
        copy(
            lodging = lodging.filter { it.item.id != itemId },
            entries = entries.filter { it.item.id != itemId },
        )

    /** The distinct items on this day (entries and lodging), one per record. */
    val uniqueItems: List<TripItem>
        get() = (entries.map { it.item } + lodging.map { it.item }).distinctBy { it.id }

    /**
     * The day's calendar date (local midnight), or null if the string won't parse.
     * Used to seed a new item's default date to the day being viewed.
     */
    val calendarDate: Instant?
        get() = isoDate(date)?.atStartOfDay(ZoneId.systemDefault())?.toInstant()

    /** The abbreviated weekday in caps for the day picker, e.g. "THU". */
    val weekdayShort: String
        get() = isoDate(date)?.let { weekday.format(it).uppercase() } ?: ""

    /** The calendar day-of-month for the day picker, e.g. `1`. */
    val dayOfMonth: Int
        get() = isoDate(date)?.dayOfMonth ?: dayNumber

    companion object {
        /**
         * A day's entries in display order: by the wall clock shown on each card, ties
         * broken by the instant (UTC for zoneless). The server orders by UTC instant,
         * which zoneless items don't have, so they'd otherwise land after zoned ones
         * regardless of time.
         *
         * A transit's entries (departure, layovers, arrival) then stay together in travel
         * order, at the spot of its first entry on the day. Wall clocks in different zones
         * don't compare, so sorted alone a layover could land before its own departure,
         * and nothing else happens between legs anyway.
         */
        fun ordered(entries: List<ItineraryEntry>): List<ItineraryEntry> {
            val sorted = entries.sortedWith(compareBy({ it.displayLocal }, { it.displayInstant }))
            val transit = sorted.filter { it.item.kind == TripItemKind.Transit }
                .groupBy { it.item.id }
                .mapValues { (_, group) -> group.sortedBy { it.travelOrder } }
            val placed = mutableSetOf<String>()
            return sorted.flatMap { entry ->
                val group = transit[entry.item.id] ?: return@flatMap listOf(entry)
                if (placed.add(entry.item.id)) group else emptyList()
            }
        }

        private val weekday = DateTimeFormatter.ofPattern("EEE", Locale.US)

        /** A few sample days for previewing the paged itinerary design. */
        val sampleDays: List<ItineraryDay> by lazy {
            fun item(
                hour: Int, minute: Int, kind: TripItemKind, title: String,
                notes: String? = null, tags: List<String> = emptyList(), image: String? = null,
            ): ItineraryEntry {
                // Wall clocks are floating UTC instants (see `TripItem.startsAtLocal`).
                val date = LocalDateTime.of(2026, 11, 1, hour, minute).toInstant(ZoneOffset.UTC)
                return ItineraryEntry(
                    item = TripItem(
                        id = title, tripId = "sample", kind = kind,
                        title = title, displayTitle = title, notes = notes, tags = tags,
                        startsAtLocal = date, startsAtUtc = null, startTimezone = null,
                        endsAtLocal = null, endsAtUtc = null, endTimezone = null,
                        imageUrl = image, createdAt = date, updatedAt = date,
                    ),
                )
            }
            /** A stay as it applies to one day, for the lodging bar. */
            fun stay(name: String, phase: LodgingPhase) =
                LodgingStay(item(15, 0, TripItemKind.Lodging, name).item, phase)
            // The lodging runs through every state of the bar: a check-in, a night in the
            // middle of a stay, a changeover, a check-out, a day with nowhere booked, then
            // a one-night stay (check-in one day, check-out the next).
            listOf(
                ItineraryDay(
                    date = "2026-11-01", dayNumber = 1, lodging = listOf(stay("Park Hyatt Tokyo", LodgingPhase.CheckIn)),
                    entries = listOf(
                        item(12, 0, TripItemKind.Lodging, "Early check-in confirmed", notes = "Standard check-in 15:00"),
                        item(15, 0, TripItemKind.Activity, "Sean Salim arrives", notes = "NH 106 · joins from Osaka"),
                        item(
                            18, 30, TripItemKind.Activity, "Omoide Yokocho dinner", notes = "Nishi-Shinjuku 1-chome",
                            tags = listOf("Reserved"), image = "https://example.com/yokocho.jpg",
                        ),
                    ),
                ),
                ItineraryDay(
                    date = "2026-11-02", dayNumber = 2, lodging = listOf(stay("Park Hyatt Tokyo", LodgingPhase.Staying)),
                    entries = listOf(item(9, 30, TripItemKind.Activity, "Tsukiji breakfast", notes = "Outer market")),
                ),
                ItineraryDay(
                    date = "2026-11-03", dayNumber = 3,
                    lodging = listOf(stay("Hoshinoya Kyoto", LodgingPhase.CheckIn), stay("Park Hyatt Tokyo", LodgingPhase.CheckOut)),
                    entries = listOf(item(11, 0, TripItemKind.Transit, "Shinkansen to Kyoto", notes = "Nozomi 221")),
                ),
                ItineraryDay(
                    date = "2026-11-04", dayNumber = 4, lodging = listOf(stay("Hoshinoya Kyoto", LodgingPhase.CheckOut)),
                    entries = emptyList(),
                ),
                ItineraryDay(date = "2026-11-05", dayNumber = 5, lodging = emptyList(), entries = emptyList()),
                ItineraryDay(
                    date = "2026-11-06", dayNumber = 6, lodging = listOf(stay("Koyasan Onsen Fukuchiin", LodgingPhase.CheckIn)),
                    entries = emptyList(),
                ),
                ItineraryDay(
                    date = "2026-11-07", dayNumber = 7, lodging = listOf(stay("Koyasan Onsen Fukuchiin", LodgingPhase.CheckOut)),
                    entries = emptyList(),
                ),
            )
        }
    }
}

/**
 * Reads a day as the server sends it, with its entries under `items`, re-sorting them
 * for display; see `ItineraryDay.ordered`.
 */
internal object ItineraryDaySerializer : KSerializer<ItineraryDay> {
    @Serializable
    private class Payload(
        val date: String,
        val dayNumber: Int,
        val lodging: List<LodgingStay>,
        @SerialName("items") val entries: List<ItineraryEntry>,
        val isGap: Boolean = false,
        val tripId: String? = null,
    )

    override val descriptor: SerialDescriptor = Payload.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ItineraryDay) {
        val payload = Payload(value.date, value.dayNumber, value.lodging, value.entries, value.isGap, value.tripId)
        encoder.encodeSerializableValue(Payload.serializer(), payload)
    }

    override fun deserialize(decoder: Decoder): ItineraryDay {
        val payload = decoder.decodeSerializableValue(Payload.serializer())
        return ItineraryDay(
            date = payload.date,
            dayNumber = payload.dayNumber,
            lodging = payload.lodging,
            entries = ItineraryDay.ordered(payload.entries),
            isGap = payload.isGap,
            tripId = payload.tripId,
        )
    }
}

/**
 * Which spot of an item an itinerary entry stands for: `Departure`/`Arrival` (and a
 * `Layover` between legs) for transit, `CheckIn`/`CheckOut` for lodging.
 */
@Serializable
enum class ItineraryPhase(val rawValue: String) {
    @SerialName("departure")
    Departure("departure"),

    @SerialName("arrival")
    Arrival("arrival"),

    @SerialName("layover")
    Layover("layover"),

    @SerialName("check_in")
    CheckIn("check_in"),

    @SerialName("check_out")
    CheckOut("check_out");

    val label: String
        get() = when (this) {
            Departure -> "Departure"
            Arrival -> "Arrival"
            Layover -> "Layover"
            CheckIn -> "Check-in"
            CheckOut -> "Check-out"
        }
}

/**
 * `ItineraryEntry`: one card in a day's timeline. The server derives these: an item
 * with distinct ends gets one entry per end (`phase`), and a multi-leg transit also
 * gets a `Layover` entry per stop between legs, carrying where and how long. Every
 * entry of an item shares `item.id`; the item is there for reference. `phase` is null
 * for items with a single spot.
 */
@Serializable(with = ItineraryEntrySerializer::class)
data class ItineraryEntry(
    val item: TripItem,
    val phase: ItineraryPhase? = null,
    /** The place waited at, for a `Layover` entry. */
    val layoverPlace: Place? = null,
    /** How long the layover lasts, in minutes, for a `Layover` entry. */
    val layoverMinutes: Int? = null,
    /** The stop's zone, for a `Layover` entry. */
    val layoverTimezone: ZoneId? = null,
) {
    /**
     * Unique per entry: an item's entries share `item.id`, and a transit can have
     * several layovers.
     */
    val id: String get() = "${item.id}|${phase?.rawValue ?: ""}|${layoverPlace?.id ?: ""}"

    private val isEnd: Boolean get() = phase == ItineraryPhase.Arrival || phase == ItineraryPhase.CheckOut

    /**
     * The place this entry is at: the destination for an arrival, the stop for a
     * layover, the lodging itself at check-out (its end place, if any), else the
     * item's place.
     */
    val place: Place?
        get() = when (phase) {
            ItineraryPhase.Arrival -> item.endPlace
            ItineraryPhase.Layover -> layoverPlace
            ItineraryPhase.CheckOut -> item.endPlace ?: item.place
            else -> item.place
        }

    /**
     * For a layover, the leg that lands at the stop. The entry carries no time of its
     * own, so the stop's arrival (and the onward departure) are read off the legs.
     */
    val layoverInbound: TransitSegment?
        get() {
            if (phase != ItineraryPhase.Layover) return null
            val stop = layoverPlace?.id ?: return null
            return item.segments.dropLast(1).firstOrNull { it.endPlace?.id == stop }
        }

    /** For a layover, the leg that leaves the stop. */
    val layoverOutbound: TransitSegment?
        get() {
            val inbound = layoverInbound ?: return null
            return item.segments.getOrNull(item.segments.indexOf(inbound) + 1)
        }

    /**
     * The zone this entry's wall clock is read in: the stop's for a layover, the end's
     * for an end phase (a check-out falling back to the stay's start zone), else the
     * start's. Null for a zoneless item.
     */
    val timeZone: ZoneId?
        get() = when (phase) {
            ItineraryPhase.Layover -> layoverTimezone ?: layoverInbound?.endTimezone
            ItineraryPhase.Arrival -> item.endTimezone
            ItineraryPhase.CheckOut -> item.endTimezone ?: item.startTimezone
            else -> item.startTimezone
        }

    /**
     * Where this entry falls along its item: the departure, then each layover by the
     * leg landing there, then the arrival.
     */
    val travelOrder: Int
        get() = when (phase) {
            ItineraryPhase.Departure, ItineraryPhase.CheckIn, null -> 0
            ItineraryPhase.Layover -> 1 + (layoverInbound?.let { item.segments.indexOf(it) } ?: 0)
            ItineraryPhase.Arrival, ItineraryPhase.CheckOut -> Int.MAX_VALUE
        }

    /**
     * The leg this entry is about, for its carrier/flight label: the first leg at a
     * departure, the last at an arrival, the onward leg at a layover.
     */
    val segment: TransitSegment?
        get() = when (phase) {
            ItineraryPhase.Departure -> item.segments.firstOrNull()
            ItineraryPhase.Arrival -> item.segments.lastOrNull()
            ItineraryPhase.Layover -> layoverOutbound
            else -> null
        }

    /**
     * The wall clock this entry is shown (and sorted) at: the landing at the stop for
     * a layover, the end for an end phase, else the start.
     */
    val displayLocal: Instant get() = moment.first

    /** The true moment of `displayLocal`, for tie-breaking (UTC for zoneless items). */
    val displayInstant: Instant get() = moment.second

    /** The wall clock and its true moment. */
    private val moment: Pair<Instant, Instant>
        get() {
            // The span whose end this entry sits at, if any: the leg landing at a layover,
            // or the item itself for an end phase.
            val endOf: WallClockSpan? = layoverInbound ?: if (isEnd) item else null
            val end = endOf?.endsAtLocal
            val instant = endOf?.endInstant(ZoneOffset.UTC)
            if (end != null && instant != null) return end to instant
            return item.startsAtLocal to item.startInstant(ZoneOffset.UTC)
        }

    /** The entry's wall clock as written, e.g. "4:00 PM". */
    fun timeText(style: DateTimeFormatter): String = WallClock.text(displayLocal, style)

    /** When the onward leg leaves a layover, e.g. "11:40 AM"; null otherwise. */
    fun layoverDepartureText(style: DateTimeFormatter): String? = layoverOutbound?.startText(style)
}

/**
 * Reads an entry as the server sends it. Also accepts the older flat shape (a bare
 * `TripItemRead`), so the app keeps working against a server that hasn't shipped the
 * wrapper.
 */
internal object ItineraryEntrySerializer : KSerializer<ItineraryEntry> {
    @Serializable
    private class Payload(
        val item: TripItem,
        val phase: ItineraryPhase? = null,
        val layoverPlace: Place? = null,
        val layoverMinutes: Int? = null,
        @Serializable(with = TimeZoneSerializer::class) val layoverTimezone: ZoneId? = null,
    )

    override val descriptor: SerialDescriptor = Payload.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ItineraryEntry) {
        val payload = Payload(value.item, value.phase, value.layoverPlace, value.layoverMinutes, value.layoverTimezone)
        encoder.encodeSerializableValue(Payload.serializer(), payload)
    }

    override fun deserialize(decoder: Decoder): ItineraryEntry {
        val input = decoder as? JsonDecoder ?: throw SerializationException("An itinerary entry is only read from JSON")
        val element = input.decodeJsonElement()
        if (element !is JsonObject || "item" !in element) {
            return ItineraryEntry(item = input.json.decodeFromJsonElement(serializer<TripItem>(), element))
        }
        val payload = input.json.decodeFromJsonElement(Payload.serializer(), element)
        return ItineraryEntry(
            item = payload.item,
            phase = payload.phase,
            layoverPlace = payload.layoverPlace,
            layoverMinutes = payload.layoverMinutes,
            layoverTimezone = payload.layoverTimezone,
        )
    }
}

/** A lodging item as it applies to one day (for the bar at the top of the day). */
@Serializable
data class LodgingStay(val item: TripItem, val phase: LodgingPhase)

@Serializable
enum class LodgingPhase(val rawValue: String) {
    @SerialName("check_in")
    CheckIn("check_in"),

    @SerialName("staying")
    Staying("staying"),

    @SerialName("check_out")
    CheckOut("check_out");

    val label: String
        get() = when (this) {
            CheckIn -> "Check-in"
            Staying -> "Staying"
            CheckOut -> "Check-out"
        }

    /**
     * Where the phase falls in a day, for ordering a changeover day's stays: leaving
     * one before arriving at the next.
     */
    val dayOrder: Int
        get() = when (this) {
            CheckOut -> 0
            Staying -> 1
            CheckIn -> 2
        }
}

fun TripsAPI.itinerary(tripId: String) = Endpoint(path = "/trips/$tripId/itinerary", cachesResponse = true)
