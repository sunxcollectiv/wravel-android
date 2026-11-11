//
//  LodgingDetailsTests.kt
//  outboundTests
//

package earth.outbound

import earth.outbound.auth.User
import earth.outbound.networking.APIClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration

class LodgingDetailsTests {
    private fun decodeItem(extra: String, kind: String = "lodging"): TripItem {
        val json = """
        {"id": "item", "trip_id": "t", "kind": "$kind", "title": "Stay",
         "notes": null, "tags": [], "starts_at": "2026-12-27T15:00:00", "start_timezone": "Asia/Bangkok",
         "ends_at": null, "end_timezone": null, "starts_at_utc": null, "ends_at_utc": null,
         "image_url": null, "place": null, "end_place": null, "segments": [],
         "created_at": "2026-10-01T00:00:00Z", "updated_at": "2026-10-01T00:00:00Z"$extra}
        """
        return APIClient.json.decodeFromString<TripItem>(json)
    }

    private inline fun <reified T> encode(value: T): JsonObject =
        APIClient.json.encodeToJsonElement(value).jsonObject

    private operator fun Instant?.plus(duration: Duration): Instant? = this?.plus(duration.toJavaDuration())

    private val importedHotel = """
    , "lodging": {"property_type": "hotel", "phone": "+66 2 012 1234",
                  "free_cancellation_until": "2026-12-29T23:59:00"},
      "rooms": [
        {"id": "r2", "position": 1, "label": null, "room_number": "412", "room_type": null,
         "guests": 2, "confirmation_number": null, "occupants": ["m1"]},
        {"id": "r1", "position": 0, "label": null, "room_number": null,
         "room_type": "1 King Bed, City view", "guests": 1, "confirmation_number": null, "occupants": []}
      ]
    """

    @Test
    fun decodesLodgingDetails() {
        val item = decodeItem(importedHotel)
        val lodging = item.lodging!!
        assertEquals(LodgingType.Hotel, lodging.propertyType)
        assertEquals("+66 2 012 1234", lodging.phone)
        assertEquals("2026-12-29T23:59:00", lodging.freeCancellationUntil?.let { WallClock.naiveString(it) })
        assertEquals("Hotel", item.typeLabel)
    }

    @Test
    fun vacationRentalDecodes() {
        val item = decodeItem(""", "lodging": {"property_type": "vacation_rental"}""")
        assertEquals(LodgingType.VacationRental, item.lodging?.propertyType)
        assertEquals("Vacation rental", item.typeLabel)
    }

    @Test
    fun missingOrNullOrUnknownDetailsStillDecode() {
        assertNull(decodeItem("").lodging)
        assertNull(decodeItem(""", "lodging": null""").lodging)
        val unknown = decodeItem(""", "lodging": {"property_type": "castle", "phone": "123"}""")
        assertNotNull(unknown.lodging)
        assertNull(unknown.lodging?.propertyType)
        assertEquals("123", unknown.lodging?.phone)
        assertEquals("Lodging", unknown.typeLabel)
        assertTrue(unknown.rooms.isEmpty())
    }

    @Test
    fun createSendsDetailsOnlyWhenSet() {
        fun body(lodging: LodgingDetails?) = TripItemsAPI.CreateRequest(
            kind = TripItemKind.Lodging, title = "Stay", notes = null, startsAt = "2026-12-27T15:00:00",
            startTimezone = null, endsAt = null, endTimezone = null, placeId = null,
            endPlaceId = null, lodging = lodging,
        )
        assertNull(encode(body(null))["lodging"])
        val sent = encode(body(LodgingDetails(propertyType = LodgingType.Hostel)))["lodging"] as JsonObject
        assertEquals(JsonPrimitive("hostel"), sent["property_type"])
    }

