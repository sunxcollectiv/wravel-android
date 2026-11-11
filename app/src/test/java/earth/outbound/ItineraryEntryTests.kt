//
//  ItineraryEntryTests.kt
//  outboundTests
//

package earth.outbound

import earth.outbound.networking.APIClient
import org.junit.Assert.assertEquals
import org.junit.Test

/** How an itinerary entry picks the time and place for the spot its phase stands for. */
class ItineraryEntryTests {

    @Test
    fun arrivalShowsTheItemsEndAndDestination() {
        val entry = decodeEntry(phase = "arrival", legs = listOf(leg("JFK", "MEL", "2026-11-11T08:30:00", "2026-11-12T09:00:00")))

        assertEquals(entry.item.endsAtLocal, entry.displayLocal)
        assertEquals("MEL", entry.place?.id)
    }

    @Test
    fun checkOutFallsBackToTheLodgingsOwnPlace() {
        val entry = decodeEntry(kind = "lodging", phase = "check_out", endPlace = null, legs = emptyList())

        assertEquals(entry.item.endsAtLocal, entry.displayLocal)
        assertEquals("JFK", entry.place?.id)
    }

    @Test
    fun layoverReadsItsTimesOffTheLegsAroundTheStop() {
        val entry = decodeEntry(
            phase = "layover", layoverPlace = "LAX", layoverMinutes = 150,
            legs = listOf(
                leg("JFK", "LAX", "2026-11-11T08:00:00", "2026-11-11T11:00:00", flight = "QF 12"),
                leg("LAX", "MEL", "2026-11-11T13:30:00", "2026-11-12T21:00:00", flight = "QF 94"),
            ),
        )

        assertEquals("LAX", entry.place?.id)
        assertEquals(150, entry.layoverMinutes)
        assertEquals("JFK-LAX", entry.layoverInbound?.id)
        assertEquals(entry.layoverInbound?.endsAtLocal, entry.displayLocal)
        assertEquals("LAX-MEL", entry.layoverOutbound?.id)
        assertEquals("Qantas QF 94", entry.segment?.label)
    }

    @Test
    fun entriesOfOneItemHaveDistinctIds() {
        val legs = listOf(
            leg("JFK", "LAX", "2026-11-11T08:00:00", "2026-11-11T11:00:00"),
            leg("LAX", "SYD", "2026-11-11T13:30:00", "2026-11-12T21:00:00"),
            leg("SYD", "MEL", "2026-11-12T23:00:00", "2026-11-13T00:30:00"),
        )
        val ids = listOf(
            decodeEntry(phase = "departure", legs = legs),
            decodeEntry(phase = "layover", layoverPlace = "LAX", layoverMinutes = 150, legs = legs),
            decodeEntry(phase = "layover", layoverPlace = "SYD", layoverMinutes = 120, legs = legs),
            decodeEntry(phase = "arrival", legs = legs),
        ).map { it.id }

        assertEquals(ids.size, ids.toSet().size)
    }

    // Fixtures

    private fun leg(from: String, to: String, start: String, end: String, flight: String? = null): String =
        """
        {"id": "$from-$to", "position": 0, "starts_at": "$start", "start_timezone": null,
         "ends_at": "$end", "end_timezone": null, "starts_at_utc": null, "ends_at_utc": null,
         "place": ${place(from)}, "end_place": ${place(to)},
         "carrier": ${if (flight == null) "null" else "\"Qantas\""}, "flight_number": ${flight?.let { "\"$it\"" } ?: "null"}}
        """

    private fun place(id: String?): String {
        if (id == null) return "null"
        return """
        {"id": "$id", "name": "$id", "address": null, "latitude": null, "longitude": null,
         "timezone": null, "google_place_id": null}
        """
    }

    /**
     * One entry of an item running JFK → `endPlace`, as the server sends it. Leg
     * positions follow array order.
     */
    private fun decodeEntry(
        kind: String = "transit", phase: String, endPlace: String? = "MEL",
        layoverPlace: String? = null, layoverMinutes: Int? = null, legs: List<String>,
    ): ItineraryEntry {
        val segments = legs
            .mapIndexed { index, leg -> leg.replace("\"position\": 0", "\"position\": $index") }
            .joinToString(",")
        val json = """
        {"item": {"id": "item", "trip_id": "t", "kind": "$kind", "scope": "group", "title": "Trip",
                  "notes": null, "tags": [], "starts_at": "2026-11-11T08:30:00", "start_timezone": null,
                  "ends_at": "2026-11-12T21:00:00", "end_timezone": null, "starts_at_utc": null, "ends_at_utc": null,
                  "image_url": null, "place": ${place("JFK")}, "end_place": ${place(endPlace)}, "segments": [$segments],
                  "created_at": "2026-10-01T00:00:00Z", "updated_at": "2026-10-01T00:00:00Z"},
         "phase": "$phase", "layover_place": ${place(layoverPlace)},
         "layover_minutes": ${layoverMinutes ?: "null"}}
        """
        return APIClient.json.decodeFromString<ItineraryEntry>(json)
    }
}
