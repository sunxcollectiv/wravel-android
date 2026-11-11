//
//  TripItemDraftTripTests.kt
//  outboundTests
//

package earth.outbound

import earth.outbound.auth.User
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A new item saves to the trip its start date falls in, among the user's trips in a
 * group, and keeps who's going (by email) as that trip changes.
 */
class TripItemDraftTripTests {
    private val me = User(id = "u1", email = "me@example.com", displayName = "Me", avatarUrl = null, createdAt = Instant.now())

    /** "Home": me and Jesse on Dec 7–11. "Before": me and Sam on Dec 2–4. */
    private val home: Trip
        get() = Trip(
            id = "home", title = "Home", startDate = "2026-12-07", endDate = "2026-12-11",
            createdAt = Instant.now(), updatedAt = Instant.now(),
        ).copy(
            members = listOf(
                TripMember(id = "home-me", email = "me@example.com", user = me, role = TripRole.Organizer),
                TripMember(id = "home-jesse", email = "jesse@example.com", user = null, role = TripRole.Member),
            ),
        )

    private val before: Trip
        get() = Trip(
            id = "before", title = "Before", startDate = "2026-12-02", endDate = "2026-12-04",
            createdAt = Instant.now(), updatedAt = Instant.now(),
        ).copy(
            members = listOf(
                TripMember(id = "before-me", email = "me@example.com", user = me, role = TripRole.Organizer),
                TripMember(id = "before-sam", email = "sam@example.com", user = null, role = TripRole.Member),
            ),
        )

    private val tripsByDate: Map<String, Trip>
        get() = mapOf("2026-12-03" to before, "2026-12-08" to home)

    /** Midday on a December day, on the device-zone canvas the draft uses. */
    private fun december(day: Int): Instant =
        LocalDateTime.of(2026, 12, day, 12, 0).atZone(ZoneId.systemDefault()).toInstant()

    private fun newDraft(day: Int): TripItemDraft =
        TripItemDraft(
            tripId = home.id, kind = TripItemKind.Activity, members = home.members ?: emptyList(),
            tripsByDate = tripsByDate, defaultDate = december(day),
        )

    @Test
    fun newItemGoesToTheTripItsDateFallsIn() {
        var draft = newDraft(8)
        assertEquals("home", draft.tripId)
        draft = draft.copy(startsAt = december(3))
        assertEquals("before", draft.tripId)
        assertEquals(listOf("before-me", "before-sam"), draft.members.map { it.id })
        // A date no trip owns (a gap) stays on the trip the form was opened from.
        draft = draft.copy(startsAt = december(5))
        assertEquals("home", draft.tripId)
    }

    @Test
    fun whoIsGoingFollowsByEmail() {
        var draft = newDraft(8).seedSelectedMembers(currentUserId = "u1")
        assertEquals(setOf("home-me", "home-jesse"), draft.selectedMemberIds)
        // On Before, Jesse isn't on the trip; I'm selected as my row there.
        draft = draft.copy(startsAt = december(3))
        assertEquals(setOf("before-me"), draft.selectedMemberIds)
        // Back on Home, Jesse is still picked.
        draft = draft.copy(startsAt = december(8))
        assertEquals(setOf("home-me", "home-jesse"), draft.selectedMemberIds)
    }

    @Test
    fun editedItemStaysOnItsTrip() {
        val item = TripItem.sample.copy(startsAtLocal = december(3))
        val draft = TripItemDraft(
            tripId = "home", kind = TripItemKind.Activity, members = home.members ?: emptyList(),
            tripsByDate = tripsByDate, item = item,
        ).copy(startsAt = december(3))
        assertEquals("home", draft.tripId)
    }
}