    @Test
    fun updateSendsDetailsOnlyWhenChanged() {
        val create = TripItemsAPI.CreateRequest(
            kind = TripItemKind.Lodging, title = "Stay", notes = null, startsAt = "2026-12-27T15:00:00",
            startTimezone = null, endsAt = null, endTimezone = null, placeId = null,
            endPlaceId = null, lodging = LodgingDetails(propertyType = LodgingType.Hotel),
        )
        val untouched = encode(TripItemsAPI.UpdateRequest(create))
        assertNull(untouched["lodging"])
        val changed = encode(TripItemsAPI.UpdateRequest(create, sendsLodging = true))
        assertEquals(JsonPrimitive("hotel"), (changed["lodging"] as? JsonObject)?.get("property_type"))

        val cleared = TripItemsAPI.UpdateRequest(create, sendsLodging = true).copy(lodging = null)
        assertEquals(JsonNull, encode(cleared)["lodging"])
    }

    @Test
    fun changingTheTypeKeepsTheImportedDetails() {
        val item = decodeItem(importedHotel)
        var draft = TripItemDraft(tripId = "t", kind = TripItemKind.Lodging, item = item)
        assertEquals(LodgingType.Hotel, draft.propertyType)
        assertFalse(draft.lodgingChanged)

        draft = draft.copy(propertyType = LodgingType.VacationRental)
        assertTrue(draft.lodgingChanged)
        val details = draft.lodgingToSave!!
        assertEquals(LodgingType.VacationRental, details.propertyType)
        assertEquals("+66 2 012 1234", details.phone)
        // Sent back as the same naive wall clock it came in as, and only with the keys
        // the server takes (it rejects any others).
        val sent = encode(details)
        assertEquals(JsonPrimitive("2026-12-29T23:59:00"), sent["free_cancellation_until"])
        assertEquals(setOf("property_type", "phone", "free_cancellation_until"), sent.keys)
    }

    @Test
    fun clearingTheOnlyDetailClearsThem() {
        val item = decodeItem(""", "lodging": {"property_type": "hostel"}""")
        val draft = TripItemDraft(tripId = "t", kind = TripItemKind.Lodging, item = item).copy(propertyType = null)
        assertTrue(draft.lodgingChanged)
        assertNull(draft.lodgingToSave)
    }

    @Test
    fun otherKindsSendNoDetails() {
        val draft = TripItemDraft(tripId = "t", kind = TripItemKind.Activity).copy(propertyType = LodgingType.Hotel)
        assertNull(draft.lodgingToSave)
        assertFalse(draft.lodgingChanged)
    }

    // MARK: - Display

    @Test
    fun roomsDecodeInOrder() {
        val item = decodeItem(importedHotel)
        assertEquals(listOf("r1", "r2"), item.rooms.map { it.id })
        assertEquals(listOf("m1"), item.rooms[1].occupants)
        val draft = TripItemDraft(tripId = "t", kind = TripItemKind.Lodging, item = item)
        assertEquals(listOf("r1", "r2"), draft.rooms.map { it.id })
        assertTrue(TripItemDraft(tripId = "t", kind = TripItemKind.Lodging).rooms.isEmpty())
    }

    @Test
    fun roomsReadNaturally() {
        val typed = LodgingRoom(id = "a", roomType = "1 King Bed", guests = 1)
        assertEquals("Room 1", typed.title(number = 1))
        assertEquals("1 King Bed · 1 guest", typed.summary)
        assertEquals("Room 412", LodgingRoom(id = "b", roomNumber = "412", guests = 3).title(number = 2))
        assertEquals("Kids' room", LodgingRoom(id = "c", label = "Kids' room", roomNumber = "412").title(number = 3))
        assertEquals("Room 4", LodgingRoom(id = "d", label = " ", roomType = " ").title(number = 4))
        assertNull(LodgingRoom(id = "d").summary)
    }

    @Test
    fun cancellationDeadlineIsReadAtTheProperty() {
        val item = decodeItem(importedHotel)
        val lodging = item.lodging!!
        val bangkok = ZoneId.of("Asia/Bangkok")
        val text = lodging.cancellationText(zone = bangkok)!!
        // The wall clock as written, whatever the device's zone, with the stay's zone.
        assertTrue(text.contains("Dec 29"))
        assertTrue(text.contains("11:59"))
        assertTrue(text.endsWith("GMT+7"))
        assertEquals(true, lodging.cancellationText(zone = null)?.endsWith("PM"))

        // 23:59 in Bangkok is 16:59 UTC.
        val deadline = Instant.parse("2026-12-29T16:59:00Z")
        assertFalse(lodging.cancellationHasPassed(zone = bangkok, now = deadline.minusSeconds(60)))
        assertTrue(lodging.cancellationHasPassed(zone = bangkok, now = deadline))
        assertFalse(LodgingDetails().cancellationHasPassed(zone = bangkok))
    }

