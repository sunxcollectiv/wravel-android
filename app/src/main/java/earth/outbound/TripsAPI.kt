//
//  TripsAPI.kt
//  outbound
//

@file:UseSerializers(InstantSerializer::class)

package earth.outbound

import earth.outbound.auth.User
import earth.outbound.networking.Endpoint
import earth.outbound.networking.HTTPMethod
import earth.outbound.networking.InstantSerializer
import earth.outbound.networking.jsonBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonElement
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** `TripRead` from the backend. */
@Serializable
data class Trip(
    val id: String,
    val title: String,
    val description: String? = null,
    /**
     * Calendar dates in "yyyy-MM-dd" form (the API's `date` type, no time component),
     * so they're kept as strings rather than instants.
     */
    val startDate: String,
    val endDate: String,
    val coverImageUrl: String? = null,
    /**
     * The areas (countries/cities) this trip covers, in order. Optional so trips
     * still decode from a backend that doesn't send the field yet.
     */
    val areas: List<Area>? = null,
    /**
     * Ids of `areas` that have no photos yet. The trip is usable as is; the client
     * asks `POST /areas/refresh-photos` for these and patches the areas in.
     */
    val areasMissingPhotos: List<String>? = null,
    val ownerId: String? = null,
    /**
     * Everyone on the trip, owner included, in the order they were added. Optional so
     * trips still decode from a backend that doesn't send the field yet.
     */
    val members: List<TripMember>? = null,
    /**
     * Shared by the trips in one group: a trip and the segments members add before or
     * after it. Null for a trip on its own.
     */
    val groupId: String? = null,
    /**
     * The trip this one was added as a segment of; null for a trip that wasn't, or
     * once that trip is deleted (the group stays).
     */
    val extendsTripId: String? = null,
    /**
     * In the trip list only: the user's other trips in this one's group, by start date.
     * The list shows them as one entry, whose `startDate`/`endDate` then span them all.
     */
    val extensions: List<Trip>? = null,
    /**
     * The key of the pricing schedule the trip was created under, which it's priced by
     * for good (see `PricingStore`). Null from a server that doesn't send it yet.
     */
    val priceSchedule: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        /**
         * Today as a "yyyy-MM-dd" date in the device's time zone, for comparing with trip
         * dates (which sort correctly as strings).
         */
        fun isoDateString(date: Instant = Instant.now()): String =
            date.atZone(ZoneId.systemDefault()).toLocalDate().toString()

        /**
         * Splits trips into upcoming (including any in progress), soonest first, and past
         * (ended before `today`), most recent first.
         */
        fun upcomingAndPast(trips: List<Trip>, today: String = isoDateString()): UpcomingAndPast {
            val byDates = compareBy<Trip>({ it.startDate }, { it.endDate })
            return UpcomingAndPast(
                upcoming = trips.filter { it.endDate >= today }.sortedWith(byDates),
                past = trips.filter { it.endDate < today }.sortedWith(byDates.reversed()),
            )
        }

        val sample = Trip(
            id = "sample",
            title = "Tokyo",
            description = "Autumn trip through Shinjuku",
            startDate = "2026-11-01",
            endDate = "2026-11-05",
            coverImageUrl = null,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
    }
}

@Serializable
enum class TripRole(val rawValue: String) {
    /** Posts group items and manages members. */
    @SerialName("organizer")
    Organizer("organizer"),

    /** Adds their own items and RSVPs. */
    @SerialName("member")
    Member("member"),
}

/**
 * `TripMemberRead`: a person on the trip, by email. `user` is null until someone
 * with that email has an account.
 */
@Serializable
data class TripMember(
    val id: String,
    val email: String,
    val user: User? = null,
    val role: TripRole,
)

/** Parses the API's "yyyy-MM-dd" calendar dates; null for anything else. */
internal fun isoDate(string: String): LocalDate? =
    try {
        LocalDate.parse(string)
    } catch (_: DateTimeException) {
        null
    }

