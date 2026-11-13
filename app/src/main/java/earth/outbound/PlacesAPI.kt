//
//  PlacesAPI.kt
//  outbound
//

package earth.outbound

import earth.outbound.networking.Endpoint
import earth.outbound.networking.HTTPMethod
import earth.outbound.networking.jsonBody
import kotlinx.serialization.Serializable
import java.time.ZoneId

/**
 * `PlaceRead` from the backend: an internal place in our own database. Registered
 * from a Google place id via `POST /places`, which stores it and hands back this
 * record; the backend enriches it (address, coords, …) afterward. The `id` is what
 * trip items reference as `place_id` / `end_place_id`.
 *
 * Only the fields the app currently uses are decoded; extra keys (area, google_data,
 * timestamps, …) are ignored, and enriched fields may be null right after creation.
 */
@Serializable
data class Place(
    val id: String,
    /** Null right after creation: the backend enriches name/address/coords later. */
    val name: String? = null,
    val category: String? = null,
    /** A short code for the place, e.g. an airport's IATA code ("LAX"). */
    val code: String? = null,
    val address: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val timezone: String? = null,
    val photoUrl: String? = null,
    val googlePlaceId: String? = null,
    /**
     * The venue's cover photo, resolved from Google by the backend. Null when the place
     * has no usable photo.
     */
    val coverPhoto: CoverPhoto? = null,
) {
    /** The place's IANA timezone, if the backend has resolved one. */
    val timeZone: ZoneId?
        get() = timezone?.let { timeZone(it) }
}

/**
 * `CoverPhoto` on `PlaceRead`: a Google place photo the backend has already resolved.
 *
 * `url` embeds an API key and redirects to a short-lived CDN image, so it's for the
 * current render only: never persist it, rewrite it, or re-host the bytes. A fresh one
 * comes with the next fetch of the place. Google requires showing `attributions`
 * wherever the image appears.
 */
@Serializable
data class CoverPhoto(
    val url: String,
    /** A missing list reads as empty. */
    val attributions: List<Attribution> = emptyList(),
    /** Advisory only: the server owns refreshing the photo. */
    val isStale: Boolean? = null,
) {
    /**
     * A photographer credit; link the name to `uri` when present. Google can omit the
     * name, in which case there's nothing to show for this entry.
     */
    @Serializable
    data class Attribution(
        val displayName: String? = null,
        val uri: String? = null,
    )
}

/**
 * Request shapes for places.
 *
 *     POST /places          PlaceFromGoogle       -> PlaceRead (201)
 *     POST /places/refresh  PlacesRefreshRequest  -> PlacesRefreshResponse
 *     GET  /places/airports ?q=&limit=            -> [PlaceRead]
 */
object PlacesAPI {
    /**
     * `PlaceFromGoogle`: body for `POST /places`. The one thing the client supplies
     * to register a place is its Google place id; the backend fills in the rest.
     */
    @Serializable
    data class CreateRequest(val googlePlaceId: String)

    fun create(googlePlaceId: String) = Endpoint(
        path = "/places",
        method = HTTPMethod.POST,
        body = jsonBody(CreateRequest(googlePlaceId = googlePlaceId)),
    )

    /** The most ids `POST /places/refresh` accepts in one request. */
    const val refreshBatchLimit = 100

    /** `PlacesRefreshRequest`: the ids an itinerary flagged as stale. */
    @Serializable
    data class RefreshRequest(val placeIds: List<String>)

    /**
     * `PlacesRefreshResponse`: the current state of each requested place the server
     * knows, keyed by id, after a best-effort refresh from Google.
     */
    @Serializable
    data class RefreshResponse(val places: Map<String, Place>)

    /**
     * Refreshes up to `refreshBatchLimit` places. The server only re-fetches the ones
     * still due, so repeat calls (or two clients on one trip) don't double-bill.
     */
    fun refresh(placeIds: List<String>) = Endpoint(
        path = "/places/refresh",
        method = HTTPMethod.POST,
        body = jsonBody(RefreshRequest(placeIds = placeIds)),
    )

    /**
     * Airports in our own database whose IATA code or name matches `query`, best
     * matches first (exact code, then code prefix, then name). For the ends of a
     * flight, so they come from our curated list rather than a broad Google search.
     */
    fun searchAirports(query: String, limit: Int = 10) = Endpoint(
        path = "/places/airports",
        query = listOf("q" to query, "limit" to limit.toString()),
    )
}
