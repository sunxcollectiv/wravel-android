//
//  AreasAPI.kt
//  outbound
//

package earth.outbound

import earth.outbound.networking.Endpoint
import earth.outbound.networking.HTTPMethod
import earth.outbound.networking.jsonBody
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * `AreaRead` from the backend: a geographic area such as a country, city, region,
 * or neighborhood, e.g. "Kyoto".
 *
 * Only the fields the app currently uses are decoded; extra keys (parent id,
 * timestamps, …) are ignored.
 */
@Serializable
data class Area(
    val id: String,
    val name: String,
    /**
     * Free-form kind, e.g. "country", "city", "neighborhood". Not an enum on the
     * backend, so new kinds can appear without notice.
     */
    val kind: String? = null,
    /** The broader area this one sits in, e.g. the country a city is in. */
    val parent: AreaRef? = null,
    /**
     * The area's cover photo and its credit, kept on the area itself. Null until the
     * server has fetched one; a trip lists such areas in `areasMissingPhotos`. Kept
     * as strings so a malformed URL drops the photo rather than failing the decode.
     */
    val defaultPhotoUrl: String? = null,
    val defaultPhotoAttribution: String? = null,
    val defaultPhotoAttributionUrl: String? = null,
) {
    /** Context shown under the name in search results, e.g. "Japan" for Kyoto. */
    val subtitle: String? get() = parent?.name

    /** The cover photo, if the area has one. */
    val coverPhoto: AreaPhoto?
        get() {
            val url = defaultPhotoUrl?.takeIf(::isWebUrl) ?: return null
            return AreaPhoto(
                url = url,
                attribution = defaultPhotoAttribution,
                attributionUrl = defaultPhotoAttributionUrl?.takeIf(::isWebUrl),
            )
        }
}

/** Whether `string` is a well-formed web address, i.e. one a photo or a credit can link to. */
private fun isWebUrl(string: String): Boolean = string.toHttpUrlOrNull() != null

/**
 * An area's Unsplash cover photo. `url` hotlinks Unsplash's CDN, and Unsplash
 * requires crediting `attribution` (the photographer), linked to `attributionUrl`,
 * wherever the photo is shown.
 */
data class AreaPhoto(
    val url: String,
    val attribution: String?,
    val attributionUrl: String?,
)

/** `AreaRef`: a compact reference to another area, enough to label it. */
@Serializable
data class AreaRef(
    val id: String,
    val name: String,
    val kind: String? = null,
)

/**
 * Request shapes for the areas endpoints.
 *
 *     GET  /areas?q=&kind=&limit=&offset=                    -> [AreaRead]
 *     POST /areas/refresh-photos  AreaPhotosRefreshRequest   -> AreaPhotosRefreshResponse
 */
object AreasAPI {
    /** The most ids `POST /areas/refresh-photos` accepts in one request. */
    const val refreshPhotosBatchLimit = 100

    /** `AreaPhotosRefreshRequest`: the ids trips flagged in `areas_missing_photos`. */
    @Serializable
    data class RefreshPhotosRequest(val areaIds: List<String>)

    /**
     * `AreaPhotosRefreshResponse`: the requested areas keyed by id, after the
     * server's best-effort photo fetch. Unknown ids are left out.
     */
    @Serializable
    data class RefreshPhotosResponse(val areas: Map<String, Area>)

    /** Asks the server to fetch photos for up to `refreshPhotosBatchLimit` areas. */
    fun refreshPhotos(areaIds: List<String>) = Endpoint(
        path = "/areas/refresh-photos",
        method = HTTPMethod.POST,
        body = jsonBody(RefreshPhotosRequest(areaIds = areaIds)),
    )

    /**
     * Text-searches areas by name, best match first. `kind` restricts results to one
     * kind (e.g. "city").
     */
    fun search(query: String, kind: String? = null, limit: Int = 10): Endpoint {
        val items = mutableListOf<Pair<String, String?>>(
            "q" to query,
            "limit" to limit.toString(),
        )
        if (kind != null) items.add("kind" to kind)
        return Endpoint(path = "/areas", query = items)
    }
}
