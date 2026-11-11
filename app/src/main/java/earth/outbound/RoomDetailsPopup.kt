//
//  RoomDetailsPopup.kt
//  outbound
//

package earth.outbound

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import earth.outbound.auth.User
import java.time.Instant

/** The share of the fields row the room number takes. */
private const val numberShare = 0.25f
private val fieldSpacing = 10.dp

/**
 * The card for one room of a stay: its type and number side by side, each with its
 * label above it, the number taking a quarter of the width; then who's in it. Edits
 * `room` through `onRoomChange`; the lodging form puts it back on the stay when the
 * popup closes.
 *
 * Who's in it lists the stay's people first, then the rest of the trip (whom putting
 * in the room adds to the stay), then, dimmed and fixed, whoever is in another room.
 * A filled check marks someone in this room; an outlined one, another room.
 */
@Composable
fun RoomDetailsPopup(
    room: LodgingRoom,
    onRoomChange: (LodgingRoom) -> Unit,
    assignments: RoomAssignments,
    /** The signed-in user's id, so their row reads "You". */
    currentUserId: String?,
    onClose: () -> Unit,
    /**
     * Removes the room from the stay; nil for a room being added, which closing
     * without anything typed already discards.
     */
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    PopupCard(background = Color.Black, onClose = onClose, modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            // The number takes a quarter of the row, less its share of the gap.
            Row(horizontalArrangement = Arrangement.spacedBy(fieldSpacing), verticalAlignment = Alignment.Top) {
                Field(
                    label = "Room type",
                    placeholder = "e.g. King, City View",
                    // One of the room's optional text fields as a field's text, empty for nil.
                    text = room.roomType ?: "",
                    onTextChange = { onRoomChange(room.copy(roomType = it)) },
                    modifier = Modifier.weight(1f - numberShare),
                )
                // Abbreviated: "Room number" doesn't fit a quarter of the row.
                Field(
                    label = "Room No.",
                    placeholder = "412",
                    text = room.roomNumber ?: "",
                    onTextChange = { onRoomChange(room.copy(roomNumber = it)) },
                    modifier = Modifier.weight(numberShare),
                )
            }

            OccupantsSection(room = room, onRoomChange = onRoomChange, assignments = assignments, currentUserId = currentUserId)

            if (onDelete != null) {
                DeleteButton(action = onDelete)
            }
        }
    }
}

/**
 * Red text across the bottom, like the forms' Delete. No confirmation: nothing
 * is lost until the stay is saved.
 */
@Composable
private fun DeleteButton(action: () -> Unit) {
    Box(Modifier.inlineDeleteText().plainClickable(onClick = action), contentAlignment = Alignment.Center) {
        Text("Delete Room", style = inlineDeleteTextStyle)
    }
}

// MARK: - Who's in it

@Composable
private fun OccupantsSection(
    room: LodgingRoom,
    onRoomChange: (LodgingRoom) -> Unit,
    assignments: RoomAssignments,
    currentUserId: String?,
) {
    val hasAnyone = assignments.participants.isNotEmpty() || assignments.others.isNotEmpty() ||
        assignments.inOtherRooms.isNotEmpty()
    if (!hasAnyone) return

    /** Members who can go in this room, each toggled by a tap. */
    @Composable
    fun MemberList(members: List<TripMember>) {
        MemberRows {
            members.forEachIndexed { index, member ->
                if (index > 0) MemberRowDivider()
                val isIn = member.id in room.occupants
                SelectableMemberRow(
                    member = member,
                    currentUserId = currentUserId,
                    isSelected = isIn,
                    onClick = {
                        onRoomChange(
                            room.copy(occupants = if (isIn) room.occupants - member.id else room.occupants + member.id)
                        )
                    },
                )
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FieldLabel("Who's in this room")
        if (assignments.participants.isNotEmpty()) {
            MemberList(assignments.participants)
        }
        if (assignments.others.isNotEmpty()) {
            Text(
                "Assigning members below will add them to the lodging.",
                style = AppFont.footnote,
                color = Color.appForeground.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = if (assignments.participants.isEmpty()) 0.dp else 6.dp),
            )
            MemberList(assignments.others)
        }
        if (assignments.inOtherRooms.isNotEmpty()) {
            PlacedList(placed = assignments.inOtherRooms, currentUserId = currentUserId, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/**
 * Members already in another of the stay's rooms: dimmed, with that room's name
 * and an outlined check, and not tappable.
 */
@Composable
private fun PlacedList(placed: List<RoomAssignments.Placed>, currentUserId: String?, modifier: Modifier = Modifier) {
    MemberRows(modifier.alpha(0.45f)) {
        placed.forEachIndexed { index, placedMember ->
            if (index > 0) MemberRowDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {}
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MemberIdentity(
                    member = placedMember.member,
                    currentUserId = currentUserId,
                    detail = "In ${placedMember.roomTitle}",
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Rounded.CheckCircleOutline,
                    contentDescription = null,
                    modifier = Modifier.padding(start = memberRowCheckGap).size(scaledSymbolSize(AppFont.title3)),
                )
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = formLabelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** A label over its text field. */
@Composable
private fun Field(
    label: String,
    placeholder: String,
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(label)
        AppTextField(
            text = text,
            onTextChange = onTextChange,
            placeholder = placeholder,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        )
    }
}

@Preview
@Composable
private fun RoomDetailsPopupPreview() {
    val sam = TripMember(
        id = "m1",
        email = "sam@example.com",
        user = User(id = "u1", email = "sam@example.com", displayName = "Sam", avatarUrl = null, createdAt = Instant.now()),
        role = TripRole.Organizer,
    )
    val jesse = TripMember(id = "m2", email = "jesse@example.com", user = null, role = TripRole.Member)
    val alex = TripMember(id = "m3", email = "alex@example.com", user = null, role = TripRole.Member)
    var room by remember {
        mutableStateOf(LodgingRoom(id = "r", roomNumber = "412", roomType = "1 King Bed, City view", occupants = listOf("m1")))
    }
    AppTheme {
        Box(Modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            RoomDetailsPopup(
                room = room,
                onRoomChange = { room = it },
                assignments = RoomAssignments(
                    participants = listOf(sam),
                    others = listOf(jesse),
                    inOtherRooms = listOf(RoomAssignments.Placed(member = alex, roomTitle = "Room 2")),
                ),
                currentUserId = "u1",
                onClose = {},
            )
        }
    }
}