    // MARK: - Editing rooms

    @Test
    fun editedRoomReplacesItsRow() {
        var draft = TripItemDraft(tripId = "t", kind = TripItemKind.Lodging, item = decodeItem(importedHotel))
        val room = draft.rooms[0].copy(roomNumber = " 1201 ")
        draft = draft.updateRoom(room)
        assertEquals(2, draft.rooms.size)
        assertEquals("1201", draft.rooms[0].roomNumber)
        assertEquals("Room 1201", draft.rooms[0].title(number = 1))
        assertEquals("1 King Bed, City view", draft.rooms[0].roomType)
    }

    @Test
    fun newRoomIsAddedUnlessBlank() {
        var draft = TripItemDraft(tripId = "t", kind = TripItemKind.Lodging)
        draft = draft.updateRoom(LodgingRoom.new())
        val blank = LodgingRoom.new().copy(roomType = "   ")
        draft = draft.updateRoom(blank)
        assertTrue(draft.rooms.isEmpty())

        var room = LodgingRoom.new().copy(roomType = "Twin")
        draft = draft.updateRoom(room)
        assertEquals(1, draft.rooms.size)
        assertTrue(draft.rooms[0].isNew)
        assertEquals("Room 1", draft.rooms[0].title(number = 1))

        // Edited again, it stays one room.
        room = room.copy(roomNumber = "7")
        draft = draft.updateRoom(room)
        assertEquals(listOf<String?>("7"), draft.rooms.map { it.roomNumber })
    }

    @Test
    fun clearedTextFieldsBecomeNil() {
        var draft = TripItemDraft(tripId = "t", kind = TripItemKind.Lodging, item = decodeItem(importedHotel))
        val room = draft.rooms[1].copy(roomNumber = "")
        draft = draft.updateRoom(room)
        assertNull(draft.rooms[1].roomNumber)
        assertEquals("Room 2", draft.rooms[1].title(number = 2))
    }

    // MARK: - Room assignments

    private val roster = listOf("m1", "m2", "m3", "m4").map {
        TripMember(id = it, email = "$it@example.com", user = null, role = TripRole.Member)
    }

    /**
     * A stay with `importedHotel`'s two rooms (r1 empty, r2 holding m1), for
     * m1 and m2, on a trip of m1–m4.
     */
    private fun assigningDraft(): TripItemDraft =
        TripItemDraft(tripId = "t", kind = TripItemKind.Lodging, members = roster, item = decodeItem(importedHotel))
            .withSelectedMemberIds(setOf("m1", "m2"))

    @Test
    fun roomPopupGroupsTheTrip() {
        val draft = assigningDraft()
        val forR1 = draft.roomAssignments("r1")
        assertEquals(listOf("m2"), forR1.participants.map { it.id })
        assertEquals(listOf("m3", "m4"), forR1.others.map { it.id })
        assertEquals(listOf("m1"), forR1.inOtherRooms.map { it.id })
        assertEquals("Room 412", forR1.inOtherRooms.firstOrNull()?.roomTitle)

        // A room's own people aren't "in another room" for it.
        val forR2 = draft.roomAssignments("r2")
        assertEquals(listOf("m1", "m2"), forR2.participants.map { it.id })
        assertTrue(forR2.inOtherRooms.isEmpty())
    }

    @Test
    fun assigningSomeoneOffTheStayAddsThem() {
        var draft = assigningDraft()
        val room = draft.rooms[0].copy(occupants = listOf("m2", "m3"))
        draft = draft.updateRoom(room)
        assertEquals(listOf("m2", "m3"), draft.rooms[0].occupants)
        assertEquals(setOf("m1", "m2", "m3"), draft.selectedMemberIds)
        assertEquals(listOf("m2", "m3"), draft.roomAssignments("r2").inOtherRooms.map { it.id })
    }

