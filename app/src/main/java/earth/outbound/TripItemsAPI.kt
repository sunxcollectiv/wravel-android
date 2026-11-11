//
//  TripItemsAPI.kt
//  outbound
//

@file:UseSerializers(InstantSerializer::class)

package earth.outbound

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apartment
import androidx.compose.material.icons.rounded.Bed
import androidx.compose.material.icons.rounded.DirectionsBoat
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tram
import androidx.compose.ui.graphics.vector.ImageVector
import earth.outbound.auth.User
import earth.outbound.networking.Endpoint
import earth.outbound.networking.HTTPMethod
import earth.outbound.networking.InstantSerializer
import earth.outbound.networking.jsonBody
import earth.outbound.networking.orJsonNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Transient
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** The kind of a trip item, matching the backend's `TripItemKind` enum. */
@Serializable
enum class TripItemKind(val rawValue: String) {
    @SerialName("lodging") Lodging("lodging"),
    @SerialName("activity") Activity("activity"),
    @SerialName("transit") Transit("transit");

    val id: String get() = rawValue
    val label: String get() = rawValue.capitalized

    companion object {
        fun fromRawValue(raw: String): TripItemKind? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * How a transit moves, matching the server's `TransitMode`. `Mixed` only comes
 * back from the server, for a booking whose legs differ; it's never sent.
 */
@Serializable
enum class TransitMode(val rawValue: String) {
    @SerialName("flight") Flight("flight"),
    @SerialName("train") Train("train"),
    @SerialName("bus") Bus("bus"),
    @SerialName("ferry") Ferry("ferry"),
    @SerialName("car_rental") CarRental("car_rental"),
    @SerialName("other") Other("other"),
    @SerialName("mixed") Mixed("mixed");

    val id: String get() = rawValue

    val label: String
        get() = when (this) {
            Flight -> "Flight"
            Train -> "Train"
            Bus -> "Bus"
            Ferry -> "Ferry"
            CarRental -> "Car rental"
            Other -> "Other"
            Mixed -> "Mixed"
        }

    val systemImage: ImageVector
        get() = when (this) {
            Flight -> Icons.Rounded.Flight
            Train -> Icons.Rounded.Tram
            Bus -> Icons.Rounded.DirectionsBus
            Ferry -> Icons.Rounded.DirectionsBoat
            CarRental -> Icons.Rounded.DirectionsCar
            Other -> Icons.Rounded.MoreHoriz
            Mixed -> Icons.Rounded.SwapHoriz
        }

    companion object {
        /** The modes a person can pick. */
        val pickable: List<TransitMode> = listOf(Flight, Train, Bus, Ferry, CarRental, Other)

        fun fromRawValue(raw: String): TransitMode? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** What kind of place a stay is at, matching the server's `LodgingType`. */
@Serializable
enum class LodgingType(val rawValue: String) {
    @SerialName("hotel") Hotel("hotel"),
    @SerialName("vacation_rental") VacationRental("vacation_rental"),
    @SerialName("hostel") Hostel("hostel"),
    @SerialName("other") Other("other");

    val id: String get() = rawValue

    val label: String
        get() = when (this) {
            Hotel -> "Hotel"
            VacationRental -> "Vacation rental"
            Hostel -> "Hostel"
            Other -> "Other"
        }

    /** A shorter label for tight spots (the form's picker). */
    val shortLabel: String
        get() = when (this) {
            VacationRental -> "Rental"
            else -> label
        }

    val systemImage: ImageVector
        get() = when (this) {
            Hotel -> Icons.Rounded.Apartment
            VacationRental -> Icons.Rounded.Home
            Hostel -> Icons.Rounded.Bed
            Other -> Icons.Rounded.MoreHoriz
        }

    companion object {
        fun fromRawValue(raw: String): LodgingType? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * `LodgingRead` / `LodgingDetails`: a stay's details beyond its check-in/check-out
 * (the item's times), property (the item's place), people (its participants) and
 * rooms (the item's `rooms`). Every field is optional: an imported booking states
 * what it states, a hand-entered stay may have none.
 *
 * The server replaces these whole on a PATCH, so an edit sends every field back,
 * not just the ones the form shows.
 */
@Serializable(with = LodgingDetailsSerializer::class)
data class LodgingDetails(
    val propertyType: LodgingType? = null,
    /** The property's own number, as printed. */
    val phone: String? = null,
    /**
     * The last moment the stay can be cancelled free of charge: a wall clock at the
     * property (the stay's check-in zone), as a floating UTC date like the item's times.
     */
    val freeCancellationUntil: Instant? = null,
) {
    /** Whether nothing is known about the stay, so there's nothing to store. */
    val isEmpty: Boolean get() = this == LodgingDetails()
}

/** `LodgingDetails` as it goes over the wire, each key there or not, null or not. */
@Serializable
private class LodgingDetailsPayload(
    val propertyType: JsonElement? = null,
    val phone: JsonElement? = null,
    val freeCancellationUntil: JsonElement? = null,
)

private object LodgingDetailsSerializer : KSerializer<LodgingDetails> {
    override val descriptor = LodgingDetailsPayload.serializer().descriptor

    override fun deserialize(decoder: Decoder): LodgingDetails {
        val payload = decoder.decodeSerializableValue(LodgingDetailsPayload.serializer())
        return LodgingDetails(
            // Leniently, so a type added on the server doesn't break the whole item.
            propertyType = payload.propertyType.lenientString()?.let(LodgingType::fromRawValue),
            phone = when (val phone = payload.phone) {
                null, JsonNull -> null
                else -> phone.lenientString() ?: throw SerializationException("Expected a string for phone: $phone")
            },
            freeCancellationUntil = payload.freeCancellationUntil.lenientString()?.let(WallClock::parse),
        )
    }

    /**
     * Nil fields are sent as `null` (the server's defaults anyway), keeping the body
     * a faithful copy of the whole object it replaces. Only these keys: the server
     * rejects any others.
     */
    override fun serialize(encoder: Encoder, value: LodgingDetails) {
        encoder.encodeSerializableValue(
            LodgingDetailsPayload.serializer(),
            LodgingDetailsPayload(
                propertyType = value.propertyType?.rawValue.orJsonNull(),
                phone = value.phone.orJsonNull(),
                freeCancellationUntil = value.freeCancellationUntil?.let { WallClock.naiveString(it) }.orJsonNull(),
            ),
        )
    }
}

/**
 * `LodgingRoomRead`: one room of a stay, in the booking's order. Every detail is
 * optional; an imported booking may list rooms with only a type, or nothing at all.
 */
@Serializable
data class LodgingRoom(
    val id: String,
    val position: Int = 0,
    /** A name for the room, e.g. "Kids' room". */
    val label: String? = null,
    val roomNumber: String? = null,
    /** As the booking names it, e.g. "Deluxe King, City View". */
    val roomType: String? = null,
    val guests: Int? = null,
    /** The room's own reference, when the booking gives each room one. */
    val confirmationNumber: String? = null,
    /** Trip-member ids of who's in it (match against `participants[].member.id`). */
    val occupants: List<String> = emptyList(),
    /**
     * Added in the app and not on the server yet, so its `id` is only a local one
     * (never sent: the server gives a new room its own).
     */
    @Transient val isNew: Boolean = false,
) {
    /** Whether the room says nothing at all, so there's no point keeping it. */
    val isBlank: Boolean
        get() = listOf(label, roomNumber, roomType, confirmationNumber).all { nonBlank(it) == null } &&
            guests == null && occupants.isEmpty()

    /** The room with its text fields trimmed, and blank ones cleared to nil. */
    val tidied: LodgingRoom
        get() = copy(
            label = trimmed(label),
            roomNumber = trimmed(roomNumber),
            roomType = trimmed(roomType),
            confirmationNumber = trimmed(confirmationNumber),
        )

    /**
     * What to call the room: its label, else its number ("Room 412"), else its
     * place in the list ("Room 1", counting from `number`).
     */
    fun title(number: Int): String {
        nonBlank(label)?.let { return it }
        nonBlank(roomNumber)?.let { return "Room $it" }
        return "Room $number"
    }

    /**
     * The line beside or under the title: the room type, then how many guests,
     * e.g. "1 King Bed · 1 guest"; nil when the booking says neither.
     */
    val summary: String?
        get() {
            val parts = listOfNotNull(nonBlank(roomType), guests?.let { if (it == 1) "1 guest" else "$it guests" })
            return if (parts.isEmpty()) null else parts.joinToString(" · ")
        }

    companion object {
        /** A blank room to add, with a local id. */
        fun new(): LodgingRoom = LodgingRoom(id = UUID.randomUUID().toString(), isNew = true)

        private fun trimmed(text: String?): String? = nonBlank(text?.trim())

        private fun nonBlank(text: String?): String? = text?.takeIf { it.trimmingWhitespaces().isNotEmpty() }
    }
}

/**
 * The free-cancellation deadline as written at the property, with the stay's
 * zone when it has one, e.g. "Tue, Dec 29 at 11:59 PM GMT+7".
 */
fun LodgingDetails.cancellationText(zone: ZoneId?): String? {
    val until = freeCancellationUntil ?: return null
    val text = WallClock.text(until, WallClock.weekdayMonthDayHourMinute)
    if (zone == null) return text
    return "$text ${WallClock.zoneAbbreviation(zone, WallClock.instant(until, zone))}"
}

/**
 * Whether the free-cancellation deadline has gone by, read in the stay's zone
 * (the device's when it has none). False when there's no deadline.
 */
fun LodgingDetails.cancellationHasPassed(zone: ZoneId?, now: Instant = Instant.now()): Boolean {
    val until = freeCancellationUntil ?: return false
    return WallClock.instant(until, zone ?: ZoneId.systemDefault()) <= now
}

/**
 * Where an item came from, matching the server's `TripItemSource`. Fixed when the
 * item is made: an imported item someone edits is still an import (see `editedAt`).
 */
@Serializable
enum class TripItemSource(val rawValue: String) {
    /** Entered by hand. */
    @SerialName("manual") Manual("manual"),

    /** Imported from a forwarded confirmation email. */
    @SerialName("email") Email("email"),

    /** Imported from an uploaded screenshot or photo. */
    @SerialName("image") Image("image");

    /** Whether it was read from a booking rather than typed in. */
    val isImport: Boolean get() = this != Manual

    /** How the details sheet says where an import came from; nil for one entered by hand. */
    val importLabel: String?
        get() = when (this) {
            Manual -> null
            Email -> "Imported from email"
            Image -> "Imported from a screenshot"
        }

    val systemImage: ImageVector
        get() = when (this) {
            Manual -> Icons.Rounded.EditNote
            Email -> Icons.Rounded.Email
            Image -> Icons.Rounded.Photo
        }

    companion object {
        fun fromRawValue(raw: String): TripItemSource? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * What people on the trip who aren't participants see of an item, matching
 * `TripItemVisibility`. Participants always see all of it.
 */
@Serializable
enum class TripItemVisibility(val rawValue: String) {
    /** Everything. */
    @SerialName("open") Open("open"),

    /** That something is on, and when: its kind, title and times. */
    @SerialName("closed") Closed("closed"),

    /** Nothing: the item doesn't exist for them. */
    @SerialName("private") Private("private");

    val id: String get() = rawValue
    val label: String get() = rawValue.capitalized

    /** What this means for people on the trip who aren't going, for the item form. */
    val explanation: String
        get() = when (this) {
            Open -> "Everyone on the trip can see all the details."
            Closed -> "Others see only the title and time."
            Private -> "Hidden from everyone who isn't going."
        }

    companion object {
        fun fromRawValue(raw: String): TripItemVisibility? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * `TripItemRead` from the backend. `place`/`endPlace` are the associated places
 * (end place is used by transit for the destination).
 *
 * Times are local wall time + an optional IANA zone, one zone per end of the item;
 * see `WallClockSpan` for how to display and order them.
 */
@Serializable(with = TripItemSerializer::class)
data class TripItem(
    val id: String,
    val tripId: String,
    val kind: TripItemKind,
    val visibility: TripItemVisibility = TripItemVisibility.Open,
    /**
     * True when the caller isn't a participant on a closed item: only its kind, title
     * and times come through, and the other details are blanked.
     */
    val redacted: Boolean = false,
    /** As written; nil when the item goes by its place (a lodging can). */
    val title: String? = null,
    /**
     * What to call the item: its title, else its place's name, else its kind. The
     * server works it out, and leaves out the place on a redacted item.
     */
    val displayTitle: String,
    val notes: String? = null,
    val tags: List<String>,
    override val startsAtLocal: Instant,
    override val startsAtUtc: Instant? = null,
    override val startTimezone: ZoneId? = null,
    override val endsAtLocal: Instant? = null,
    override val endsAtUtc: Instant? = null,
    override val endTimezone: ZoneId? = null,
    val imageUrl: String? = null,
    val place: Place? = null,
    val endPlace: Place? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    /**
     * The legs of a transit booking, in travel order (one for a direct trip). Empty
     * for other kinds.
     */
    val segments: List<TransitSegment> = emptyList(),
    /** Who added the item; nil once they've left the trip. */
    val createdBy: User? = null,
    /** Who the item is for, each with an optional RSVP-style answer. */
    val participants: List<TripItemParticipant> = emptyList(),
    /** How a transit moves; nil when unknown or for other kinds. */
    val transitMode: TransitMode? = null,
    /** A stay's details; nil for other kinds, a stay with none, and a redacted one. */
    val lodging: LodgingDetails? = null,
    /** A stay's rooms, in order; empty for other kinds and a stay that lists none. */
    val rooms: List<LodgingRoom> = emptyList(),
    /** Where it came from; nil from a server that doesn't say. */
    val source: TripItemSource? = null,
    /**
     * Whether the original it was imported from can still be fetched (by its
     * participants, from `GET …/items/{id}/source`).
     */
    val hasSource: Boolean = false,
    /**
     * The last time someone changed the booking itself (not who's on it, its
     * visibility or notes), and who. On an import, set means it no longer matches
     * exactly what the booking said.
     */
    val editedAt: Instant? = null,
    val editedBy: User? = null,
) : WallClockSpan {
    /**
     * What sort of item this is, for badges: a stay's property type when known
     * ("Hotel"), else its kind ("Lodging").
     */
    val typeLabel: String
        get() = if (kind == TripItemKind.Lodging) lodging?.propertyType?.label ?: kind.label else kind.label

    companion object {
        val sample: TripItem by lazy {
            TripItem(
                id = "sample-item",
                tripId = "sample",
                kind = TripItemKind.Activity,
                title = "Land at HND",
                displayTitle = "Land at HND",
                notes = "Terminal 3",
                tags = emptyList(),
                startsAtLocal = Instant.now(),
                startsAtUtc = null,
                startTimezone = null,
                endsAtLocal = null,
                endsAtUtc = null,
                endTimezone = null,
                imageUrl = null,
                place = null,
                endPlace = null,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        }
    }
}

@Serializable
enum class RsvpStatus(val rawValue: String) {
    @SerialName("going") Going("going"),
    @SerialName("maybe") Maybe("maybe"),
    @SerialName("declined") Declined("declined"),
}

/** `ParticipantRead`: one trip member an item is for, and their answer if any. */
@Serializable
data class TripItemParticipant(
    val member: TripMember,
    val status: RsvpStatus? = null,
)

/**
 * `TransitSegmentRead`: one leg of a transit booking, e.g. JFK → LAX on a two-leg
 * flight. Times follow the same wall-clock rules as `TripItem`.
 */
@Serializable(with = TransitSegmentSerializer::class)
data class TransitSegment(
    val id: String,
    val position: Int,
    override val startsAtLocal: Instant,
    override val startsAtUtc: Instant? = null,
    override val startTimezone: ZoneId? = null,
    override val endsAtLocal: Instant? = null,
    override val endsAtUtc: Instant? = null,
    override val endTimezone: ZoneId? = null,
    val place: Place? = null,
    val endPlace: Place? = null,
    val carrier: String? = null,
    val flightNumber: String? = null,
    val transitMode: TransitMode? = null,
    val confirmationNumber: String? = null,
) : WallClockSpan {
    /**
     * The leg's label from its carrier and flight number, e.g. "Qantas QF 12"; nil
     * when it has neither.
     */
    val label: String?
        get() {
            val parts = listOfNotNull(carrier, flightNumber).filter { it.isNotEmpty() }
            return if (parts.isEmpty()) null else parts.joinToString(" ")
        }
}

/** `TransitSegment` as the server sends it. */
@Serializable
private class TransitSegmentPayload(
    val id: String,
    val position: Int,
    @Serializable(with = WallClockSerializer::class) val startsAt: Instant,
    val startsAtUtc: Instant? = null,
    @Serializable(with = TimeZoneSerializer::class) val startTimezone: ZoneId? = null,
    @Serializable(with = WallClockSerializer::class) val endsAt: Instant? = null,
    val endsAtUtc: Instant? = null,
    @Serializable(with = TimeZoneSerializer::class) val endTimezone: ZoneId? = null,
    val place: Place? = null,
    val endPlace: Place? = null,
    val carrier: String? = null,
    val flightNumber: String? = null,
    val transitMode: JsonElement? = null,
    val confirmationNumber: String? = null,
)

private object TransitSegmentSerializer : KSerializer<TransitSegment> {
    override val descriptor = TransitSegmentPayload.serializer().descriptor

    override fun deserialize(decoder: Decoder): TransitSegment {
        val payload = decoder.decodeSerializableValue(TransitSegmentPayload.serializer())
        return TransitSegment(
            id = payload.id,
            position = payload.position,
            startsAtLocal = payload.startsAt,
            startsAtUtc = payload.startsAtUtc,
            startTimezone = payload.startTimezone,
            endsAtLocal = payload.endsAt,
            endsAtUtc = payload.endsAtUtc,
            endTimezone = payload.endTimezone,
            place = payload.place,
            endPlace = payload.endPlace,
            carrier = payload.carrier,
            flightNumber = payload.flightNumber,
            transitMode = payload.transitMode.lenientString()?.let(TransitMode::fromRawValue),
            confirmationNumber = payload.confirmationNumber,
        )
    }

    override fun serialize(encoder: Encoder, value: TransitSegment) =
        throw SerializationException("TransitSegment is only read from the server.")
}

/** `TripItem` as the server sends it. */
@Serializable
private class TripItemPayload(
    val id: String,
    val tripId: String,
    val kind: TripItemKind,
    val visibility: String? = null,
    val redacted: Boolean = false,
    val title: String? = null,
    val displayTitle: String? = null,
    val notes: String? = null,
    val tags: List<String> = emptyList(),
    @Serializable(with = TimeZoneSerializer::class) val startTimezone: ZoneId? = null,
    @Serializable(with = TimeZoneSerializer::class) val endTimezone: ZoneId? = null,
    @Serializable(with = WallClockSerializer::class) val startsAt: Instant,
    @Serializable(with = WallClockSerializer::class) val endsAt: Instant? = null,
    val startsAtUtc: Instant? = null,
    val endsAtUtc: Instant? = null,
    val imageUrl: String? = null,
    val place: Place? = null,
    val endPlace: Place? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val segments: List<TransitSegment> = emptyList(),
    val createdBy: User? = null,
    val participants: List<TripItemParticipant> = emptyList(),
    val transitMode: JsonElement? = null,
    val lodging: JsonElement? = null,
    val rooms: JsonElement? = null,
    val source: JsonElement? = null,
    val hasSource: Boolean = false,
    val editedAt: Instant? = null,
    val editedBy: User? = null,
)

private object TripItemSerializer : KSerializer<TripItem> {
    override val descriptor = TripItemPayload.serializer().descriptor

    override fun deserialize(decoder: Decoder): TripItem {
        val json = (decoder as? JsonDecoder)?.json ?: throw SerializationException("TripItem is only read from JSON.")
        val payload = decoder.decodeSerializableValue(TripItemPayload.serializer())
        return TripItem(
            id = payload.id,
            tripId = payload.tripId,
            kind = payload.kind,
            visibility = payload.visibility?.let {
                TripItemVisibility.fromRawValue(it) ?: throw SerializationException("Unrecognized visibility: $it")
            } ?: TripItemVisibility.Open,
            redacted = payload.redacted,
            title = payload.title,
            notes = payload.notes,
            tags = payload.tags,
            startsAtLocal = payload.startsAt,
            startsAtUtc = payload.startsAtUtc,
            startTimezone = payload.startTimezone,
            endsAtLocal = payload.endsAt,
            endsAtUtc = payload.endsAtUtc,
            endTimezone = payload.endTimezone,
            imageUrl = payload.imageUrl,
            place = payload.place,
            endPlace = payload.endPlace,
            createdAt = payload.createdAt,
            updatedAt = payload.updatedAt,
            segments = payload.segments.sortedBy { it.position },
            // Leniently, so a source added on the server doesn't break the whole item.
            source = payload.source.lenientString()?.let(TripItemSource::fromRawValue),
            hasSource = payload.hasSource,
            editedAt = payload.editedAt,
            editedBy = payload.editedBy,
            createdBy = payload.createdBy,
            participants = payload.participants,
            // Leniently, so a mode added on the server doesn't break the whole item.
            transitMode = payload.transitMode.lenientString()?.let(TransitMode::fromRawValue),
            // Leniently too: a malformed details object shouldn't hide the stay.
            lodging = json.lenient(LodgingDetails.serializer(), payload.lodging),
            rooms = (json.lenient(ListSerializer(LodgingRoom.serializer()), payload.rooms) ?: emptyList())
                .sortedBy { it.position },
            displayTitle = payload.displayTitle ?: payload.title ?: payload.place?.name ?: payload.kind.label,
        )
    }

    override fun serialize(encoder: Encoder, value: TripItem) =
        throw SerializationException("TripItem is only read from the server.")
}

/**
 * Request shapes for a trip's items.
 *
 *     GET   /trips/{id}/items                 -> [TripItemRead]
 *     POST  /trips/{id}/items  TripItemCreate -> TripItemRead (201)
 *     PATCH /trips/{id}/items/{itemId} TripItemUpdate -> TripItemRead
 */
object TripItemsAPI {
    /**
     * `TripItemCreate`: body for `POST /trips/{id}/items`. Times are naive ISO 8601
     * wall clocks (no offset or `Z`) paired with an optional IANA zone; a nil zone
     * makes a zoneless item. `placeId`/`endPlaceId` are internal place UUIDs (from
     * `POST /places`); nil values are omitted from the JSON. A lodging may go
     * without a title when it has a place.
     */
    @Serializable
    data class CreateRequest(
        val kind: TripItemKind,
        val visibility: TripItemVisibility? = null,
        val title: String?,
        val notes: String?,
        /**
         * The item's own start; nil for a transit sent as `segments`, whose span the
         * server takes from its legs.
         */
        val startsAt: String?,
        val startTimezone: String?,
        val endsAt: String?,
        val endTimezone: String?,
        val placeId: String?,
        val endPlaceId: String?,
        /** Transit only; omitted when nil. Never `mixed`, which the server derives. */
        val transitMode: TransitMode? = null,
        /**
         * A transit's legs, in order. When set, the item's own times, places and mode
         * are left out: the server takes them from the legs.
         */
        val segments: List<SegmentRequest>? = null,
        /** Lodging only: the stay's details. Omitted when nil. */
        val lodging: LodgingDetails? = null,
        /** Lodging only: its rooms, in order, with who's in each. Omitted when nil. */
        val rooms: List<RoomRequest>? = null,
        /**
         * Exactly who the item is for; nobody is implied. Omitted when nil, which makes
         * the item just for its creator.
         */
        val participants: List<ParticipantRequest>? = null,
        /** Tell the trip's other members about the new item. Omitted unless true. */
        val notifyMembers: Boolean? = null,
    )

    /**
     * `TransitSegmentCreate`: one leg of a transit, with its own times, zones,
     * places and mode; nil values are omitted. Every leg is sent on each save (they
     * replace the old ones), so the booking details are carried along too.
     */
    @Serializable
    data class SegmentRequest(
        val startsAt: String,
        val startTimezone: String?,
        val endsAt: String?,
        val endTimezone: String?,
        val placeId: String?,
        val endPlaceId: String?,
        val transitMode: TransitMode?,
        val carrier: String? = null,
        val flightNumber: String? = null,
        val confirmationNumber: String? = null,
    )

    /**
     * `LodgingRoomSet`: one room of a stay, as sent. `id` is only for a room the
     * server already has; a new one goes without. Nil fields are omitted, which the
     * server takes as empty.
     */
    @Serializable
    data class RoomRequest(
        val id: String? = null,
        val label: String? = null,
        val roomNumber: String? = null,
        val roomType: String? = null,
        val guests: Int? = null,
        val confirmationNumber: String? = null,
        /** Trip-member ids of who's in it. Anyone not on the stay yet is added to it. */
        val occupants: List<String> = emptyList(),
    ) {
        /** A room as sent, leaving out the local id of one added in the app. */
        constructor(room: LodgingRoom) : this(
            id = if (room.isNew) null else room.id,
            label = room.label,
            roomNumber = room.roomNumber,
            roomType = room.roomType,
            guests = room.guests,
            confirmationNumber = room.confirmationNumber,
            occupants = room.occupants,
        )
    }

    /**
     * `ParticipantSet`: one member an item is for, by their trip-member id. A nil
     * `status` is omitted, keeping whatever answer they already had.
     */
    @Serializable
    data class ParticipantRequest(
        val memberId: String,
        val status: RsvpStatus? = null,
    )

    /**
     * `TripItemUpdate`: body for `PATCH /trips/{id}/items/{itemId}`. Every field is
     * optional server-side. Nil place ids are omitted, leaving existing places
     * untouched. A nil `endsAt` is sent as an explicit `null` to clear the end time;
     * the server drops the end zone with it. `participants` is omitted when nil; when
     * sent it replaces who the item is for (listed members keep their answers).
     * A nil title is omitted too, unless `nullsTitleAndPlace`.
     */
    @Serializable(with = UpdateRequestSerializer::class)
    data class UpdateRequest(
        val kind: TripItemKind,
        val visibility: TripItemVisibility? = null,
        val title: String?,
        val notes: String?,
        /**
         * The item's own start; nil for a transit sent as `segments`, whose span the
         * server takes from its legs.
         */
        val startsAt: String?,
        val startTimezone: String?,
        val endsAt: String?,
        val endTimezone: String?,
        val placeId: String?,
        val endPlaceId: String?,
        /** Omitted when nil, keeping the current mode. */
        val transitMode: TransitMode? = null,
        /** Replaces a transit's legs; see `CreateRequest.segments`. */
        val segments: List<SegmentRequest>? = null,
        /** Replaces a stay's details whole when sent (only when `sendsLodging`). */
        val lodging: LodgingDetails? = null,
        /**
         * Whether to send `lodging` at all: false leaves the stored details as they
         * are; true sends it, a nil one as `null`, clearing them.
         */
        val sendsLodging: Boolean = false,
        /**
         * Replaces a stay's rooms when sent (only when `sendsRooms`): rooms with an
         * `id` are kept and updated, new ones made, any left out dropped, and an
         * empty list clears them. Left out, the stored rooms stay as they are.
         */
        val rooms: List<RoomRequest>? = null,
        val sendsRooms: Boolean = false,
        val participants: List<ParticipantRequest>? = null,
        /** Tell the trip's other members what changed. Omitted unless true. */
        val notifyMembers: Boolean? = null,
        /**
         * Send a nil title or place id as an explicit `null`, clearing it, rather than
         * omitting it. For kinds that go by a title or a place (a lodging, an
         * activity), so removing either one clears it.
         */
        val nullsTitleAndPlace: Boolean = false,
    ) {
        /**
         * An update carrying the same fields as `create`, so a form can build its body
         * once for both.
         */
        constructor(
            create: CreateRequest,
            nullsTitleAndPlace: Boolean = false,
            sendsLodging: Boolean = false,
            sendsRooms: Boolean = false,
        ) : this(
            kind = create.kind,
            visibility = create.visibility,
            title = create.title,
            notes = create.notes,
            startsAt = create.startsAt,
            startTimezone = create.startTimezone,
            endsAt = create.endsAt,
            endTimezone = create.endTimezone,
            placeId = create.placeId,
            endPlaceId = create.endPlaceId,
            transitMode = create.transitMode,
            segments = create.segments,
            lodging = create.lodging,
            sendsLodging = sendsLodging,
            rooms = create.rooms,
            sendsRooms = sendsRooms,
            participants = create.participants,
            notifyMembers = create.notifyMembers,
            nullsTitleAndPlace = nullsTitleAndPlace,
        )
    }

    /**
     * `UpdateRequest` as it goes over the wire: a key left null is omitted, one set
     * to `JsonNull` is sent as an explicit `null`.
     */
    @Serializable
    private class UpdateRequestPayload(
        val kind: TripItemKind,
        val visibility: TripItemVisibility? = null,
        val title: JsonElement? = null,
        val notes: String? = null,
        val segments: List<SegmentRequest>? = null,
        val startsAt: String? = null,
        val startTimezone: String? = null,
        val endsAt: JsonElement? = null,
        val endTimezone: String? = null,
        val placeId: JsonElement? = null,
        val endPlaceId: String? = null,
        val transitMode: TransitMode? = null,
        val lodging: JsonElement? = null,
        val rooms: List<RoomRequest>? = null,
        val participants: List<ParticipantRequest>? = null,
        val notifyMembers: Boolean? = null,
    )

    private object UpdateRequestSerializer : KSerializer<UpdateRequest> {
        override val descriptor = UpdateRequestPayload.serializer().descriptor

        override fun serialize(encoder: Encoder, value: UpdateRequest) {
            val json = (encoder as? JsonEncoder)?.json ?: throw SerializationException("UpdateRequest is only sent as JSON.")
            // The server rejects the item's own span alongside its legs.
            val span = value.segments == null

            /** A nil `text` as `null` when `nullsTitleAndPlace`, else left out. */
            fun clearable(text: String?): JsonElement? =
                if (text != null || value.nullsTitleAndPlace) text.orJsonNull() else null

            encoder.encodeSerializableValue(
                UpdateRequestPayload.serializer(),
                UpdateRequestPayload(
                    kind = value.kind,
                    visibility = value.visibility,
                    title = clearable(value.title),
                    notes = value.notes,
                    segments = value.segments,
                    startsAt = if (span) value.startsAt else null,
                    startTimezone = if (span) value.startTimezone else null,
                    // Clear the end explicitly; `ends_at: null` also clears the end zone.
                    endsAt = if (span) value.endsAt.orJsonNull() else null,
                    endTimezone = if (span && value.endsAt != null) value.endTimezone else null,
                    placeId = if (span) clearable(value.placeId) else null,
                    endPlaceId = if (span) value.endPlaceId else null,
                    transitMode = if (span) value.transitMode else null,
                    lodging = if (value.sendsLodging) {
                        value.lodging?.let { json.encodeToJsonElement(LodgingDetails.serializer(), it) } ?: JsonNull
                    } else {
                        null
                    },
                    // An empty list, not null, clears them: always a list when sent.
                    rooms = if (value.sendsRooms) value.rooms ?: emptyList() else null,
                    participants = value.participants,
                    notifyMembers = value.notifyMembers,
                ),
            )
        }

        override fun deserialize(decoder: Decoder): UpdateRequest =
            throw SerializationException("UpdateRequest is only sent to the server.")
    }

    fun list(tripId: String): Endpoint =
        Endpoint(path = "/trips/$tripId/items")

    fun create(tripId: String, body: CreateRequest): Endpoint =
        Endpoint(path = "/trips/$tripId/items", method = HTTPMethod.POST, body = jsonBody(body))

    fun update(tripId: String, itemId: String, body: UpdateRequest): Endpoint =
        Endpoint(path = "/trips/$tripId/items/$itemId", method = HTTPMethod.PATCH, body = jsonBody(body))

    fun delete(tripId: String, itemId: String): Endpoint =
        Endpoint(path = "/trips/$tripId/items/$itemId", method = HTTPMethod.DELETE)
}

// MARK: - Decoding helpers

/** A raw value with its first letter capitalized, e.g. "Lodging". */
private val String.capitalized: String
    get() = replaceFirstChar { it.uppercase() }

/**
 * This string without the spaces and tabs around it (line breaks are kept), like
 * Swift's `trimmingCharacters(in: .whitespaces)`.
 */
internal fun String.trimmingWhitespaces(): String =
    trim { it == '\t' || it.category == CharCategory.SPACE_SEPARATOR }

/** The string this value holds; null when it's missing, `null` or not a string. */
private fun JsonElement?.lenientString(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** `element` decoded leniently: null when it's missing, `null` or malformed. */
private fun <T> Json.lenient(serializer: KSerializer<T>, element: JsonElement?): T? {
    if (element == null || element is JsonNull) return null
    return try {
        decodeFromJsonElement(serializer, element)
    } catch (_: IllegalArgumentException) {
        null
    }
}
