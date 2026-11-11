//
//  TripItemParticipantsTests.kt
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripItemParticipantsTests {
    private fun decodeItem(extra: String): TripItem {
        val json = """
        {"id": "item", "trip_id": "t", "kind": "activity", "visibility": "closed", "title": "Dinner",
         "notes": null, "tags": [], "starts_at": "2026-11-11T19:00:00", "start_timezone": null,
         "ends_at": null, "end_timezone": null, "starts_at_utc": null, "ends_at_utc": null,
         "image_url": null, "place": null, "end_place": null, "segments": [],
         "created_at": "2026-10-01T00:00:00Z", "updated_at": "2026-10-01T00:00:00Z"$extra}
        """
        return APIClient.json.decodeFromString<TripItem>(json)
    }

    private inline fun <reified T> encode(value: T): JsonObject =
        APIClient.json.encodeToJsonElement(value).jsonObject

    @Test
    fun itemDecodesCreatorAndParticipants() {
        val item = decodeItem(
            """
        , "created_by": {"id": "u1", "email": "a@example.com", "display_name": "A", "avatar_url": null,
                         "created_at": "2026-10-01T00:00:00Z"},
          "participants": [
            {"member": {"id": "m2", "email": "b@example.com", "user": null, "role": "member",
                        "created_at": "2026-10-01T00:00:00Z"},
             "status": "going", "updated_at": "2026-10-01T00:00:00Z"}
          ]
        """,
        )
        assertEquals("u1", item.createdBy?.id)
        assertEquals(listOf("m2"), item.participants.map { it.member.id })
        assertEquals(RsvpStatus.Going, item.participants.firstOrNull()?.status)
        assertEquals(TripItemVisibility.Closed, item.visibility)
        assertFalse(item.redacted)
    }

    @Test
    fun participantWithoutAnAnswerDecodes() {
        val item = decodeItem(
            """
        , "redacted": true, "participants": [
            {"member": {"id": "m2", "email": "b@example.com", "user": null, "role": "member",
                        "created_at": "2026-10-01T00:00:00Z"},
             "status": null, "updated_at": "2026-10-01T00:00:00Z"}
          ]
        """,
        )
        assertTrue(item.redacted)
        assertNull(item.participants.firstOrNull()?.status)
    }

    @Test
    fun itemWithoutParticipantsStillDecodes() {
        val item = decodeItem("")
        assertNull(item.createdBy)
        assertTrue(item.participants.isEmpty())
    }

    @Test
    fun itemDecodesDisplayTitle() {
        val item = decodeItem(""", "display_title": "Dinner at Lilia"""")
        assertEquals("Dinner", item.title)
        assertEquals("Dinner at Lilia", item.displayTitle)
    }

    @Test
    fun untitledItemDecodes() {
        val json = """
        {"id": "item", "trip_id": "t", "kind": "lodging", "title": null, "display_title": "Ace Hotel",
         "starts_at": "2026-11-11T15:00:00", "created_at": "2026-10-01T00:00:00Z",
         "updated_at": "2026-10-01T00:00:00Z"}
        """
        val item = APIClient.json.decodeFromString<TripItem>(json)
        assertNull(item.title)
        assertEquals("Ace Hotel", item.displayTitle)
    }

    @Test
    fun itemDecodesTransitMode() {
        assertEquals(TransitMode.CarRental, decodeItem(""", "transit_mode": "car_rental"""").transitMode)
        // An unknown mode leaves it unset rather than failing the item.
        assertNull(decodeItem(""", "transit_mode": "hovercraft"""").transitMode)
        assertNull(decodeItem("").transitMode)
    }

    @Test
    fun requestsSendTransitModeOnlyWhenSet() {
        val create = TripItemsAPI.CreateRequest(
            kind = TripItemKind.Transit, title = null, notes = null,
            startsAt = "2026-11-11T09:00:00", startTimezone = null, endsAt = null, endTimezone = null,
            placeId = "p1", endPlaceId = "p2", transitMode = TransitMode.Train,
        )
        assertEquals(JsonPrimitive("train"), encode(create)["transit_mode"])
        assertEquals(JsonPrimitive("train"), encode(TripItemsAPI.UpdateRequest(create))["transit_mode"])

        val unset = TripItemsAPI.CreateRequest(
            kind = TripItemKind.Transit, title = null, notes = null,
            startsAt = "2026-11-11T09:00:00", startTimezone = null, endsAt = null, endTimezone = null,
            placeId = "p1", endPlaceId = "p2",
        )
        assertFalse(encode(unset).containsKey("transit_mode"))
        assertFalse(encode(TripItemsAPI.UpdateRequest(unset)).containsKey("transit_mode"))
    }

    @Test
    fun createSendsParticipantsByMemberId() {
        val body = TripItemsAPI.CreateRequest(
            kind = TripItemKind.Activity, title = "Dinner", notes = null,
            startsAt = "2026-11-11T19:00:00", startTimezone = null, endsAt = null, endTimezone = null,
            placeId = null, endPlaceId = null,
            participants = listOf(TripItemsAPI.ParticipantRequest(memberId = "m2")),
        )
        val obj = encode(body)
        assertNull(obj["scope"])
        val participants = obj["participants"] as JsonArray
        assertEquals(JsonPrimitive("m2"), participants.first().jsonObject["member_id"])
        // No status sent, so the server keeps any existing answer.
        assertNull(participants.first().jsonObject["status"])
    }

    @Test
    fun requestsSendVisibility() {
        val create = TripItemsAPI.CreateRequest(
            kind = TripItemKind.Activity, visibility = TripItemVisibility.Closed, title = "Dinner", notes = null,
            startsAt = "2026-11-11T19:00:00", startTimezone = null, endsAt = null, endTimezone = null,
            placeId = null, endPlaceId = null,
        )
        assertEquals(JsonPrimitive("closed"), encode(create)["visibility"])
        val update = TripItemsAPI.UpdateRequest(
            kind = TripItemKind.Activity, visibility = TripItemVisibility.Private, title = "Dinner", notes = null,
            startsAt = "2026-11-11T19:00:00", startTimezone = null, endsAt = null, endTimezone = null,
            placeId = null, endPlaceId = null,
        )
        assertEquals(JsonPrimitive("private"), encode(update)["visibility"])
    }

    @Test
    fun createOmitsMissingTitle() {
        val body = TripItemsAPI.CreateRequest(
            kind = TripItemKind.Lodging, title = null, notes = null,
            startsAt = "2026-11-11T15:00:00", startTimezone = null, endsAt = null, endTimezone = null,
            placeId = "p1", endPlaceId = null,
        )
        val obj = encode(body)
        assertFalse(obj.containsKey("title"))
        assertEquals(JsonPrimitive("p1"), obj["place_id"])
    }

    @Test
    fun updateNullsTitleOrPlaceWhenAsked() {
        val create = TripItemsAPI.CreateRequest(
            kind = TripItemKind.Lodging, title = "Grandma's", notes = null,
            startsAt = "2026-11-11T15:00:00", startTimezone = null, endsAt = null, endTimezone = null,
            placeId = null, endPlaceId = null,
        )
        val lodging = encode(TripItemsAPI.UpdateRequest(create, nullsTitleAndPlace = true))
        assertEquals(JsonPrimitive("Grandma's"), lodging["title"])
        // Present as null, so the server clears the place the name replaces.
        assertEquals(JsonNull, lodging["place_id"])

        val other = encode(TripItemsAPI.UpdateRequest(create))
        assertFalse(other.containsKey("place_id"))
    }

    @Test
    fun updateOmitsParticipantsWhenUnchanged() {
        val body = TripItemsAPI.UpdateRequest(
            kind = TripItemKind.Activity, title = "Dinner", notes = null, startsAt = "2026-11-11T19:00:00",
            startTimezone = null, endsAt = null, endTimezone = null, placeId = null, endPlaceId = null,
        )
        val obj = encode(body)
        assertNull(obj["scope"])
        assertNull(obj["participants"])
    }

    @Test
    fun updateSendsParticipantsWhenSet() {
        val body = TripItemsAPI.UpdateRequest(
            kind = TripItemKind.Activity, title = "Dinner", notes = null, startsAt = "2026-11-11T19:00:00",
            startTimezone = null, endsAt = null, endTimezone = null, placeId = null, endPlaceId = null,
            participants = listOf(TripItemsAPI.ParticipantRequest(memberId = "m1")),
        )
        val obj = encode(body)
        // The update endpoint rejects unknown keys, so scope must never be sent.
        assertNull(obj["scope"])
        assertEquals(1, (obj["participants"] as? JsonArray)?.size)
    }
}
