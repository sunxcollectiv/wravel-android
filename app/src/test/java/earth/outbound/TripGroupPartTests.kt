//
//  TripGroupPartTests.kt
//  outboundTests
//

package earth.outbound

import earth.outbound.auth.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** When the edit form says which trip in a group is being edited. */
class TripGroupPartTests {
    private fun user(id: String): User =
        User(id = id, email = "$id@example.com", displayName = id, avatarUrl = null, createdAt = Instant.now())

    private fun trip(id: String, organizers: List<String>, members: List<String> = emptyList()): Trip =
        Trip(
            id = id,
            title = id,
            startDate = "2026-12-07",
            endDate = "2026-12-11",
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        ).copy(
            members = organizers.map { TripMember(id = "$id-$it", email = "$it@example.com", user = user(it), role = TripRole.Organizer) } +
                members.map { TripMember(id = "$id-$it", email = "$it@example.com", user = user(it), role = TripRole.Member) },
        )

    @Test
    fun extensionOrganizerIsToldWhichTrip() {
        val home = trip("home", organizers = listOf("owner"), members = listOf("sun"))
        val after = trip("after", organizers = listOf("sun"))
        assertEquals(
            TripGroupPart.Primary,
            TripGroupPart.forEditing(home, primary = home, extensions = listOf(after), userId = "sun"),
        )
        assertEquals(
            TripGroupPart.Extension,
            TripGroupPart.forEditing(after, primary = home, extensions = listOf(after), userId = "sun"),
        )
    }

    @Test
    fun primaryOrganizerWithoutAnExtensionSeesNothing() {
        val home = trip("home", organizers = listOf("owner"), members = listOf("sun"))
        val after = trip("after", organizers = listOf("sun"))
        assertNull(TripGroupPart.forEditing(home, primary = home, extensions = listOf(after), userId = "owner"))
        // Nor does anyone on a trip with no extensions.
        assertNull(TripGroupPart.forEditing(home, primary = home, extensions = emptyList(), userId = "sun"))
    }

    @Test
    fun plainMemberOfAnExtensionSeesNothing() {
        val home = trip("home", organizers = listOf("owner"))
        val after = trip("after", organizers = listOf("owner"), members = listOf("sun"))
        assertNull(TripGroupPart.forEditing(home, primary = home, extensions = listOf(after), userId = "sun"))
    }
}
