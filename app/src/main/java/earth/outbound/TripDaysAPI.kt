//
//  TripDaysAPI.kt
//  outbound
//

package earth.outbound

import earth.outbound.networking.Endpoint
import earth.outbound.networking.HTTPMethod
import earth.outbound.networking.jsonBody
import kotlinx.serialization.Serializable
import java.time.ZoneId

/**
 * `TripDayRead`: one row per calendar date of a trip. Created with the trip and kept
 * in sync with its dates. Its `timezones` seed the zone picker when adding items to a
 * day (the first entry is the default for new items).
 *
 * The itinerary endpoint doesn't carry these fields yet, so the client loads days
 * alongside the itinerary and joins on `date`.
 */
@Serializable
data class TripDay(
    val date: String,
    val dayNumber: Int,
    val name: String? = null,
    val timezones: List<String>,
    val place: Place? = null,
) {
    val id: String get() = date

    /** The day's zones as `ZoneId` values, dropping any the device can't resolve. */
    val timeZones: List<ZoneId>
        get() = timezones.mapNotNull(::timeZone)
}

/**
 * Request shapes for a trip's days.
 *
 *     GET   /trips/{id}/days                   -> [TripDayRead]
 *     PATCH /trips/{id}/days/{YYYY-MM-DD}       -> TripDayRead (organizers only)
 */
object TripDaysAPI {
    /**
     * `TripDayUpdate`: body for `PATCH /trips/{id}/days/{date}`. All fields optional;
     * `timezones` may be `[]` but not null. Only non-null fields are sent.
     */
    @Serializable
    data class UpdateRequest(
        val name: String? = null,
        val timezones: List<String>? = null,
        val placeId: String? = null,
    )

    fun list(tripId: String) = Endpoint(path = "/trips/$tripId/days")

    fun update(tripId: String, date: String, body: UpdateRequest) =
        Endpoint(path = "/trips/$tripId/days/$date", method = HTTPMethod.PATCH, body = jsonBody(body))
}
