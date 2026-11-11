//
//  GooglePlacesAPI.kt
//  outbound
//

package earth.outbound.networking

import earth.outbound.OutboundApp
import earth.outbound.timeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.ZoneId

sealed class GooglePlacesError(message: String) : Exception(message) {
    class MissingAPIKey : GooglePlacesError("Place search isn't configured. Add a Google Places key to Secrets.json.")
}

/**
 * A single place suggestion returned by Google Places Autocomplete.
 *
 * `mainText` is the place's name (e.g. "Tokyo Tower") and `secondaryText` is the
 * supporting address line (e.g. "4 Chome-2-8 Shibakoen, Minato City, Tokyo"). The
 * `placeId` is kept so a later step can fetch full details (coordinates, etc.) when
 * locations are wired into trip items.
 */
data class PlaceSuggestion(
    val placeId: String,
    val mainText: String,
    val secondaryText: String?,
) {
    val id: String get() = placeId
}

/**
 * Rich details for a single place, fetched on demand from Google Place Details.
 * Everything is optional since any given place may not have, say, a phone or hours.
 */
data class PlaceDetails(
    val name: String?,
    val address: String?,
    /** Human-readable primary type, e.g. "Observation Deck". */
    val typeLabel: String?,
    /** Whether the place is open right now, if Google reports it. */
    val openNow: Boolean?,
    /** One line per weekday, e.g. "Monday: 9:00 AM – 11:00 PM". */
    val weekdayHours: List<String>,
    val phone: String?,
    val website: String?,
    val rating: Double?,
    val userRatingCount: Int?,
    val mapsURL: String?,
    val latitude: Double?,
    val longitude: Double?,
)

/**
 * Talks to the Google Places API (New) for place search.
 *
 * This is a separate service from the Outbound backend (a different host that
 * authenticates with an API key header rather than a bearer token), so it has its
 * own tiny client instead of going through `APIClient`. The new Places API returns
 * camelCase JSON, so no key-conversion strategy is needed.
 */
class GooglePlacesClient(private val session: OkHttpClient = APIClient.sharedSession) {
    /**
     * The API key, loaded from the gitignored `Secrets.json` bundled with the app as
     * an asset. See `Secrets.example.json` for setup. Restrict the key to this app's
     * package and the Places API in the Google Cloud console.
     */
    private val apiKey: String = loadAPIKey()
    private val endpoint = "https://places.googleapis.com/v1/places:autocomplete"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class AutocompleteRequest(val input: String)

    @Serializable
    private data class DetailsResponse(
        val displayName: LocalizedText? = null,
        val formattedAddress: String? = null,
        val primaryTypeDisplayName: LocalizedText? = null,
        val regularOpeningHours: OpeningHours? = null,
        val currentOpeningHours: CurrentHours? = null,
        val internationalPhoneNumber: String? = null,
        val websiteUri: String? = null,
        val rating: Double? = null,
        val userRatingCount: Int? = null,
        val googleMapsUri: String? = null,
        val location: Location? = null,
    ) {
        @Serializable
        data class LocalizedText(val text: String)

        @Serializable
        data class OpeningHours(val weekdayDescriptions: List<String>? = null)

        @Serializable
        data class CurrentHours(val openNow: Boolean? = null)

        @Serializable
        data class Location(val latitude: Double? = null, val longitude: Double? = null)
    }

    @Serializable
    private data class AutocompleteResponse(val suggestions: List<Suggestion>? = null) {
        @Serializable
        data class Suggestion(val placePrediction: PlacePrediction? = null)

        @Serializable
        data class PlacePrediction(
            val placeId: String,
            val structuredFormat: StructuredFormat? = null,
            val text: LocalizedText? = null,
        )

        @Serializable
        data class StructuredFormat(
            val mainText: LocalizedText? = null,
            val secondaryText: LocalizedText? = null,
        )

        @Serializable
        data class LocalizedText(val text: String)
    }

    @Serializable
    private data class TimeZoneResponse(val timeZone: Zone? = null) {
        @Serializable
        data class Zone(val id: String? = null)
    }

    /**
     * Returns place suggestions for the given free-text input.
     *
     * Returns an empty list for blank input. Throws on network or decoding failures.
     */
    suspend fun autocomplete(input: String): List<PlaceSuggestion> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return emptyList()

