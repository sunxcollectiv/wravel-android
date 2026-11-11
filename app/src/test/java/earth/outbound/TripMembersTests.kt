//
//  TripMembersTests.kt
//  outboundTests
//

package earth.outbound

import earth.outbound.networking.APIClient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripMembersTests {
    @Test
    fun tripDecodesMembersWithAndWithoutAccounts() {
        val json = """
        {
          "id": "t1", "owner_id": "u1", "title": "Tokyo", "description": null,
          "start_date": "2026-11-01", "end_date": "2026-11-05", "cover_image_url": null,
          "areas": [], "areas_missing_photos": [],
          "members": [
            {"id": "m1", "email": "owner@example.com", "role": "organizer", "created_at": "2026-10-26T00:00:00Z",
             "user": {"id": "u1", "email": "owner@example.com", "display_name": "Owner", "avatar_url": null,
                      "created_at": "2026-10-01T00:00:00Z"}},
            {"id": "m2", "email": "sam@example.com", "role": "member", "created_at": "2026-10-26T00:00:00Z",
             "user": null}
          ],
          "created_at": "2026-10-26T00:00:00Z", "updated_at": "2026-10-26T00:00:00Z"
        }
        """
        val trip = APIClient.json.decodeFromString<Trip>(json)

        assertEquals("u1", trip.ownerId)
        assertEquals(listOf("owner@example.com", "sam@example.com"), trip.members?.map { it.email })
        assertEquals(TripRole.Organizer, trip.members?.firstOrNull()?.role)
        assertNull(trip.members?.lastOrNull()?.user)
    }

    @Test
    fun tripStillDecodesWithoutMembers() {
        val json = """
        {"id": "t1", "title": "Tokyo", "start_date": "2026-11-01", "end_date": "2026-11-05",
         "created_at": "2026-10-26T00:00:00Z", "updated_at": "2026-10-26T00:00:00Z"}
        """
        val trip = APIClient.json.decodeFromString<Trip>(json)
        assertNull(trip.members)
    }

    @Test
    fun createRequestEncodesMembersAndOmitsNilRole() {
        val body = TripsAPI.CreateRequest(
            title = "Tokyo", description = JsonNull, startDate = "2026-11-01", endDate = "2026-11-05",
            members = listOf(
                TripsAPI.MemberRequest(email = "jesse@example.com", role = TripRole.Organizer),
                TripsAPI.MemberRequest(email = "sam@example.com"),
            ),
        )
        val members = encode(body).getValue("members").jsonArray.map { it.jsonObject }

        assertEquals(2, members.size)
        assertEquals("jesse@example.com", members[0]["email"]?.jsonPrimitive?.content)
        assertEquals("organizer", members[0]["role"]?.jsonPrimitive?.content)
        assertNull(members[1]["role"])
    }

    @Test
    fun createRequestOmitsMembersWhenNil() {
        val body = TripsAPI.CreateRequest(title = "Tokyo", description = JsonNull, startDate = "2026-11-01", endDate = "2026-11-05")
        assertNull(encode(body)["members"])
    }

    /** The body as the API client sends it. */
    private fun encode(body: TripsAPI.CreateRequest): JsonObject =
        APIClient.json.parseToJsonElement(APIClient.json.encodeToString(body)).jsonObject
}
