//
//  ItemMemberSelectionTests.kt
//  outboundTests
//

package earth.outbound

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Person
import earth.outbound.auth.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ItemMemberSelectionTests {
    private val me = User(id = "u1", email = "me@example.com", displayName = "Me", avatarUrl = null, createdAt = Instant.now())
    private val members: List<TripMember>
        get() = listOf(
            TripMember(id = "m1", email = "me@example.com", user = me, role = TripRole.Organizer),
            TripMember(id = "m2", email = "jesse@example.com", user = null, role = TripRole.Member),
            TripMember(id = "m3", email = "sam@example.com", user = null, role = TripRole.Member),
        )

    private fun item(participants: List<Pair<String, RsvpStatus?>>): TripItem =
        TripItem.sample.copy(
            participants = participants.map { (id, status) ->
                TripItemParticipant(member = members.first { it.id == id }, status = status)
            },
        )

    @Test
    fun newItemStartsWithEveryone() {
        val selection = ItemMemberSelection(members = members, currentUserId = "u1")
        assertEquals(setOf("m1", "m2", "m3"), selection.initialSelection(null))
    }

    @Test
    fun existingItemStartsWithExactlyItsParticipants() {
        val selection = ItemMemberSelection(members = members, currentUserId = "u1")
        // Answers don't matter: a declined or unanswered participant is still on the item.
        val existing = item(listOf("m2" to RsvpStatus.Going, "m3" to RsvpStatus.Declined))
        assertEquals(setOf("m2", "m3"), selection.initialSelection(existing))
        assertEquals(setOf("m1"), selection.initialSelection(item(listOf("m1" to null))))
        assertTrue(selection.initialSelection(item(emptyList())).isEmpty())
    }

    @Test
    fun knowsWhetherYouAreOnAnItem() {
        val selection = ItemMemberSelection(members = members, currentUserId = "u1")
        assertEquals(true, selection.includesCurrentUser(item(listOf("m1" to null))))
        assertEquals(false, selection.includesCurrentUser(item(listOf("m2" to RsvpStatus.Going))))
        // Without a roster there's no telling.
        assertNull(ItemMemberSelection(members = emptyList(), currentUserId = "u1").includesCurrentUser(item(emptyList())))
    }

    @Test
    fun summaries() {
        val selection = ItemMemberSelection(members = members, currentUserId = "u1")
        assertEquals("Everyone", selection.summary(setOf("m1", "m2", "m3")))
        assertEquals("Just you", selection.summary(setOf("m1")))
        assertEquals("You and 1 other", selection.summary(setOf("m1", "m2")))
        assertEquals("2 people", selection.summary(setOf("m2", "m3")))
        assertEquals("Just you", ItemMemberSelection(members = emptyList(), currentUserId = "u1").summary(emptySet()))
    }

    @Test
    fun iconFollowsTheHeadCount() {
        assertEquals(Icons.Rounded.Person, ItemMemberSelection.symbol(forCount = 0))
        assertEquals(Icons.Rounded.Person, ItemMemberSelection.symbol(forCount = 1))
        assertEquals(Icons.Rounded.Group, ItemMemberSelection.symbol(forCount = 2))
        assertEquals(Icons.Rounded.Group, ItemMemberSelection.symbol(forCount = 5))
    }

    @Test
    fun participantsAreExactlyTheSelectionInRosterOrderWithoutStatus() {
        val selection = ItemMemberSelection(members = members, currentUserId = "u1")
        assertEquals(
            listOf(TripItemsAPI.ParticipantRequest(memberId = "m1"), TripItemsAPI.ParticipantRequest(memberId = "m3")),
            selection.participants(setOf("m3", "m1")),
        )
        assertTrue(selection.participants(emptySet()).isEmpty())
    }
}