    @Test
    fun blankNewRoomAddsNobody() {
        val draft = assigningDraft().updateRoom(LodgingRoom.new())
        assertEquals(2, draft.rooms.size)
        assertEquals(setOf("m1", "m2"), draft.selectedMemberIds)
    }

    @Test
    fun roomRowNamesWhosIn() {
        val you = TripMember(
            id = "m1", email = "me@example.com",
            user = User(id = "u1", email = "me@example.com", displayName = "Sun", avatarUrl = null, createdAt = Instant.now()),
            role = TripRole.Organizer,
        )
        val roster = listOf(you) + listOf("m2", "m3", "m4").map {
            TripMember(id = it, email = "$it@example.com", user = null, role = TripRole.Member)
        }
        val draft = TripItemDraft(tripId = "t", kind = TripItemKind.Lodging, members = roster)
        var room = LodgingRoom.new()
        assertNull(draft.occupantNames(room, currentUserId = "u1"))
        room = room.copy(occupants = listOf("m2", "m1"))
        // In trip order, with the signed-in user as "You".
        assertEquals("You and m2", draft.occupantNames(room, currentUserId = "u1"))
        assertEquals("Sun and m2", draft.occupantNames(room, currentUserId = null))
        room = room.copy(occupants = listOf("m1", "m2", "m3", "m4"))
        assertEquals("You, m2 and 2 others", draft.occupantNames(room, currentUserId = "u1"))
    }

    @Test
    fun removingAParticipantTakesThemOutOfTheirRoom() {
        var draft = assigningDraft()
        val room = draft.rooms[0].copy(occupants = listOf("m2"))
        draft = draft.updateRoom(room)
        assertEquals(listOf(listOf("m2"), listOf("m1")), draft.rooms.map { it.occupants })

        draft = draft.withSelectedMemberIds(setOf("m2"))
        assertEquals(listOf(listOf("m2"), emptyList()), draft.rooms.map { it.occupants })
        // Adding someone back doesn't put them back in a room.
        draft = draft.withSelectedMemberIds(setOf("m1", "m2"))
        assertEquals(listOf(listOf("m2"), emptyList()), draft.rooms.map { it.occupants })
    }

    // MARK: - Saving rooms

    private fun lodgingCreate(rooms: List<TripItemsAPI.RoomRequest>?): TripItemsAPI.CreateRequest =
        TripItemsAPI.CreateRequest(
            kind = TripItemKind.Lodging, title = "Stay", notes = null, startsAt = "2026-12-27T15:00:00",
            startTimezone = null, endsAt = null, endTimezone = null, placeId = null,
            endPlaceId = null, rooms = rooms,
        )

    @Test
    fun roomsAreSentInTheServersShape() {
        val new = LodgingRoom.new().copy(roomType = "Twin", occupants = listOf("m2"))
        val rooms = listOf(TripItemsAPI.RoomRequest(LodgingRoom(id = "r1", roomNumber = "412")), TripItemsAPI.RoomRequest(new))
        val sent = (encode(lodgingCreate(rooms))["rooms"] as JsonArray).map { it.jsonObject }
        // A stored room keeps its id; a new one goes without its local one.
        assertEquals(JsonPrimitive("r1"), sent[0]["id"])
        assertEquals(JsonPrimitive("412"), sent[0]["room_number"])
        assertNull(sent[0]["room_type"])
        assertNull(sent[1]["id"])
        assertEquals(JsonPrimitive("Twin"), sent[1]["room_type"])
        assertEquals(listOf(JsonPrimitive("m2")), sent[1]["occupants"]?.jsonArray?.toList())
        assertNull(encode(lodgingCreate(null))["rooms"])
    }

    @Test
    fun updateSendsRoomsOnlyWhenAsked() {
        val create = lodgingCreate(emptyList())
        assertNull(encode(TripItemsAPI.UpdateRequest(create))["rooms"])
        // Sent empty, it clears them.
        assertTrue(encode(TripItemsAPI.UpdateRequest(create, sendsRooms = true))["rooms"] is JsonArray)
        assertEquals(true, (encode(TripItemsAPI.UpdateRequest(create, sendsRooms = true))["rooms"] as? JsonArray)?.isEmpty())
    }

