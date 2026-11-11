//
//  ItineraryPlacesTests.kt
//  outboundTests
//

package earth.outbound

import earth.outbound.networking.APIClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How the itinerary keeps one copy of each place, and how a stale-place refresh
 * patches every card that shows it.
 */
class ItineraryPlacesTests {

    @Test
    fun everyEntryForAPlaceReadsOneCopy() {
        // A flight's departure and arrival, and a dinner at the arrival airport.
        val itinerary = decodeItinerary(stale = listOf("MEL"))
        val places = ItineraryPlaces(itinerary.days)
        val entries = itinerary.days.flatMap { it.entries }
        val melEntries = entries.filter { it.place?.id == "MEL" }
        assertEquals(2, melEntries.size)

        places.update(mapOf("MEL" to place("MEL", name = "Melbourne Airport", photo = "https://example.com/mel.jpg")))

        for (entry in melEntries) {
            assertEquals("Melbourne Airport", places.current(entry.place)?.name)
            assertEquals("https://example.com/mel.jpg", places.current(entry.place)?.coverPhoto?.url)
        }
        // Places outside the refresh keep the itinerary's copy.
        val departure = entries.first { it.phase == ItineraryPhase.Departure }
        assertEquals("JFK", places.current(departure.place)?.name)
    }

    @Test
    fun aRefreshReplacesTheWholePlace() {
        val places = ItineraryPlaces(decodeItinerary().days)
        assertEquals("https://example.com/old.jpg", places.byId["MEL"]?.coverPhoto?.url)

        places.update(mapOf("MEL" to place("MEL", name = "Melbourne Airport", address = "Tullamarine VIC", photo = null)))

        val mel = places.byId.getValue("MEL")
        assertEquals("Melbourne Airport", mel.name)
        assertEquals("Tullamarine VIC", mel.address)
        assertNull(mel.coverPhoto)
    }

    @Test
    fun resolvingAnItemSwapsInTheSharedCopies() {
        val itinerary = decodeItinerary()
        val places = ItineraryPlaces(itinerary.days)
        places.update(mapOf("MEL" to place("MEL", name = "Melbourne Airport")))
        val flight = itinerary.days.flatMap { it.entries }.first { it.item.kind == TripItemKind.Transit }.item

        val resolved = places.resolving(flight)

        assertEquals("Melbourne Airport", resolved.endPlace?.name)
        assertEquals("Melbourne Airport", resolved.segments.firstOrNull()?.endPlace?.name)
        assertEquals("JFK", resolved.place?.name)
    }

    @Test
    fun decodesStalePlaceIds() {
        assertEquals(listOf("MEL", "JFK"), decodeItinerary(stale = listOf("MEL", "JFK")).stalePlaceIds)
        assertEquals(emptyList<String>(), decodeItinerary(stale = null).stalePlaceIds)
    }

    @Test
    fun decodesARefreshResponseKeyedByPlaceId() {
        // Real ids are UUIDs; the snake-case naming strategy must leave them alone.
        val id = "3f2c7a9e-1b4d-4c8e-9a6f-2d5b8e1c0a7f"
        val json = """{"places": {"$id": ${placeJSON(id, name = "Grill'd", photo = "https://example.com/g.jpg")}}}"""
        val response = APIClient.json.decodeFromString<PlacesAPI.RefreshResponse>(json)

        assertEquals("Grill'd", response.places[id]?.name)
        assertEquals("https://example.com/g.jpg", response.places[id]?.coverPhoto?.url)
    }

    // Fixtures

    private fun place(id: String, name: String, address: String? = null, photo: String? = null): Place =
        APIClient.json.decodeFromString<Place>(placeJSON(id, name = name, address = address, photo = photo))

    private fun placeJSON(id: String, name: String, address: String? = null, photo: String? = null): String {
        val cover = photo?.let { """{"url": "$it", "attributions": [], "is_stale": false}""" } ?: "null"
        return """
        {"id": "$id", "name": "$name", "address": ${address?.let { "\"$it\"" } ?: "null"},
         "latitude": null, "longitude": null, "timezone": null, "google_place_id": null, "cover_photo": $cover}
        """
    }

    /**
     * One day: a JFK → MEL flight (departure + arrival entries) and a dinner at MEL.
     * The itinerary's own copy of MEL carries an old cover photo.
     */
    private fun decodeItinerary(stale: List<String>? = emptyList()): Itinerary {
        val jfk = placeJSON("JFK", name = "JFK")
        val mel = placeJSON("MEL", name = "MEL", photo = "https://example.com/old.jpg")
        fun item(id: String, kind: String, place: String, endPlace: String, segments: String): String =
            """
            {"id": "$id", "trip_id": "t", "kind": "$kind", "scope": "group", "title": "$id",
             "notes": null, "tags": [], "starts_at": "2026-11-11T08:30:00", "start_timezone": null,
             "ends_at": "2026-11-11T21:00:00", "end_timezone": null, "starts_at_utc": null, "ends_at_utc": null,
             "image_url": null, "place": $place, "end_place": $endPlace, "segments": [$segments],
             "created_at": "2026-10-01T00:00:00Z", "updated_at": "2026-10-01T00:00:00Z"}
            """
        val leg = """
        {"id": "JFK-MEL", "position": 0, "starts_at": "2026-11-11T08:30:00", "start_timezone": null,
         "ends_at": "2026-11-11T21:00:00", "end_timezone": null, "starts_at_utc": null, "ends_at_utc": null,
         "place": $jfk, "end_place": $mel, "carrier": null, "flight_number": null}
        """
        val flight = item("flight", "transit", place = jfk, endPlace = mel, segments = leg)
        val dinner = item("dinner", "activity", place = mel, endPlace = "null", segments = "")
        val staleJSON = stale?.let { ids -> """, "stale_place_ids": [${ids.joinToString(",") { "\"$it\"" }}]""" } ?: ""
        val json = """
        {"trip": {"id": "t", "title": "Trip", "description": null, "start_date": "2026-11-11", "end_date": "2026-11-12",
                  "cover_image_url": null, "created_at": "2026-10-01T00:00:00Z", "updated_at": "2026-10-01T00:00:00Z"},
         "days": [{"date": "2026-11-11", "day_number": 1, "lodging": [], "items": [
            {"item": $flight, "phase": "departure", "layover_place": null, "layover_minutes": null},
            {"item": $flight, "phase": "arrival", "layover_place": null, "layover_minutes": null},
            {"item": $dinner, "phase": null, "layover_place": null, "layover_minutes": null}
         ]}]$staleJSON}
        """
        return APIClient.json.decodeFromString<Itinerary>(json)
    }
}
