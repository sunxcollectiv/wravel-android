//
//  ItemMembersPopup.kt
//  outbound
//

package earth.outbound

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import earth.outbound.auth.User
import java.time.Instant

/**
 * A centered card for choosing which of the trip's members an item is for, shown
 * over the item form. Tapping a member toggles them in `selectedIds`.
 */
@Composable
fun ItemMembersPopup(
    members: List<TripMember>,
    /** The signed-in user's id, so their row reads "You". */
    currentUserId: String?,
    selectedIds: Set<String>,
    onSelectedIdsChange: (Set<String>) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PopupCard(onClose = onClose, modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PopupHeaderLabel("Who's Going", Icons.Rounded.Group)

            if (members.isEmpty()) {
                Text(
                    "Add people to the trip to include them here.",
                    style = AppFont.footnote,
                    color = Color.appForeground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            } else {
                // The card hugs the list until it needs to scroll.
                Box(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    MemberRows {
                        members.forEachIndexed { index, member ->
                            val isSelected = member.id in selectedIds
                            SelectableMemberRow(
                                member = member,
                                currentUserId = currentUserId,
                                isSelected = isSelected,
                                onClick = {
                                    onSelectedIdsChange(if (isSelected) selectedIds - member.id else selectedIds + member.id)
                                },
                            )
                            if (index < members.size - 1) {
                                MemberRowDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The rounded, faintly filled list the member rows sit in. */
@Composable
internal fun MemberRows(modifier: Modifier = Modifier, rows: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Color.appForeground.copy(alpha = 0.08f), RoundedCornerShape(appFieldCornerRadius))
    ) {
        rows()
    }
}

/** The hairline between two members' rows. */
@Composable
internal fun MemberRowDivider() {
    Hairline(opacity = 0.15f)
}

/** The least room between a member and the check at the end of their row. */
internal val memberRowCheckGap = 32.dp

/** A member with a check at the end, filled while they're picked; tapping toggles them. */
@Composable
internal fun SelectableMemberRow(
    member: TripMember,
    currentUserId: String?,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val checkColor by animateColorAsState(
        if (isSelected) Color.appForeground else Color.appForeground.copy(alpha = 0.35f),
        AppAnimation.snappy(0.15),
        label = "check",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .plainClickable(onClick = onClick)
            .semantics { selected = isSelected }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MemberIdentity(member = member, currentUserId = currentUserId, modifier = Modifier.weight(1f))
        Icon(
            if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = null,
            tint = checkColor,
            modifier = Modifier.padding(start = memberRowCheckGap).size(scaledSymbolSize(AppFont.title3)),
        )
    }
}

/**
 * A member as the member lists show them: an initial in a circle, their name (or
 * email) with "(You)" for the signed-in user, and their email under a name.
 * `detail` replaces the email line, e.g. with the room they're in.
 */
@Composable
fun MemberIdentity(
    member: TripMember,
    currentUserId: String?,
    detail: String? = null,
    modifier: Modifier = Modifier,
) {
    val user = member.user
    val isYou = user != null && user.id == currentUserId
    val name = user?.displayName ?: member.email
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        MemberInitial(name)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (isYou) "$name (You)" else name,
                style = AppFont.subheadline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Members without an account only have their email, already shown.
            (detail ?: member.email.takeIf { user != null })?.let { line ->
                Text(
                    line,
                    style = AppFont.caption,
                    color = Color.appForeground.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The stand-in for a member's picture in the member lists: the first letter of their
 * name (or email) in a faint circle.
 */
@Composable
internal fun MemberInitial(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier.size(32.dp).background(Color.appForeground.copy(alpha = 0.18f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            (name.firstOrNull { it.isLetter() } ?: '?').uppercase(),
            style = AppFont.subheadline.semibold,
            maxLines = 1,
        )
    }
}

@Preview
@Composable
private fun ItemMembersPopupPreview() {
    var selected by remember { mutableStateOf(setOf("m1")) }
    val members = listOf(
        TripMember(
            id = "m1",
            email = "sam@example.com",
            user = User(id = "u1", email = "sam@example.com", displayName = "Sam", avatarUrl = null, createdAt = Instant.now()),
            role = TripRole.Organizer,
        ),
        TripMember(id = "m2", email = "jesse@example.com", user = null, role = TripRole.Member),
    )
    AppTheme {
        Box(Modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            ItemMembersPopup(
                members = members,
                currentUserId = "u1",
                selectedIds = selected,
                onSelectedIdsChange = { selected = it },
                onClose = {},
            )
        }
    }
}