    @Test
    fun untouchedRoomsAreLeftOut() {
        var draft = assigningDraft()
        assertFalse(draft.roomsChanged)
        // Reopening a room and closing it without changes doesn't count.
        draft = draft.updateRoom(draft.rooms[0])
        assertFalse(draft.roomsChanged)
    }

    @Test
    fun editedRoomsAreSentWhole() {
        var draft = assigningDraft()
        val room = draft.rooms[0].copy(occupants = listOf("m2"))
        draft = draft.updateRoom(room)
        assertTrue(draft.roomsChanged)
        val sent = draft.roomsToSave!!
        assertEquals(listOf<String?>("r1", "r2"), sent.map { it.id })
        assertEquals(listOf(listOf("m2"), listOf("m1")), sent.map { it.occupants })

        // Taking someone off the stay empties their room, which is a change too.
        val other = assigningDraft().withSelectedMemberIds(setOf("m2"))
        assertTrue(other.roomsChanged)
        assertEquals(listOf(emptyList<String>(), emptyList()), other.roomsToSave?.map { it.occupants })
    }

    @Test
    fun newStaySendsRoomsOnlyWhenItHasSome() {
        var draft = TripItemDraft(tripId = "t", kind = TripItemKind.Lodging, members = roster)
        assertNull(draft.roomsToSave)
        val room = LodgingRoom.new().copy(roomNumber = "7")
        draft = draft.updateRoom(room)
        assertEquals(listOf(TripItemsAPI.RoomRequest(roomNumber = "7")), draft.roomsToSave)
        assertNull(TripItemDraft(tripId = "t", kind = TripItemKind.Activity).roomsToSave)
    }

    // MARK: - Import source

