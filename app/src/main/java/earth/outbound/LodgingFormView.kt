//
//  LodgingFormView.kt
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Person
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import earth.outbound.auth.LocalAuthStore

/**
 * The form for adding or editing a lodging: what kind of place it is (hotel,
 * vacation rental, …), where you're staying, as a picked place or a name taken as
 * typed (which becomes its title), then check-in and check-out.
 * `updateDraft` applies a change to the draft, as for `TripItemFormScaffold`.
 */
@Composable
fun LodgingFormView(
    draft: TripItemDraft,
    updateDraft: ((TripItemDraft) -> TripItemDraft) -> Unit,
    onSaved: (TripItem) -> Unit = {},
    onDeleted: () -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentUserId = LocalAuthStore.current.currentUser?.id

    var showingRoomDetails by remember { mutableStateOf(false) }
    /** The room the popup edits: a copy of one of the stay's, or a new blank one. */
    var openedRoom by remember { mutableStateOf(LodgingRoom.new()) }
    /**
     * Whether the opened room was already on the stay, so it can be deleted; not so
     * for one being added.
     */
    var openedRoomIsOnStay by remember { mutableStateOf(false) }

    /** Opens the popup on `room`, or on a blank new one to add. */
    fun openRoomDetails(room: LodgingRoom?) {
        openedRoom = room ?: LodgingRoom.new()
        openedRoomIsOnStay = draft.rooms.any { it.id == openedRoom.id }
        showingRoomDetails = true
    }

    /** Closes the popup, putting what was typed back on the stay's rooms. */
    fun closeRoomDetails() {
        if (!showingRoomDetails) return
        val room = openedRoom
        updateDraft { it.updateRoom(room) }
        showingRoomDetails = false
    }

    /** Closes the popup, taking its room off the stay. */
    fun deleteOpenedRoom() {
        val id = openedRoom.id
        updateDraft { it.removeRoom(id = id) }
        showingRoomDetails = false
    }

    TripItemFormScaffold(
        draft = draft,
        updateDraft = updateDraft,
        timeLabels = TripItemTimeLabels(start = "Check-in", end = "Check-out", missingStart = "Add a check-in time."),
        onSaved = onSaved,
        onDeleted = onDeleted,
        onDismiss = onDismiss,
        modifier = modifier,
        places = {
            PropertyTypePicker(
                propertyType = draft.propertyType,
                onPropertyTypeChange = { type -> updateDraft { it.copy(propertyType = type) } },
            )
            TripItemPlaceField(
                placeholder = "Search a place",
                text = draft.startPlaceText,
                onTextChange = { text -> updateDraft { it.copy(startPlaceText = text) } },
                place = draft.startPlace,
                onPlaceChange = { place -> updateDraft { it.copy(startPlace = place) } },
                onTimeZoneChange = { zone -> updateDraft { it.withStartPlaceZone(zone) } },
                typedEntry = draft.typedName,
                onTypedEntryChange = { name -> updateDraft { it.copy(typedName = name) } },
            )
        },
        afterTimes = {
            RoomsSection(
                rooms = draft.rooms,
                occupantNames = { room -> draft.occupantNames(room, currentUserId = currentUserId) },
                onOpenRoom = ::openRoomDetails,
            )
        },
    )

    // The room details popup over a dimmed form; tapping outside closes it.
    DimmedPopup(isPresented = showingRoomDetails, onClose = ::closeRoomDetails) {
        RoomDetailsPopup(
            room = openedRoom,
            onRoomChange = { openedRoom = it },
            assignments = draft.roomAssignments(openedRoom.id),
            currentUserId = currentUserId,
            onClose = ::closeRoomDetails,
            // Delete Room for a room already on the stay; nil for one being added.
            onDelete = if (openedRoomIsOnStay) ::deleteOpenedRoom else null,
        )
    }
}

// MARK: - Room details

/**
 * "Room details" with "+ add room" opposite, over a full-width row per room, or
 * a dashed placeholder while the stay has none. Adding a room or tapping one
 * opens the room details popup.
 */
