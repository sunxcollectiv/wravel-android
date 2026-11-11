//
//  ServerContractTests.kt
//  outboundTests
//

package earth.outbound

import earth.outbound.auth.AuthAPI
import earth.outbound.auth.User
import earth.outbound.networking.APIClient
import earth.outbound.networking.APIError
import earth.outbound.networking.Endpoint
import earth.outbound.networking.MultipartFile
import earth.outbound.networking.ResponseCache
import earth.outbound.networking.orJsonNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Every endpoint the app calls, sent with the app's own request bodies and decoded
 * into its own models, against a dev server. Catches a server change that moves,
 * locks down or reshapes something the app relies on.
 *
 * Skipped unless `OUTBOUND_SERVER_URL` names a server that answers (the app's own
 * debug base URL is the emulator's view of this machine, no use to a test run on it):
 *
 *     OUTBOUND_SERVER_URL=http://localhost:4830 ./gradlew :app:testDebugUnitTest --tests 'earth.outbound.ServerContractTests'
 *
 * Each test registers throwaway accounts (`contract-…@example.com`) and deletes the
 * trips it creates.
 */
class ServerContractTests {
    private val cache = ResponseCache(File(System.getProperty("java.io.tmpdir"), "ServerContractTests-${UUID.randomUUID()}"))

    @Before
    fun requireDevServer() {
        assumeTrue("No dev server: set OUTBOUND_SERVER_URL to one that's running", DevServer.isUp)
    }

    private fun client(token: String? = null): APIClient {
        val client = APIClient(baseURL = checkNotNull(DevServer.baseURL), session = OkHttpClient(), cache = cache)
        client.token = token
        return client
    }

    private class Account(
        val api: APIClient,
        val user: User,
        val password: String,
    )

    private suspend fun register(name: String = "Contract"): Account {
        val email = "contract-${UUID.randomUUID()}@example.com"
        val password = "contract-${UUID.randomUUID()}"
        val session = client().send<AuthAPI.Session>(
            AuthAPI.register(email = email, password = password, displayName = name)
        )
        assertEquals(email, session.user.email)
        assertTrue(session.expiresAt.isAfter(Instant.now()))
        return Account(api = client(token = session.token), user = session.user, password = password)
    }

    private fun status(error: Throwable?): Int? = when (error) {
        is APIError.Unauthorized -> 401
        is APIError.Server -> error.status
        else -> null
    }

    /** What `request` fails with; fails the test if it goes through. */
    private suspend fun failure(request: suspend () -> Unit): Throwable {
        try {
            request()
        } catch (error: Exception) {
            return error
        }
        throw AssertionError("Expected the request to be turned down")
    }

    /** Deletes what a test made, whether or not it got that far. Best-effort. */
    private suspend fun cleanUp(api: APIClient, endpoint: Endpoint) {
        try {
            api.sendIgnoringResponse(endpoint)
        } catch (_: Exception) {
        }
    }

    /** A trip from Mar 1–4 next year in Tokyo time, with `others` on it as members. */
    private suspend fun makeTrip(owner: Account, others: List<Account> = emptyList(), areaIds: List<String>? = null): Trip {
        val year = LocalDate.now().year + 1
        val body = TripsAPI.CreateRequest(
            title = "Contract trip",
            description = "Made by ServerContractTests".orJsonNull(),
            startDate = "$year-03-01",
            endDate = "$year-03-04",
            timezone = "Asia/Tokyo",
            areaIds = areaIds,
            members = others.map { TripsAPI.MemberRequest(email = it.user.email) },
        )
        return owner.api.send<Trip>(TripsAPI.create(body))
    }

    private fun wallClock(trip: Trip, day: Int, time: String): String {
        val parts = trip.startDate.split("-")
        return "${parts[0]}-${parts[1]}-${"%02d".format(parts[2].toInt() + day)}T$time"
    }

    // MARK: - Auth

    @Test
    fun registerLoginMeAndLogout() = runBlocking {
        val account = register("Contract Auth")

        val me = account.api.send<User>(AuthAPI.me)
        assertEquals(account.user, me)

        val login = client().send<AuthAPI.Session>(
            AuthAPI.login(email = account.user.email, password = account.password)
        )
        assertEquals(account.user.id, login.user.id)

        // A wrong password is the server's message, not "session expired".
        val wrongPassword = failure {
            client().send<AuthAPI.Session>(AuthAPI.login(email = account.user.email, password = "wrong"))
        }
        assertEquals(401, status(wrongPassword))
        assertTrue(wrongPassword is APIError.Server)

        val fresh = client(token = login.token)
        fresh.sendIgnoringResponse(AuthAPI.logout)
        assertEquals(401, status(failure { fresh.send<User>(AuthAPI.me) }))
        // Logging out one device leaves the other signed in.
        account.api.send<User>(AuthAPI.me)
        Unit
    }

    // MARK: - Trips, days, members

    /**
     * The rate tables, current and by key, and that a quote names a schedule the app
     * can load and that prices it the same way the server did.
     */
    @Test
    fun pricingSchedules() = runBlocking {
        val current = client().send<PricingSchedule>(PricingAPI.current)
        assertTrue(current.key.isNotEmpty())
        assertEquals(1, current.tripBrackets.firstOrNull()?.firstDay)
        assertNull(current.tripBrackets.lastOrNull()?.lastDay)
        assertEquals(current, client().send<PricingSchedule>(PricingAPI.schedule(key = current.key)))

        val owner = register("Contract Pricing")
        val body = TripsAPI.CreateRequest(title = "Contract quote", description = JsonNull, startDate = "2027-03-01", endDate = "2027-03-07")
        val quote = owner.api.send<TripQuote>(TripsAPI.quote(body))
        assertEquals(current.key, quote.schedule)
        // A new trip has nothing paid yet, so the charge is its whole price.
        assertEquals(quote.priceCents, current.priceCents(days = quote.days, members = quote.members))
        assertEquals(quote.tripLines, current.breakdown(days = quote.days))

        // With extra people, priced by the member-day: 3 beyond the included ones.
        val emails = (1..(current.includedMembers + 2)).map { "contract-${UUID.randomUUID()}-$it@example.com" }
        val crowded = body.copy(members = emails.map { TripsAPI.MemberRequest(email = it) })
        val crowdedQuote = owner.api.send<TripQuote>(TripsAPI.quote(crowded))
        assertEquals(3, crowdedQuote.extraMembers)
        assertEquals(crowdedQuote.priceCents, current.priceCents(days = crowdedQuote.days, members = crowdedQuote.members))
        val memberDays = current.memberDays(days = crowdedQuote.days, members = crowdedQuote.members)
        assertEquals(crowdedQuote.memberLines, current.memberBreakdown(memberDays = memberDays))
    }

    @Test
    fun tripLifecycle() = runBlocking {
        val owner = register("Contract Owner")
        val friend = register("Contract Friend")

        val areas = owner.api.send<List<Area>>(AreasAPI.search("Tokyo", limit = 5))
        val trip = makeTrip(owner, others = listOf(friend), areaIds = areas.firstOrNull()?.let { listOf(it.id) })
        try {
            assertEquals(owner.user.id, trip.ownerId)
            val members = checkNotNull(trip.members)
            assertTrue(members.any { it.user?.id == owner.user.id && it.role == TripRole.Organizer })
            assertTrue(members.any { it.email == friend.user.email && it.role == TripRole.Member })
            areas.firstOrNull()?.let { area ->
                assertEquals(listOf(area.id), trip.areas?.map { it.id })
                val missing = trip.areasMissingPhotos ?: emptyList()
                if (missing.isNotEmpty()) {
                    owner.api.send<AreasAPI.RefreshPhotosResponse>(AreasAPI.refreshPhotos(areaIds = missing))
                }
            }

            // Both see it in their list; the list is saved for offline.
            val ownerList = owner.api.send<List<Trip>>(TripsAPI.list)
            assertTrue(ownerList.any { it.id == trip.id })
            assertEquals(true, owner.api.cached<List<Trip>>(TripsAPI.list)?.any { it.id == trip.id })
            val friendList = friend.api.send<List<Trip>>(TripsAPI.list)
            assertTrue(friendList.any { it.id == trip.id })

            // Edit: rename, keep areas, and swap the roster for nobody else.
            var edit = TripsAPI.CreateRequest(
                title = "Contract trip (edited)", description = JsonNull,
                startDate = trip.startDate, endDate = trip.endDate, members = emptyList(), notifyMembers = true,
            )
            val edited = owner.api.send<Trip>(TripsAPI.update(id = trip.id, body = edit))
            assertEquals("Contract trip (edited)", edited.title)
            assertNull(edited.description)
            assertEquals(trip.areas?.map { it.id }, edited.areas?.map { it.id })
            assertEquals(listOf(owner.user.id), edited.members?.map { it.user?.id })

            // Add the friend back, then they can't delete the trip, only the owner can.
            edit = edit.copy(
                members = listOf(TripsAPI.MemberRequest(email = friend.user.email, role = TripRole.Member)),
                notifyMembers = null,
            )
            owner.api.send<Trip>(TripsAPI.update(id = trip.id, body = edit))
            val refused = status(failure { friend.api.sendIgnoringResponse(TripsAPI.delete(id = trip.id)) })
            assertTrue(refused == 403 || refused == 404)

            // Days come with the trip, seeded with its zone; organizers can edit one.
            val days = owner.api.send<List<TripDay>>(TripDaysAPI.list(tripId = trip.id))
            assertEquals(listOf(1, 2, 3, 4), days.map { it.dayNumber })
            assertTrue(days.all { it.timezones == listOf("Asia/Tokyo") })
            val day = owner.api.send<TripDay>(
                TripDaysAPI.update(
                    tripId = trip.id, date = days[1].date,
                    body = TripDaysAPI.UpdateRequest(name = "Day trip", timezones = listOf("Asia/Tokyo", "UTC")),
                )
            )
            assertEquals("Day trip", day.name)
            assertEquals(listOf("Asia/Tokyo", "UTC"), day.timezones)

            owner.api.sendIgnoringResponse(TripsAPI.delete(id = trip.id))
            val after = owner.api.send<List<Trip>>(TripsAPI.list)
            assertFalse(after.any { it.id == trip.id })
        } finally {
            cleanUp(owner.api, TripsAPI.delete(id = trip.id))
        }
    }

    /**
     * Segments: a member adds a trip of their own next to one they're on, and the two
     * join one group, whatever their dates.
     */
    @Test
    fun tripSegments() = runBlocking {
        val owner = register("Contract Owner")
        val friend = register("Contract Friend")
        val trip = makeTrip(owner, others = listOf(friend))
        val added = mutableListOf<Trip>()
        try {
            assertNull(trip.groupId)
            assertNull(trip.extendsTripId)
            val year = trip.startDate.take(4)

            // The friend adds the two days after the trip (Mar 5–6) as their own segment.
            val segment = friend.api.send<Trip>(
                TripsAPI.create(
                    TripsAPI.CreateRequest(
                        title = "Contract segment", description = JsonNull,
                        startDate = "$year-03-05", endDate = "$year-03-06",
                        timezone = "Asia/Tokyo", extendsTripId = trip.id,
                    )
                )
            )
            added.add(segment)
            assertEquals(friend.user.id, segment.ownerId)
            assertEquals(trip.id, segment.extendsTripId)
            val groupId = checkNotNull(segment.groupId)
            assertEquals(listOf(friend.user.id), segment.members?.map { it.user?.id })

            // The trip it was added to joins the same group.
            val ownerList = owner.api.send<List<Trip>>(TripsAPI.list)
            assertEquals(groupId, ownerList.firstOrNull { it.id == trip.id }?.groupId)

            // A segment needn't touch the trip: one with days between them joins the group too.
            val gap = friend.api.send<Trip>(
                TripsAPI.create(
                    TripsAPI.CreateRequest(
                        title = "Contract gap", description = JsonNull,
                        startDate = "$year-03-08", endDate = "$year-03-09", extendsTripId = trip.id,
                    )
                )
            )
            added.add(gap)
            assertEquals(groupId, gap.groupId)

            // The friend's merged itinerary keeps the empty day between their two segments,
            // marked as a gap.
            val itinerary = friend.api.send<Itinerary>(TripsAPI.itinerary(tripId = gap.id))
            assertEquals(listOf("$year-03-07"), itinerary.days.filter { it.isGap }.map { it.date })
            assertEquals(listOf(trip.id, segment.id, gap.id), itinerary.trips.map { it.id })

            // Only people on the trip can add a segment to it.
            val stranger = register("Contract Stranger")
            val refused = failure {
                stranger.api.send<Trip>(
                    TripsAPI.create(
                        TripsAPI.CreateRequest(
                            title = "Contract stranger", description = JsonNull,
                            startDate = "$year-02-27", endDate = "$year-02-28", extendsTripId = trip.id,
                        )
                    )
                )
            }
            assertEquals(404, status(refused))
        } finally {
            added.forEach { cleanUp(friend.api, TripsAPI.delete(id = it.id)) }
            cleanUp(owner.api, TripsAPI.delete(id = trip.id))
        }
    }

    // MARK: - Items and itinerary

    @Test
    fun itemsAndItinerary() = runBlocking {
        val owner = register("Contract Owner")
        val friend = register("Contract Friend")
        val trip = makeTrip(owner, others = listOf(friend))
        try {
            val me = checkNotNull(trip.members?.firstOrNull { it.user?.id == owner.user.id })
            val them = checkNotNull(trip.members?.firstOrNull { it.email == friend.user.email })

            // An activity for both, with a zone and an end.
            val activity = owner.api.send<TripItem>(
                TripItemsAPI.create(
                    tripId = trip.id,
                    body = TripItemsAPI.CreateRequest(
                        kind = TripItemKind.Activity, title = "Sumo", notes = "Ryogoku",
                        startsAt = wallClock(trip, day = 0, time = "10:00:00"), startTimezone = "Asia/Tokyo",
                        endsAt = wallClock(trip, day = 0, time = "12:00:00"), endTimezone = "Asia/Tokyo",
                        placeId = null, endPlaceId = null,
                        participants = listOf(
                            TripItemsAPI.ParticipantRequest(memberId = me.id, status = RsvpStatus.Going),
                            TripItemsAPI.ParticipantRequest(memberId = them.id),
                        ),
                        notifyMembers = true,
                    ),
                )
            )
            assertEquals("Sumo", activity.displayTitle)
            assertEquals("Asia/Tokyo", activity.startTimezone?.id)
            assertNotNull(activity.startsAtUtc)
            assertEquals(setOf(me.id, them.id), activity.participants.map { it.member.id }.toSet())
            assertEquals(owner.user.id, activity.createdBy?.id)

            // A zoneless, closed lodging across two nights, just for the owner.
            val lodging = owner.api.send<TripItem>(
                TripItemsAPI.create(
                    tripId = trip.id,
                    body = TripItemsAPI.CreateRequest(
                        kind = TripItemKind.Lodging, visibility = TripItemVisibility.Closed, title = "Ryokan", notes = "Room 4",
                        startsAt = wallClock(trip, day = 0, time = "15:00:00"), startTimezone = null,
                        endsAt = wallClock(trip, day = 2, time = "11:00:00"), endTimezone = null,
                        placeId = null, endPlaceId = null,
                    ),
                )
            )
            assertEquals(TripItemVisibility.Closed, lodging.visibility)
            assertNull(lodging.startTimezone)
            assertEquals(listOf(me.id), lodging.participants.map { it.member.id })

            // A private two-leg flight sent as segments; the server derives its span.
            val flight = owner.api.send<TripItem>(
                TripItemsAPI.create(
                    tripId = trip.id,
                    body = TripItemsAPI.CreateRequest(
                        kind = TripItemKind.Transit, visibility = TripItemVisibility.Private, title = "Home", notes = null,
                        startsAt = null, startTimezone = null, endsAt = null, endTimezone = null, placeId = null, endPlaceId = null,
                        segments = listOf(
                            TripItemsAPI.SegmentRequest(
                                startsAt = wallClock(trip, day = 3, time = "09:00:00"), startTimezone = "Asia/Tokyo",
                                endsAt = wallClock(trip, day = 3, time = "11:00:00"), endTimezone = "Asia/Seoul",
                                placeId = null, endPlaceId = null, transitMode = TransitMode.Flight,
                                carrier = "Korean Air", flightNumber = "KE 704",
                            ),
                            TripItemsAPI.SegmentRequest(
                                startsAt = wallClock(trip, day = 3, time = "13:00:00"), startTimezone = "Asia/Seoul",
                                endsAt = wallClock(trip, day = 3, time = "18:00:00"), endTimezone = "Asia/Seoul",
                                placeId = null, endPlaceId = null, transitMode = TransitMode.Train,
                                confirmationNumber = "KTX1",
                            ),
                        ),
                        participants = listOf(TripItemsAPI.ParticipantRequest(memberId = me.id)),
                    ),
                )
            )
            val positions = flight.segments.map { it.position }
            assertTrue(positions == listOf(0, 1) || positions == listOf(1, 2))
            assertEquals("Korean Air KE 704", flight.segments.firstOrNull()?.label)
            assertEquals(TransitMode.Mixed, flight.transitMode)

            // The friend sees the closed lodging redacted, and the private flight (just
            // the owner) not at all.
            val theirItems = friend.api.send<List<TripItem>>(TripItemsAPI.list(tripId = trip.id))
            assertEquals(true, theirItems.firstOrNull { it.id == lodging.id }?.redacted)
            assertNull(theirItems.firstOrNull { it.id == lodging.id }?.notes)
            assertTrue(theirItems.any { it.id == activity.id })
            assertFalse(theirItems.any { it.id == flight.id })
            val myItems = owner.api.send<List<TripItem>>(TripItemsAPI.list(tripId = trip.id))
            assertEquals(setOf(activity.id, lodging.id, flight.id), myItems.map { it.id }.toSet())

            // Edits: drop the activity's end and title (it has no place, so keep a title),
            // retitle, and make it just for the owner.
            var update = TripItemsAPI.UpdateRequest(
                TripItemsAPI.CreateRequest(
                    kind = TripItemKind.Activity, title = "Sumo (morning)", notes = "Ryogoku",
                    startsAt = wallClock(trip, day = 0, time = "09:30:00"), startTimezone = "Asia/Tokyo",
                    endsAt = null, endTimezone = null, placeId = null, endPlaceId = null,
                    participants = listOf(TripItemsAPI.ParticipantRequest(memberId = me.id)),
                ),
                nullsTitleAndPlace = true,
            )
            val edited = owner.api.send<TripItem>(TripItemsAPI.update(tripId = trip.id, itemId = activity.id, body = update))
            assertEquals("Sumo (morning)", edited.title)
            assertNull(edited.endsAtLocal)
            assertEquals(listOf(me.id), edited.participants.map { it.member.id })

            // Swap the flight's legs for one.
            update = TripItemsAPI.UpdateRequest(
                TripItemsAPI.CreateRequest(
                    kind = TripItemKind.Transit, title = "Home", notes = null,
                    startsAt = null, startTimezone = null, endsAt = null, endTimezone = null, placeId = null, endPlaceId = null,
                    segments = listOf(
                        TripItemsAPI.SegmentRequest(
                            startsAt = wallClock(trip, day = 3, time = "08:00:00"), startTimezone = "Asia/Tokyo",
                            endsAt = wallClock(trip, day = 3, time = "12:00:00"), endTimezone = "UTC",
                            placeId = null, endPlaceId = null, transitMode = TransitMode.Flight,
                        )
                    ),
                )
            )
            val rerouted = owner.api.send<TripItem>(TripItemsAPI.update(tripId = trip.id, itemId = flight.id, body = update))
            assertEquals(1, rerouted.segments.size)
            assertEquals(TransitMode.Flight, rerouted.transitMode)

            // The itinerary lays it all out by day, and is saved for offline.
            val itinerary = owner.api.send<Itinerary>(TripsAPI.itinerary(tripId = trip.id))
            assertEquals(trip.id, itinerary.trip.id)
            assertEquals(listOf(1, 2, 3, 4), itinerary.days.map { it.dayNumber })
            assertTrue(itinerary.days[0].entries.any { it.item.id == activity.id })
            assertTrue(itinerary.days[1].lodging.any { it.item.id == lodging.id && it.phase == LodgingPhase.Staying })
            assertTrue(itinerary.days[3].entries.any { it.item.id == flight.id })
            assertEquals(4, owner.api.cached<Itinerary>(TripsAPI.itinerary(tripId = trip.id))?.days?.size)
            val theirs = friend.api.send<Itinerary>(TripsAPI.itinerary(tripId = trip.id))
            assertFalse(theirs.days.flatMap { it.entries }.any { it.item.id == flight.id })

            owner.api.sendIgnoringResponse(TripItemsAPI.delete(tripId = trip.id, itemId = lodging.id))
            val remaining = owner.api.send<List<TripItem>>(TripItemsAPI.list(tripId = trip.id))
            assertFalse(remaining.any { it.id == lodging.id })

            // Someone not on the trip can't see it.
            val stranger = register("Contract Stranger")
            val refused = status(failure { stranger.api.send<List<TripItem>>(TripItemsAPI.list(tripId = trip.id)) })
            assertTrue(refused == 403 || refused == 404)
        } finally {
            cleanUp(owner.api, TripsAPI.delete(id = trip.id))
        }
    }

    // MARK: - Lodging details and rooms

    @Test
    fun lodgingDetailsAndRooms() = runBlocking {
        val owner = register("Contract Host")
        val friend = register("Contract Roommate")
        val trip = makeTrip(owner, others = listOf(friend))
        try {
            val me = checkNotNull(trip.members?.firstOrNull { it.user?.id == owner.user.id })
            val them = checkNotNull(trip.members?.firstOrNull { it.email == friend.user.email })

            /** The stay as the form sends it, with whatever a step changes. */
            fun stayRequest(lodging: LodgingDetails? = null, rooms: List<TripItemsAPI.RoomRequest>? = null, participants: List<TripItemsAPI.ParticipantRequest>? = null) =
                TripItemsAPI.CreateRequest(
                    kind = TripItemKind.Lodging, title = "Hotel Gracery", notes = null,
                    startsAt = wallClock(trip, day = 0, time = "15:00:00"), startTimezone = "Asia/Tokyo",
                    endsAt = wallClock(trip, day = 2, time = "11:00:00"), endTimezone = "Asia/Tokyo",
                    placeId = null, endPlaceId = null,
                    lodging = lodging, rooms = rooms, participants = participants,
                )

            // A hotel with two rooms; the friend isn't on the stay, so rooming them adds them.
            val stay = owner.api.send<TripItem>(
                TripItemsAPI.create(
                    tripId = trip.id,
                    body = stayRequest(
                        lodging = LodgingDetails(propertyType = LodgingType.Hotel, phone = "+81 3 6833 2489"),
                        rooms = listOf(
                            TripItemsAPI.RoomRequest(roomNumber = "1204", roomType = "Double", occupants = listOf(me.id)),
                            TripItemsAPI.RoomRequest(roomType = "Twin", occupants = listOf(them.id)),
                        ),
                        participants = listOf(TripItemsAPI.ParticipantRequest(memberId = me.id)),
                    ),
                )
            )
            assertEquals(LodgingType.Hotel, stay.lodging?.propertyType)
            assertEquals("+81 3 6833 2489", stay.lodging?.phone)
            assertEquals(listOf("1204", null), stay.rooms.map { it.roomNumber })
            assertEquals(listOf(listOf(me.id), listOf(them.id)), stay.rooms.map { it.occupants })
            assertEquals(setOf(me.id, them.id), stay.participants.map { it.member.id }.toSet())

            // The app's own edit: change the type only; the rooms aren't sent, so stay put.
            val draft = TripItemDraft(tripId = trip.id, kind = TripItemKind.Lodging, members = trip.members ?: emptyList(), item = stay)
                .seedSelectedMembers(currentUserId = owner.user.id)
                .copy(propertyType = LodgingType.VacationRental)
            assertFalse(draft.roomsChanged)
            var update = TripItemsAPI.UpdateRequest(
                stayRequest(lodging = draft.lodgingToSave, rooms = draft.roomsToSave),
                sendsLodging = draft.lodgingChanged, sendsRooms = draft.roomsChanged,
            )
            val retyped = owner.api.send<TripItem>(TripItemsAPI.update(tripId = trip.id, itemId = stay.id, body = update))
            assertEquals(LodgingType.VacationRental, retyped.lodging?.propertyType)
            assertEquals("+81 3 6833 2489", retyped.lodging?.phone)
            assertEquals(stay.rooms, retyped.rooms)

            // Rooms edited in the app: the first keeps its id and takes the friend too, the
            // second goes, and a new one (sent without its local id) is added.
            var roomer = TripItemDraft(tripId = trip.id, kind = TripItemKind.Lodging, members = trip.members ?: emptyList(), item = retyped)
                .seedSelectedMembers(currentUserId = owner.user.id)
            roomer = roomer.updateRoom(roomer.rooms[0].copy(occupants = listOf(me.id, them.id)))
            roomer = roomer.copy(rooms = roomer.rooms.filterIndexed { index, _ -> index != 1 })
            val added = LodgingRoom.new().copy(label = "Spare")
            roomer = roomer.updateRoom(added)
            assertTrue(roomer.roomsChanged)
            update = TripItemsAPI.UpdateRequest(stayRequest(rooms = roomer.roomsToSave), sendsRooms = true)
            val reroomed = owner.api.send<TripItem>(TripItemsAPI.update(tripId = trip.id, itemId = stay.id, body = update))
            assertEquals(2, reroomed.rooms.size)
            assertEquals(stay.rooms[0].id, reroomed.rooms[0].id)
            assertEquals(listOf(me.id, them.id), reroomed.rooms[0].occupants)
            assertEquals("Spare", reroomed.rooms[1].label)
            assertNotEquals(added.id, reroomed.rooms[1].id)

            // An empty list clears them.
            update = update.copy(rooms = emptyList())
            val cleared = owner.api.send<TripItem>(TripItemsAPI.update(tripId = trip.id, itemId = stay.id, body = update))
            assertTrue(cleared.rooms.isEmpty())
        } finally {
            cleanUp(owner.api, TripsAPI.delete(id = trip.id))
        }
    }

    // MARK: - Places and areas

    @Test
    fun placesAndAreas() = runBlocking {
        val account = register()

        val areas = account.api.send<List<Area>>(AreasAPI.search("a", limit = 3))
        assertTrue(areas.size <= 3)
        val cities = account.api.send<List<Area>>(AreasAPI.search("a", kind = "city", limit = 3))
        assertTrue(cities.all { it.kind == "city" })

        // Unknown ids are left out rather than failing the batch.
        val places = account.api.send<PlacesAPI.RefreshResponse>(
            PlacesAPI.refresh(placeIds = listOf(UUID.randomUUID().toString()))
        )
        assertTrue(places.places.isEmpty())
        val photos = account.api.send<AreasAPI.RefreshPhotosResponse>(
            AreasAPI.refreshPhotos(areaIds = listOf(UUID.randomUUID().toString()))
        )
        assertTrue(photos.areas.isEmpty())

        // Registering a place reaches the endpoint (Google may be unconfigured on dev,
        // but a user token must not be turned away).
        try {
            val place = account.api.send<Place>(PlacesAPI.create(googlePlaceId = "ChIJ51cu8IcbXWARiRtXIothAS4"))
            assertEquals("ChIJ51cu8IcbXWARiRtXIothAS4", place.googlePlaceId)
        } catch (error: Exception) {
            assertFalse("POST /places: $error", (status(error) ?: 0) in listOf(401, 403, 404, 405))
        }
    }

    // MARK: - Notifications and push

    @Test
    fun notificationsInbox() = runBlocking {
        val owner = register("Contract Owner")
        val friend = register("Contract Friend")
        val trip = makeTrip(owner, others = listOf(friend))
        try {
            val them = checkNotNull(trip.members?.firstOrNull { it.email == friend.user.email })
            // Something the friend hears about.
            owner.api.send<TripItem>(
                TripItemsAPI.create(
                    tripId = trip.id,
                    body = TripItemsAPI.CreateRequest(
                        kind = TripItemKind.Activity, title = "Karaoke", notes = null,
                        startsAt = wallClock(trip, day = 1, time = "20:00:00"), startTimezone = "Asia/Tokyo",
                        endsAt = null, endTimezone = null, placeId = null, endPlaceId = null,
                        participants = listOf(TripItemsAPI.ParticipantRequest(memberId = them.id)), notifyMembers = true,
                    ),
                )
            )

            val page = friend.api.send<NotificationsAPI.Page>(NotificationsAPI.list(limit = 5))
            assertTrue(page.items.size <= 5)
            val count = friend.api.send<NotificationsAPI.UnreadCount>(NotificationsAPI.unreadCount)
            assertTrue(count.count == page.items.count { !it.isRead } || page.nextCursor != null)

            page.nextCursor?.let { next ->
                friend.api.send<NotificationsAPI.Page>(NotificationsAPI.list(before = next, limit = 5))
            }

            val first = page.items.firstOrNull()
            if (first != null) {
                val read = friend.api.send<AppNotification>(NotificationsAPI.markRead(id = first.id))
                assertTrue(read.isRead)
                val unread = friend.api.send<AppNotification>(NotificationsAPI.markUnread(id = first.id))
                assertFalse(unread.isRead)
                friend.api.sendIgnoringResponse(NotificationsAPI.dismiss(id = first.id))
                val after = friend.api.send<NotificationsAPI.Page>(NotificationsAPI.list(limit = 50))
                assertFalse(after.items.any { it.id == first.id })
            } else {
                fail("Expected the friend to be notified about the trip or its item")
            }

            val cleared = friend.api.send<NotificationsAPI.UnreadCount>(NotificationsAPI.markAllRead)
            assertEquals(0, cleared.count)
        } finally {
            cleanUp(owner.api, TripsAPI.delete(id = trip.id))
        }
    }

    @Test
    fun pushDeviceRegistration() = runBlocking {
        val account = register()
        val device = account.api.send<PushDevicesAPI.Device>(
            PushDevicesAPI.register(
                PushDevicesAPI.RegisterRequest(
                    token = "ab".repeat(32), appVersion = "1.0", locale = "en_US", timezone = "Asia/Tokyo",
                )
            )
        )
        assertTrue(device.id.isNotEmpty())
        account.api.sendIgnoringResponse(PushDevicesAPI.unregister(id = device.id))
    }

    // MARK: - Screenshot import

    /**
     * Only checks the user-facing route is still there and open to a signed-in user
     * (an empty upload is turned away before any extraction runs).
     */
    @Test
    fun screenshotProcessIsOpenToUsers() = runBlocking {
        val account = register()
        val empty = MultipartFile(field = "file", filename = "empty.png", contentType = "image/png", data = ByteArray(0))
        assertEquals(422, status(failure { account.api.send<ImageImportResult>(ReceivedImagesAPI.process(empty)) }))
        assertEquals(401, status(failure { client().send<ImageImportResult>(ReceivedImagesAPI.process(empty)) }))
    }
}

/** Whether a dev server was named and answers at all, checked once before the suite runs. */
private object DevServer {
    /** Where the dev server is, from `OUTBOUND_SERVER_URL`; null when it isn't set. */
    val baseURL: HttpUrl? = System.getenv("OUTBOUND_SERVER_URL")?.toHttpUrlOrNull()

    val isUp: Boolean by lazy {
        val url = baseURL?.newBuilder()?.addPathSegment("health")?.build() ?: return@lazy false
        val session = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build()
        try {
            session.newCall(Request.Builder().url(url).build()).execute().use { it.code == 200 }
        } catch (_: Exception) {
            false
        }
    }
}