    @Test
    fun decodesImportSource() {
        val imported = decodeItem(
            """
        , "source": "email", "has_source": true, "edited_at": "2026-11-05T19:41:17.885521Z",
          "edited_by": {"id": "u1", "email": "sam@example.com", "display_name": "Sam", "avatar_url": null,
                        "created_at": "2026-10-01T00:00:00Z"}
        """,
        )
        assertEquals(TripItemSource.Email, imported.source)
        assertEquals("Imported from email", imported.source?.importLabel)
        assertTrue(imported.hasSource)
        assertNotNull(imported.editedAt)
        assertEquals("Sam", imported.editedBy?.displayName)

        val screenshot = decodeItem(""", "source": "image", "has_source": false, "edited_at": null""", kind = "transit")
        assertEquals(TripItemSource.Image, screenshot.source)
        assertFalse(screenshot.hasSource)
        assertNull(screenshot.editedAt)

        val manual = decodeItem(""", "source": "manual"""")
        assertEquals(TripItemSource.Manual, manual.source)
        assertNull(manual.source?.importLabel)
        // An older server, or a source the app doesn't know yet, leaves it unknown.
        assertNull(decodeItem("").source)
        assertNull(decodeItem(""", "source": "fax"""").source)
    }

    // MARK: - Real bookings

    /** A stay from Dec 27, 3 PM to Dec 29, 11 AM, as edited. */
    private fun bookedDraft(type: String = "hotel"): TripItemDraft {
        // Wall clocks are floating UTC dates.
        val item = decodeItem(""", "lodging": {"property_type": "$type"}""")
            .copy(endsAtLocal = Instant.parse("2026-12-29T11:00:00Z"))
        return TripItemDraft(tripId = "t", kind = TripItemKind.Lodging, item = item)
    }

    @Test
    fun movingABookedStaysDatesAsksFirst() {
        var draft = bookedDraft()
        assertFalse(draft.changesBookedDates)

        // A new time on the same day doesn't ask.
        draft = draft.copy(startsAt = draft.startsAt + 2.hours)
        assertFalse(draft.changesBookedDates)

        // A day later does, for check-in or check-out.
        draft = draft.copy(startsAt = draft.startsAt + 1.days)
        assertTrue(draft.changesBookedDates)
        var checkOut = bookedDraft()
        checkOut = checkOut.copy(endsAt = checkOut.endsAt + 1.days)
        assertTrue(checkOut.changesBookedDates)
        val cleared = bookedDraft().copy(endsAt = null)
        assertTrue(cleared.changesBookedDates)
    }

    @Test
    fun otherStaysAndNewOnesDontAsk() {
        var other = bookedDraft(type = "other")
        other = other.copy(startsAt = other.startsAt + 1.days)
        assertFalse(other.changesBookedDates)
        assertFalse(other.deletesBookedStay)

        // Changing a stay to Other while moving it doesn't ask either.
        var retyped = bookedDraft().copy(propertyType = LodgingType.Other)
        retyped = retyped.copy(startsAt = retyped.startsAt + 1.days)
        assertFalse(retyped.changesBookedDates)

        var new = TripItemDraft(tripId = "t", kind = TripItemKind.Lodging)
        new = new.copy(startsAt = new.startsAt + 1.days)
        assertFalse(new.changesBookedDates)
        assertFalse(new.deletesBookedStay)

        val activity = decodeItem("", kind = "activity")
        assertFalse(TripItemDraft(tripId = "t", kind = TripItemKind.Activity, item = activity).deletesBookedStay)
    }

    @Test
    fun deletingABookedStayAsksFirst() {
        assertTrue(bookedDraft().deletesBookedStay)
        // A stay with no type set still has a booking as far as we know.
        val untyped = decodeItem("")
        assertTrue(TripItemDraft(tripId = "t", kind = TripItemKind.Lodging, item = untyped).deletesBookedStay)
    }

    @Test
    fun changingABookedStaysRoomCountAsksFirst() {
        var draft = assigningDraft()
        assertFalse(draft.changesBookedRoomCount)

        // Who's in which room isn't the property's business.
        val room = draft.rooms[0].copy(occupants = listOf("m2"), roomNumber = "9")
        draft = draft.updateRoom(room)
        assertFalse(draft.changesBookedRoomCount)

        // Adding or dropping a room is.
        val added = LodgingRoom.new().copy(roomType = "Twin")
        draft = draft.updateRoom(added)
        assertTrue(draft.changesBookedRoomCount)
        var dropped = assigningDraft()
        dropped = dropped.copy(rooms = dropped.rooms.dropLast(1))
        assertTrue(dropped.changesBookedRoomCount)

        val other = assigningDraft().copy(propertyType = LodgingType.Other).updateRoom(added)
        assertFalse(other.changesBookedRoomCount)
        assertFalse(TripItemDraft(tripId = "t", kind = TripItemKind.Lodging).changesBookedRoomCount)
    }

    @Test
    fun bookingPopupSaysWhatChanged() {
        assertNull(BookingChangePopupChange.edit(changingDates = false, rooms = false))
        val rooms = BookingChangePopupChange.Edit(dates = false, rooms = true)
        assertEquals("Change the number of rooms?", rooms.question)
        assertTrue(rooms.explanation.contains("new number of rooms has already been confirmed"))
        val both = BookingChangePopupChange.Edit(dates = true, rooms = true)
        assertEquals("Change this stay's dates and rooms?", both.question)
        assertTrue(both.explanation.contains("new dates and rooms have already been confirmed"))
        val dates = BookingChangePopupChange.Edit(dates = true, rooms = false)
        assertEquals("Change this stay's dates?", dates.question)
        assertEquals("Changed with Property", dates.confirmTitle)
    }

    @Test
    fun deletingARoomKeepsItsPeopleOnTheStay() {
        val draft = assigningDraft().removeRoom(id = "r2")
        assertEquals(listOf("r1"), draft.rooms.map { it.id })
        assertEquals(setOf("m1", "m2"), draft.selectedMemberIds)
        assertEquals(listOf("m1", "m2"), draft.roomAssignments("r1").participants.map { it.id })
        // One fewer room is a change to the booking, and sent.
        assertTrue(draft.changesBookedRoomCount)
        assertEquals(listOf<String?>("r1"), draft.roomsToSave?.map { it.id })
    }
}