        if (apiKey.isEmpty()) throw GooglePlacesError.MissingAPIKey()

        val request = Request.Builder()
            .url(endpoint)
            .post(json.encodeToString(AutocompleteRequest(input = trimmed)).toRequestBody(jsonMediaType))
            .header("X-Goog-Api-Key", apiKey)
            .build()

        val response = json.decodeFromString<AutocompleteResponse>(data(request))

        return response.suggestions.orEmpty().mapNotNull { suggestion ->
            val prediction = suggestion.placePrediction ?: return@mapNotNull null
            // Prefer the structured name; fall back to the full text when it's missing.
            val main = prediction.structuredFormat?.mainText?.text ?: prediction.text?.text
                ?: return@mapNotNull null
            PlaceSuggestion(
                placeId = prediction.placeId,
                mainText = main,
                secondaryText = prediction.structuredFormat?.secondaryText?.text,
            )
        }
    }

    /**
     * Fetches just the IANA time zone for a Google place id (a cheap, single-field
     * request). Returns null if Google doesn't report one. Throws on network failures.
     */
    suspend fun placeTimeZone(placeId: String): ZoneId? {
        if (apiKey.isEmpty()) throw GooglePlacesError.MissingAPIKey()

        val response = json.decodeFromString<TimeZoneResponse>(data(placeRequest(placeId, fieldMask = "timeZone")))
        return response.timeZone?.id?.let { timeZone(it) }
    }

    /**
     * Fetches rich details (address, hours, phone, website, rating, …) for a Google
     * place id. Throws on network or decoding failures.
     */
    suspend fun placeDetails(placeId: String): PlaceDetails {
        if (apiKey.isEmpty()) throw GooglePlacesError.MissingAPIKey()

        val r = json.decodeFromString<DetailsResponse>(data(placeRequest(placeId, fieldMask = detailsFieldMask)))

        return PlaceDetails(
            name = r.displayName?.text,
            address = r.formattedAddress,
            typeLabel = r.primaryTypeDisplayName?.text,
            openNow = r.currentOpeningHours?.openNow,
            weekdayHours = r.regularOpeningHours?.weekdayDescriptions.orEmpty(),
            phone = r.internationalPhoneNumber,
            website = r.websiteUri,
            rating = r.rating,
            userRatingCount = r.userRatingCount,
            mapsURL = r.googleMapsUri,
            latitude = r.location?.latitude,
            longitude = r.location?.longitude,
        )
    }

    /** A GET of one place, asking only for the fields in `fieldMask`. */
    private fun placeRequest(placeId: String, fieldMask: String): Request =
        Request.Builder()
            .url("https://places.googleapis.com/v1/places/$placeId")
            .header("X-Goog-Api-Key", apiKey)
            .header("X-Goog-FieldMask", fieldMask)
            .build()

    /** Sends `request` and returns the response body, whatever its status. */
    private suspend fun data(request: Request): String = withContext(Dispatchers.IO) {
        session.newCall(request).await().use { it.body?.string() ?: "" }
    }

    companion object {
        val shared: GooglePlacesClient by lazy { GooglePlacesClient() }

        private val jsonMediaType = "application/json".toMediaType()

        /**
         * Reads `GooglePlacesAPIKey` from the bundled `Secrets.json`. Returns an empty
         * string if the file or key is missing, which surfaces as a clear error on use.
         */
        private fun loadAPIKey(): String =
            try {
                val data = OutboundApp.context.assets.open("Secrets.json").use { it.readBytes().decodeToString() }
                val secrets = Json.parseToJsonElement(data) as? JsonObject
                (secrets?.get("GooglePlacesAPIKey") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: ""
            } catch (_: Exception) {
                ""
            }

        /**
         * Place Details (New) is a GET with a per-place URL. Google bills by field, so the
         * mask asks only for what the detail sheet shows.
         */
        private val detailsFieldMask = listOf(
            "displayName",
            "formattedAddress",
            "regularOpeningHours.weekdayDescriptions",
            "currentOpeningHours.openNow",
            "internationalPhoneNumber",
            "websiteUri",
            "rating",
            "userRatingCount",
            "primaryTypeDisplayName",
            "googleMapsUri",
            "location",
        ).joinToString(",")
    }
}