@Composable
private fun RoomsSection(
    rooms: List<LodgingRoom>,
    /** Who's in a room, for its row; nil when nobody is. */
    occupantNames: (LodgingRoom) -> String?,
    /** Opens the popup on a room, or on a blank new one for nil. */
    onOpenRoom: (LodgingRoom?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row {
            Text("Room details", style = formLabelStyle, modifier = Modifier.weight(1f).padding(end = 8.dp).alignByBaseline())
            // The plus snug against the words, rather than a label's wide gap.
            // A point under subheadline (15sp), scaling with the system's font size.
            SymbolLabel(
                "add room",
                icon = Icons.Rounded.Add,
                style = AppFont.system(14f, FontWeight.SemiBold),
                color = Color.appForeground,
                spacing = 3.dp,
                modifier = Modifier
                    .alignByBaseline()
                    .plainClickable { onOpenRoom(null) }
                    .clearAndSetSemantics {
                        contentDescription = "Add room"
                        role = Role.Button
                    },
            )
        }
        if (rooms.isEmpty()) {
            RoomsPlaceholder()
        } else {
            rooms.forEachIndexed { index, room ->
                RoomButton(room = room, number = index + 1, occupantNames = occupantNames(room), onClick = { onOpenRoom(room) })
            }
        }
    }
}

/**
 * One room, styled like the members row up top: its name with who's in it
 * underneath, and its type and guests opposite.
 */
@Composable
private fun RoomButton(room: LodgingRoom, number: Int, occupantNames: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().plainClickable(onClick = onClick).formRow(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Measured first, so the name keeps its room and the summary takes what's left.
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(room.title(number = number), maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Who's in it, under its name.
            if (occupantNames != null) {
                SymbolLabel(
                    occupantNames,
                    icon = Icons.Rounded.Person,
                    style = AppFont.caption,
                    color = Color.appForeground.copy(alpha = 0.6f),
                    maxLines = 1,
                )
            }
        }
        room.summary?.let { summary ->
            Text(
                summary,
                color = Color.appForeground.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                // Kept a second gap away from the name, on the far side of the room between them.
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
        }
    }
}

/**
 * Stands in for the rooms until the stay has one: a dashed outline the height of
 * a room row. Not a button; rooms are added from "+ add room" above it.
 */
@Composable
private fun RoomsPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .dashedOutline(Color.appForeground.copy(alpha = 0.3f), cornerRadius = appFieldCornerRadius)
            .padding(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("No room details yet", color = Color.appForeground.copy(alpha = 0.5f))
    }
}

/**
 * One tile per property type, icon over name; the picked one is lit. Tapping it
 * again clears it, since a stay needn't say.
 */
@Composable
private fun PropertyTypePicker(propertyType: LodgingType?, onPropertyTypeChange: (LodgingType?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (type in LodgingType.entries) {
            val isPicked = propertyType == type
            val foreground by animateColorAsState(
                if (isPicked) Color.Black else Color.appForeground.copy(alpha = 0.8f),
                AppAnimation.snappy(0.15),
                label = "foreground",
            )
            val background by animateColorAsState(
                if (isPicked) Color.appForeground else Color.appForeground.copy(alpha = 0.08f),
                AppAnimation.snappy(0.15),
                label = "background",
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
                    .plainClickable { onPropertyTypeChange(if (isPicked) null else type) }
                    .background(background, RoundedCornerShape(appFieldCornerRadius))
                    .clearAndSetSemantics {
                        contentDescription = type.label
                        role = Role.Button
                        selected = isPicked
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                // One height for every icon, so the names line up.
                Box(Modifier.height(20.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        type.systemImage,
                        contentDescription = null,
                        tint = foreground,
                        modifier = Modifier.size(scaledSymbolSize(AppFont.system(16f))),
                    )
                }
                ShrinkingText(
                    text = type.shortLabel,
                    style = AppFont.caption2.semibold,
                    minimumScaleFactor = 0.8f,
                    color = foreground,
                )
            }
        }
    }
}
