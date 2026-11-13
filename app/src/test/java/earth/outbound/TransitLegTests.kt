//
//  TransitLegTests.kt
//  outboundTests
//

package earth.outbound

import earth.outbound.networking.APIClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TransitLegTests {
    private inline fun <reified T> encode(value: T): JsonObject =
        APIClient.json.encodeToJsonElement(value).jsonObject

    private fun decodeTransit(segments: String): TripItem {
        val json = """
        {"id": "item", "trip_id": "t", "kind": "transit", "title": null, "display_title": "JFK to LAX",
         "starts_at": "2026-11-11T09:00:00", "start_timezone": "America/New_York",
         "ends_at": "2026-11-11T18:00:00", "end_timezone": "America/Los_Angeles",
         "transit_mode": "mixed", "segments": $segments,
         "created_at": "2026-10-01T00:00:00Z", "updated_at": "2026-10-01T00:00:00Z"}
        """
        return APIClient.json.decodeFromString<TripItem>(json)
    }

    private val twoLegs = """
    [{"id": "s2", "position": 1, "starts_at": "2026-11-11T14:00:00", "start_timezone": "America/Chicago",
      "ends_at": "2026-11-11T18:00:00", "end_timezone": "America/Los_Angeles",
      "transit_mode": "train", "carrier": null, "flight_number": null, "confirmation_number": null},
     {"id": "s1", "position": 0, "starts_at": "2026-11-11T09:00:00", "start_timezone": "America/New_York",
      "ends_at": "2026-11-11T12:00:00", "end_timezone": "America/Chicago",
      "transit_mode": "flight", "carrier": "Delta", "flight_number": "DL 12", "confirmation_number": "ABC123"}]
    """

    @Test
    fun updateWithSegmentsLeavesOutTheItemsSpan() {
        val create = TripItemsAPI.CreateRequest(
            kind = TripItemKind.Transit, title = null, notes = null,
            startsAt = null, startTimezone = null, endsAt = null, endTimezone = null,
            placeId = null, endPlaceId = null,
            segments = listOf(
                TripItemsAPI.SegmentRequest(
                    startsAt = "2026-11-11T09:00:00", startTimezone = "America/New_York",
                    endsAt = null, endTimezone = null, placeId = "p1", endPlaceId = "p2",
                    transitMode = TransitMode.Flight, carrier = "Delta",
                ),
            ),
        )
        for (obj in listOf(encode(create), encode(TripItemsAPI.UpdateRequest(create)))) {
            // The server rejects these alongside segments; an update mustn't even null them.
            for (key in listOf("starts_at", "start_timezone", "ends_at", "end_timezone", "place_id", "end_place_id", "transit_mode")) {
                assertFalse(key, obj.containsKey(key))
            }
            val segment = (obj["segments"] as JsonArray).first().jsonObject
            assertEquals(JsonPrimitive("2026-11-11T09:00:00"), segment["starts_at"])
            assertEquals(JsonPrimitive("p1"), segment["place_id"])
            assertEquals(JsonPrimitive("flight"), segment["transit_mode"])
            assertEquals(JsonPrimitive("Delta"), segment["carrier"])
            assertFalse(segment.containsKey("ends_at"))
        }
    }

    @Test
    fun updateWithoutSegmentsStillSendsTheSpan() {
        val create = TripItemsAPI.CreateRequest(
            kind = TripItemKind.Activity, title = "Dinner", notes = null,
            startsAt = "2026-11-11T19:00:00", startTimezone = null, endsAt = null, endTimezone = null,
            placeId = null, endPlaceId = null,
        )
        val obj = encode(TripItemsAPI.UpdateRequest(create))
        assertEquals(JsonPrimitive("2026-11-11T19:00:00"), obj["starts_at"])
        assertEquals(JsonNull, obj["ends_at"])
        assertFalse(obj.containsKey("segments"))
    }

    @Test
    fun draftLoadsSavedLegsInOrder() {
        val item = decodeTransit(twoLegs)
        val draft = TripItemDraft(tripId = "t", kind = TripItemKind.Transit, item = item)
        assertEquals(2, draft.legs.size)
        assertEquals(listOf(TransitMode.Flight, TransitMode.Train), draft.legs.map { it.transitMode })
        assertEquals("America/New_York", draft.legs.firstOrNull()?.startZone?.id)
        // Booking details ride along, so saving doesn't drop them.
        val first = draft.legs.first().request!!
        assertEquals("Delta", first.carrier)
        assertEquals("DL 12", first.flightNumber)
        assertEquals("ABC123", first.confirmationNumber)
        assertEquals("2026-11-11T09:00:00", first.startsAt)
    }

    @Test
    fun newTransitStartsWithOneLeg() {
        val draft = TripItemDraft(tripId = "t", kind = TripItemKind.Transit)
        assertEquals(1, draft.legs.size)
        assertNotNull(draft.legs.firstOrNull()?.startsAt)
        // A flight, the most common case.
        assertEquals(TransitMode.Flight, draft.legs.firstOrNull()?.transitMode)
        assertTrue(TripItemDraft(tripId = "t", kind = TripItemKind.Activity).legs.isEmpty())
    }

    @Test
    fun addedLegPicksUpWhereTheLastArrives() {
        val draft = TripItemDraft(tripId = "t", kind = TripItemKind.Transit, item = decodeTransit(twoLegs)).addLeg()
        val last = draft.legs[1]
        val added = draft.legs[2]
        assertEquals(last.endsAt, added.startsAt)
        assertEquals(last.endZone, added.startZone)
        assertEquals(TransitMode.Train, added.transitMode)
        assertNull(added.endsAt)
        // Nothing from the booking carries over to a new leg.
        assertNull(added.carrier)
    }

    @Test
    fun lastLegCantBeRemoved() {
        var draft = TripItemDraft(tripId = "t", kind = TripItemKind.Transit, item = decodeTransit(twoLegs))
        draft = draft.removeLeg(draft.legs[0])
        assertEquals(listOf(TransitMode.Train), draft.legs.map { it.transitMode })
        draft = draft.removeLeg(draft.legs[0])
        assertEquals(1, draft.legs.size)
    }

    @Test
    fun legArrivingBeforeItDepartsIsCaught() {
        val leg = TransitLeg(startsAt = Instant.ofEpochSecond(3600), endsAt = Instant.ofEpochSecond(0))
        assertTrue(leg.endsBeforeStart)
        assertNull(TransitLeg(startsAt = null).request)
    }
}
