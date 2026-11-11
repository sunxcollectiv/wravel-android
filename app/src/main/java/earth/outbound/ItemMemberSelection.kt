//
//  ItemMemberSelection.kt
//  outbound
//

package earth.outbound

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Person
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Who a trip item is for, in terms of the trip's members: how a selection is seeded
 * from an item, summarized on a row or card, and turned into the backend's
 * `participants`. Shared by the item form and the itinerary cards.
 */
data class ItemMemberSelection(
    /** Everyone on the trip. */
    val members: List<TripMember>,
    /** The signed-in user's id. */
    val currentUserId: String?,
) {
    /** The signed-in user's row on this trip, if they're on it. */
    val currentMember: TripMember?
        get() = members.firstOrNull { it.user?.id != null && it.user?.id == currentUserId }

    /**
     * Whether the signed-in user is one of the item's participants. Nil when that can't
     * be told: no roster, or the user isn't on the trip.
     */
    fun includesCurrentUser(item: TripItem): Boolean? {
        val currentMember = currentMember ?: return null
        return item.participants.any { it.member.id == currentMember.id }
    }

    /** A new item starts with everyone; an existing one with exactly its participants. */
    fun initialSelection(item: TripItem?): Set<String> {
        if (item == null) return members.map { it.id }.toSet()
        return item.participants.map { it.member.id }.toSet()
    }

    /** "Everyone", "Just you", "You and 2 others", or a head count without you. */
    fun summary(selected: Set<String>): String {
        // A trip without a loaded roster only has the user creating the item.
        if (members.isEmpty()) return "Just you"
        if (selected.isEmpty()) return "No one"
        if (selected.size == members.size && members.size > 1) return "Everyone"
        val includesYou = currentMember?.let { it.id in selected } ?: false
        val others = selected.size - (if (includesYou) 1 else 0)
        return when {
            includesYou && others == 0 -> "Just you"
            includesYou && others == 1 -> "You and 1 other"
            includesYou -> "You and $others others"
            others == 1 -> "1 person"
            else -> "$others people"
        }
    }

    /**
     * The `participants` to send: exactly the selected members, in roster order so the
     * request is stable. No status is sent, so anyone already on the item keeps their
     * answer.
     */
    fun participants(selected: Set<String>): List<TripItemsAPI.ParticipantRequest> =
        members.filter { it.id in selected }.map { TripItemsAPI.ParticipantRequest(memberId = it.id) }

    companion object {
        /**
         * The icon for how many are going: one person, or two people for two or more.
         * An empty selection (a trip without a roster) counts as one.
         */
        fun symbol(forCount: Int): ImageVector =
            if (forCount <= 1) Icons.Rounded.Person else Icons.Rounded.Group
    }
}
