//
//  TripMembersTests.kt
//  wravelTests
//

package app.wravel

import app.wravel.networking.APIClient
import app.wravel.networking.HTTPMethod
import app.wravel.networking.orJsonNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun memberNameFallsBackFromAccountToGivenName() {
        val json = """
        [
          {"id": "m1", "email": "sam@example.com", "role": "member", "display_name": "Sammy",
           "user": {"id": "u1", "email": "sam@example.com", "display_name": "Sammy",
                    "avatar_url": null, "created_at": "2026-10-01T00:00:00Z"}},
          {"id": "m2", "email": "jesse@example.com", "role": "member", "display_name": "Jesse", "user": null},
          {"id": "m3", "email": "pat@example.com", "role": "member", "display_name": null, "user": null},
          {"id": "m4", "email": "old@example.com", "role": "member",
           "user": {"id": "u4", "email": "old@example.com", "display_name": "Old Server",
                    "avatar_url": null, "created_at": "2026-10-01T00:00:00Z"}}
        ]
        """
        val members = APIClient.json.decodeFromString<List<TripMember>>(json)

        assertEquals(listOf("Sammy", "Jesse", null, "Old Server"), members.map { it.name })
    }

    @Test
    fun memberRequestSendsNameOnlyWhenGiven() {
        val body = TripsAPI.CreateRequest(
            title = "Tokyo", description = JsonNull, startDate = "2026-11-01", endDate = "2026-11-05",
            members = listOf(
                TripsAPI.MemberRequest(email = "jesse@example.com", name = "Jesse"),
                TripsAPI.MemberRequest(email = "sam@example.com"),
            ),
        )
        val members = encode(body).getValue("members").jsonArray.map { it.jsonObject }

        assertEquals("Jesse", members[0]["name"]?.jsonPrimitive?.content)
        // Left out, so the server keeps the name someone already on the trip has.
        assertFalse(members[1].containsKey("name"))
    }

    @Test
    fun memberUpdateSendsNullToClearTheName() {
        val renamed = encodeObject(TripsAPI.MemberUpdate(name = "Jesse".orJsonNull()))
        assertEquals(mapOf("name" to JsonPrimitive("Jesse")), renamed)

        val cleared = encodeObject(TripsAPI.MemberUpdate(name = null.orJsonNull()))
        assertEquals(setOf("name"), cleared.keys)
        assertEquals(JsonNull, cleared["name"])
    }

    @Test
    fun updateMemberPatchesThatMember() {
        val endpoint = TripsAPI.updateMember("t1", "m2", TripsAPI.MemberUpdate(name = "Jesse".orJsonNull()))
        assertEquals("/trips/t1/members/m2", endpoint.path)
        assertEquals(HTTPMethod.PATCH, endpoint.method)
    }

    /** The body as the API client sends it. */
    private fun encode(body: TripsAPI.CreateRequest): JsonObject =
        APIClient.json.parseToJsonElement(APIClient.json.encodeToString(body)).jsonObject

    private fun encodeObject(body: TripsAPI.MemberUpdate): JsonObject =
        APIClient.json.parseToJsonElement(APIClient.json.encodeToString(body)).jsonObject
}
