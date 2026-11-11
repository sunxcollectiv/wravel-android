//
//  ItineraryZoneChangesTests.kt
//  outboundTests
//

package earth.outbound

import earth.outbound.networking.APIClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the itinerary marks a change of clock between entries. */
class ItineraryZoneChangesTests {

    @Test
    fun marksTheFirstEntryInANewZoneOnly() {
        val day = ItineraryDay(
            date = "2026-11-11", dayNumber = 1, lodging = emptyList(),
            entries = listOf(
                entry("breakfast", start = "2026-11-11T08:00:00", startZone = "America/New_York"),
                entry(
                    "flight", kind = "transit", phase = "arrival", start = "2026-11-11T10:00:00", startZone = "America/New_York",
                    end = "2026-11-11T13:00:00", endZone = "America/Los_Angeles",
                ),
                entry("dinner", start = "2026-11-11T19:00:00", startZone = "America/Los_Angeles"),
            ),
        )

        val changes = ItineraryZoneChanges.changes(listOf(day))

        assertEquals(listOf("flight|arrival|"), changes.keys.sorted())
        assertEquals("America/Los_Angeles", changes["flight|arrival|"]?.id)
    }

    @Test
    fun aChangeOvernightMarksTheTopOfTheNextDay() {
        val days = listOf(
            ItineraryDay(
                date = "2026-11-11", dayNumber = 1, lodging = emptyList(),
                entries = listOf(entry("dinner", start = "2026-11-11T19:00:00", startZone = "America/New_York")),
            ),
            ItineraryDay(
                date = "2026-11-12", dayNumber = 2, lodging = emptyList(),
                entries = listOf(entry("tour", start = "2026-11-12T09:00:00", startZone = "Europe/London")),
            ),
        )

        assertEquals(listOf("tour||"), ItineraryZoneChanges.changes(days).keys.sorted())
    }

    @Test
    fun zonelessEntriesAndMatchingOffsetsAreNotChanges() {
        val day = ItineraryDay(
            date = "2026-11-11", dayNumber = 1, lodging = emptyList(),
            entries = listOf(
                entry("coffee", start = "2026-11-11T08:00:00", startZone = "America/New_York"),
                entry("walk", start = "2026-11-11T10:00:00", startZone = null),
                entry("lunch", start = "2026-11-11T12:00:00", startZone = "America/Toronto"),
            ),
        )

        assertTrue(ItineraryZoneChanges.changes(listOf(day)).isEmpty())
    }

    @Test
    fun aLayoverIsReadInItsStopsZone() {
        val layover = entry(
            "flight", kind = "transit", phase = "layover", start = "2026-11-11T08:00:00",
            startZone = "America/New_York", layoverZone = "America/Chicago",
        )
        val day = ItineraryDay(
            date = "2026-11-11", dayNumber = 1, lodging = emptyList(),
            entries = listOf(
                entry("coffee", start = "2026-11-11T06:00:00", startZone = "America/New_York"),
                layover,
            ),
        )

        assertEquals("America/Chicago", layover.timeZone?.id)
        assertEquals("America/Chicago", ItineraryZoneChanges.changes(listOf(day))[layover.id]?.id)
    }

    @Test
    fun aFlightsEntriesStayTogetherInTravelOrder() {
        // Melbourne → LA → New York: the LA layover's 10:10 AM ties the 10:10 AM
        // departure on the wall clock, and the 3 PM check-out falls between legs.
        val legs = """
        [{"id": "MEL-LAX", "position": 0, "starts_at": "2026-11-19T10:10:00", "start_timezone": "Australia/Melbourne",
          "ends_at": "2026-11-19T08:40:00", "end_timezone": "America/Los_Angeles",
          "place": {"id": "MEL"}, "end_place": {"id": "LAX"}},
         {"id": "LAX-JFK", "position": 1, "starts_at": "2026-11-19T10:10:00", "start_timezone": "America/Los_Angeles",
          "ends_at": "2026-11-19T15:41:00", "end_timezone": "America/New_York",
          "place": {"id": "LAX"}, "end_place": {"id": "JFK"}}]
        """
        val flight = { phase: String, extra: String ->
            entry(
                "flight", kind = "transit", phase = phase, start = "2026-11-19T10:10:00", startZone = "Australia/Melbourne",
                end = "2026-11-19T15:41:00", endZone = "America/New_York", segments = legs, extra = extra,
            )
        }
        val arrival = flight("arrival", "")
        val layover = flight("layover", """, "layover_place": {"id": "LAX"}, "layover_timezone": "America/Los_Angeles"""")
        val departure = flight("departure", "")
        val checkOut = entry(
            "hotel", kind = "lodging", phase = "check_out", start = "2026-11-13T15:00:00",
            startZone = "Australia/Melbourne", end = "2026-11-19T15:00:00", endZone = "Australia/Melbourne",
        )

        val ordered = ItineraryDay.ordered(listOf(arrival, checkOut, layover, departure))

        assertEquals(listOf(departure.id, layover.id, arrival.id, checkOut.id), ordered.map { it.id })
        val day = ItineraryDay(date = "2026-11-19", dayNumber = 9, lodging = emptyList(), entries = ordered)
        val changes = ItineraryZoneChanges.changes(listOf(day))
        assertEquals("America/Los_Angeles", changes[layover.id]?.id)
        assertEquals("America/New_York", changes[arrival.id]?.id)
        assertEquals("Australia/Melbourne", changes[checkOut.id]?.id)
        assertNull(changes[departure.id])
    }

    // Fixtures

    private fun entry(
        id: String, kind: String = "activity", phase: String? = null,
        start: String, startZone: String?, end: String? = null, endZone: String? = null,
        layoverZone: String? = null, segments: String = "[]", extra: String = "",
    ): ItineraryEntry {
        fun json(value: String?): String = value?.let { "\"$it\"" } ?: "null"
        val payload = """
        {"item": {"id": "$id", "trip_id": "t", "kind": "$kind", "scope": "group", "title": "$id",
                  "tags": [], "starts_at": "$start", "start_timezone": ${json(startZone)},
                  "ends_at": ${json(end)}, "end_timezone": ${json(endZone)}, "segments": $segments,
                  "created_at": "2026-10-01T00:00:00Z", "updated_at": "2026-10-01T00:00:00Z"},
         "phase": ${json(phase)}${extra.ifEmpty { ", \"layover_timezone\": ${json(layoverZone)}" }}}
        """
        return APIClient.json.decodeFromString<ItineraryEntry>(payload)
    }
}