/** A human-readable date range, e.g. "Nov 1 – Nov 5, 2026". */
val Trip.dateRangeText: String
    get() {
        val start = isoDate(startDate)
        val end = isoDate(endDate)
        if (start == null || end == null) return "$startDate – $endDate"
        val startText = (if (start.year == end.year) WallClock.monthDay else WallClock.monthDayYear).format(start)
        return "$startText – ${WallClock.monthDayYear.format(end)}"
    }

/**
 * The trip's first and last days as midnight in the device zone, or null if either
 * date doesn't parse.
 */
val Trip.dateRange: ClosedRange<Instant>?
    get() {
        val zone = ZoneId.systemDefault()
        val start = isoDate(startDate)?.atStartOfDay(zone)?.toInstant() ?: return null
        val end = isoDate(endDate)?.atStartOfDay(zone)?.toInstant() ?: return null
        return start..maxOf(start, end)
    }

/** Trips split by `Trip.upcomingAndPast`. */
data class UpcomingAndPast(val upcoming: List<Trip>, val past: List<Trip>)

/**
 * Request shapes for the trips endpoints.
 *
 *     GET   /trips             -> [TripRead]
 *     POST  /trips  TripCreate -> TripRead (201)
 *     PATCH /trips/{id} TripUpdate -> TripRead
 */
object TripsAPI {
    /**
     * `TripCreate`: body for `POST /trips`. An optional `timezone` (IANA) seeds every
     * day of the trip with that zone, so items can be added without picking a zone each
     * time; it's sent on creation and left null for edits (PATCH doesn't take it).
     * The same shape is reused for edits via `update`.
     *
     * `areaIds` replaces the trip's whole set of areas. Null keys are omitted, so on
     * an edit leave it null to keep the existing areas untouched.
     *
     * `members` lists everyone besides the creator. On an edit it replaces the
     * roster, matched by email (the owner is always kept); leave it null to keep the
     * roster untouched.
     */
    @Serializable
    data class CreateRequest(
        val title: String,
        /**
         * Always sent (fill it with `orJsonNull()`), so an edit that empties the
         * description clears it rather than leaving the old one.
         */
        val description: JsonElement,
        val startDate: String,
        val endDate: String,
        val timezone: String? = null,
        val areaIds: List<String>? = null,
        val members: List<MemberRequest>? = null,
        /** PATCH only: tell the trip's other members what changed. */
        val notifyMembers: Boolean? = null,
        /**
         * POST only: the trip to group this one with, making it a segment in that trip's
         * group.
         */
        val extendsTripId: String? = null,
        /**
         * The charge a quote showed the user for this save. The server refuses the
         * save (409) if it would now charge something else.
         */
        val expectedPriceCents: Int? = null,
    )

    /** `TripMemberCreate`. A null `role` lets the server default to `member`. */
    @Serializable
    data class MemberRequest(val email: String, val role: TripRole? = null)

    val list = Endpoint(path = "/trips", cachesResponse = true)

    /** One trip with its own dates (the list's span a trip's whole group). */
    fun read(id: String) = Endpoint(path = "/trips/$id")

    fun create(body: CreateRequest) = Endpoint(path = "/trips", method = HTTPMethod.POST, body = jsonBody(body))

    fun update(id: String, body: CreateRequest) =
        Endpoint(path = "/trips/$id", method = HTTPMethod.PATCH, body = jsonBody(body))

    /**
     * What `create(body)` would charge, with the user's balance and any shortfall.
     * Nothing is saved.
     */
    fun quote(body: CreateRequest) = Endpoint(path = "/trips/quote", method = HTTPMethod.POST, body = jsonBody(body))

    /** What `update(id, body)` would charge (or refund). Organizers only. */
    fun quote(id: String, body: CreateRequest) =
        Endpoint(path = "/trips/$id/quote", method = HTTPMethod.POST, body = jsonBody(body))

    /** Owner only; deletes the trip with everything on it. 204. */
    fun delete(id: String) = Endpoint(path = "/trips/$id", method = HTTPMethod.DELETE)
}
