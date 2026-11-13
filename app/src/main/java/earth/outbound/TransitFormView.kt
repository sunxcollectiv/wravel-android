//
//  TransitFormView.kt
//  outbound
//

package earth.outbound

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import java.util.UUID
import kotlin.math.roundToInt

/** The leg and end whose date popup is open. */
private data class EditingTime(
    val legID: UUID,
    val field: DateTimePopupField,
)

/** A leg as the form lists it: one of the draft's, or one just removed that's still fading out. */
private data class ShownLeg(
    val leg: TransitLeg,
    /** The leg's number, or nil for a lone leg. */
    val number: Int?,
    val isLeaving: Boolean = false,
    /** For a leg that's leaving: the legs listed before it, which it stays after. */
    val precedingIDs: Set<UUID> = emptySet(),
)

/** How legs come and go, and the rest of the form makes room. */
private fun <T> legAnimation() = AppAnimation.snappy<T>(0.25)

/** The gap between legs, the rule between them, and the button under them. */
private val legSpacing = 28.dp

/**
 * The form for adding or editing transit: one or more legs, each with how it
 * travels, an origin and a destination, and when it departs and arrives (each end
 * in its own zone, so a leg can cross zones). No title: the server names it from
 * its route. `updateDraft` applies a change to the draft, as for `TripItemFormScaffold`.
 */
@Composable
fun TransitFormView(
    draft: TripItemDraft,
    updateDraft: ((TripItemDraft) -> TripItemDraft) -> Unit,
    onSaved: (TripItem) -> Unit = {},
    onDeleted: () -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    /** The leg and end whose date popup is open, if any. */
    var editingTime by remember { mutableStateOf<EditingTime?>(null) }
    /** The legs the form opened with; ones added later fade in. */
    val openingLegIDs = remember { draft.legs.map { it.id }.toSet() }
    /** Legs taken off the draft that are still fading out of the form. */
    var leavingLegs by remember { mutableStateOf(listOf<ShownLeg>()) }

    // The draft's legs, numbered only once there's more than one, with those just
    // removed still in their places.
    val shownLegs = buildList {
        draft.legs.forEachIndexed { index, leg ->
            add(ShownLeg(leg = leg, number = if (draft.legs.size > 1) index + 1 else null))
        }
        for (leaving in leavingLegs) {
            add(indexOfLast { it.leg.id in leaving.precedingIDs } + 1, leaving)
        }
    }

    /** Applies a change to the leg with `id` as it is now, if it's still there. */
    fun updateLeg(id: UUID, change: (TransitLeg) -> TransitLeg) {
        updateDraft { current ->
            current.legs.firstOrNull { it.id == id }?.let { current.updateLeg(change(it)) } ?: current
        }
    }

    TripItemFormScaffold(
        draft = draft,
        updateDraft = updateDraft,
        timeLabels = TripItemTimeLabels(start = "Departs", end = "Arrives", missingStart = "Add a departure time."),
        onSaved = onSaved,
        onDeleted = onDeleted,
        onDismiss = onDismiss,
        modifier = modifier,
        places = {
            // Each leg brings the gap under it, and the rule over it, so they come and go with it.
            Column {
                shownLegs.forEachIndexed { index, shown ->
                    val leg = shown.leg
                    key(leg.id) {
                        Collapsible(
                            isPresent = !shown.isLeaving,
                            startsHidden = leg.id !in openingLegIDs,
                            onGone = { leavingLegs = leavingLegs.filterNot { it.leg.id == leg.id } },
                            // Earlier legs over later ones, so their floating suggestions show.
                            modifier = Modifier.zIndex((shownLegs.size - index).toFloat()),
                        ) {
                            Column {
                                // A faint rule between legs.
                                Collapsible(isPresent = shownLegs.take(index).any { !it.isLeaving }) {
                                    Box(
                                        Modifier
                                            .padding(bottom = legSpacing)
                                            .fillMaxWidth()
                                            .height(1.dp)
                                            .background(Color.appForeground.copy(alpha = 0.12f))
                                    )
                                }
                                TransitLegSection(
                                    leg = leg,
                                    updateLeg = { change -> updateLeg(leg.id, change) },
                                    number = shown.number,
                                    onDelete = {
                                        if (!shown.isLeaving && draft.legs.size > 1) {
                                            leavingLegs = leavingLegs + shown.copy(
                                                isLeaving = true,
                                                precedingIDs = shownLegs.take(index).map { it.leg.id }.toSet(),
                                            )
                                            updateDraft { it.removeLeg(leg) }
                                        }
                                    },
                                    onEditTime = { field ->
                                        if (!shown.isLeaving) editingTime = EditingTime(legID = leg.id, field = field)
                                    },
                                    modifier = Modifier.padding(bottom = legSpacing),
                                )
                            }
                        }
                    }
                }

                AddLegButton(
                    onClick = { updateDraft { it.addLeg() } },
                    // Twice the form's usual gap before the notes below.
                    modifier = Modifier.padding(bottom = 20.dp),
                )
            }
        },
    )

    // The date popup for the tapped leg's departure or arrival, over a dimmed form.
    val shownEditingTime = lastPresented(editingTime)
    val editedLeg = shownEditingTime?.let { editing -> draft.legs.firstOrNull { it.id == editing.legID } }
    DimmedPopup(isPresented = editingTime != null && editedLeg != null, onClose = { editingTime = null }) {
        if (shownEditingTime != null && editedLeg != null) {
            val legID = editedLeg.id
            // Opened anew on another leg or end, rather than carrying over what was picked.
            key(shownEditingTime) {
                DateTimePopup(
                    field = shownEditingTime.field,
                    startPlaceholder = "Departs",
                    endPlaceholder = "Arrives",
                    tripDates = draft.tripDates,
                    start = editedLeg.startsAt,
                    onStartChange = { date -> updateLeg(legID) { it.copy(startsAt = date) } },
                    end = editedLeg.endsAt,
                    onEndChange = { date -> updateLeg(legID) { it.copy(endsAt = date) } },
                    startZone = editedLeg.startZone,
                    onStartZoneChange = { zone -> updateLeg(legID) { it.copy(startZone = zone) } },
                    endZone = editedLeg.endZone,
                    onEndZoneChange = { zone -> updateLeg(legID) { it.copy(endZone = zone) } },
                    viewedDay = draft.viewedDay,
                    onClose = { editingTime = null },
                )
            }
        }
    }
}

@Composable
private fun AddLegButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .plainClickable(onClick = onClick)
            .dashedOutline(Color.appForeground.copy(alpha = 0.5f), cornerRadius = appFieldCornerRadius, dash = 5.dp)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        SymbolLabel(
            "Add transit leg",
            icon = Icons.Rounded.Add,
            style = AppFont.subheadline.medium,
            color = Color.appForeground.copy(alpha = 0.8f),
        )
    }
}

/**
 * Shows `content` while `isPresent`: it grows to its height and fades in as it
 * arrives, and the reverse as it goes (after which `onGone` is called), like a view
 * added or removed in the middle of a stack on iOS. Once it's all there it isn't
 * clipped, so what's inside can draw past its edge.
 */
@Composable
private fun Collapsible(
    isPresent: Boolean,
    modifier: Modifier = Modifier,
    /** Whether it arrives animated when first shown, rather than being there already. */
    startsHidden: Boolean = false,
    onGone: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    /** How much of it shows, from none (0) to all (1). */
    val shown = remember { Animatable(if (isPresent && !startsHidden) 1f else 0f) }
    val currentOnGone by rememberUpdatedState(onGone)
    LaunchedEffect(isPresent) {
        shown.animateTo(if (isPresent) 1f else 0f, legAnimation())
        if (!isPresent) currentOnGone()
    }

    val isGone by remember { derivedStateOf { shown.value <= 0f } }
    if (!isPresent && isGone) return

    Box(
        modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val height = (placeable.height * shown.value.coerceIn(0f, 1f)).roundToInt()
                layout(placeable.width, height) { placeable.place(0, 0) }
            }
            // While it's fading, the layer also keeps it to the height it has so far.
            .graphicsLayer { alpha = shown.value.coerceIn(0f, 1f) }
    ) {
        content()
    }
}

/**
 * One leg of a transit: a "Segment N" tag with a delete button when there are
 * several, then the ways to travel, the origin and destination side by side, and
 * when it departs and arrives. `updateLeg` applies a change to the leg.
 */
@Composable
private fun TransitLegSection(
    leg: TransitLeg,
    updateLeg: ((TransitLeg) -> TransitLeg) -> Unit,
    /** The leg's number, shown with a delete button; nil for a lone leg. */
    number: Int?,
    onDelete: () -> Unit,
    onEditTime: (DateTimePopupField) -> Unit,
    modifier: Modifier = Modifier,
) {
    /** The number the tag shows, kept while the tag goes away. */
    val shownNumber = lastPresented(number)

    Column(modifier) {
        Collapsible(isPresent = number != null) {
            if (shownNumber != null) {
                Header(number = shownNumber, onDelete = onDelete, modifier = Modifier.padding(bottom = 16.dp))
            }
        }
        LegFields(leg = leg, updateLeg = updateLeg, onEditTime = onEditTime)
    }
}

/** A leg's ways to travel, its origin and destination, and its times. */
@Composable
private fun LegFields(
    leg: TransitLeg,
    updateLeg: ((TransitLeg) -> TransitLeg) -> Unit,
    onEditTime: (DateTimePopupField) -> Unit,
) {
    /** Width of the origin/destination row, so each field's suggestions can span it. */
    var rowWidth by remember { mutableStateOf<Dp?>(null) }
    val density = LocalDensity.current
    // A flight's ends are picked from our own airports rather than all of Google.
    val placeSource = if (leg.transitMode == TransitMode.Flight) PlaceSearchSource.Airports else PlaceSearchSource.Google

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ModePicker(transitMode = leg.transitMode, onTransitModeChange = { mode -> updateLeg { it.copy(transitMode = mode) } })

        // Side by side, with suggestions floating over the form across the whole
        // row (from the origin's left edge, or the destination's right).
        Row(
            modifier = Modifier
                // Over the times below, so the suggestions cover them.
                .zIndex(1f)
                .onSizeChanged { rowWidth = with(density) { it.width.toDp() } },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            TripItemPlaceField(
                label = "Origin",
                placeholder = "Search",
                text = leg.startPlaceText,
                onTextChange = { text -> updateLeg { it.copy(startPlaceText = text) } },
                place = leg.startPlace,
                onPlaceChange = { place -> updateLeg { it.copy(startPlace = place) } },
                onTimeZoneChange = { zone -> updateLeg { it.withStartPlaceZone(zone) } },
                floatingResults = PlaceSearchFieldFloatingResults(
                    width = rowWidth,
                    edge = PlaceSearchFieldFloatingResults.Edge.Leading,
                ),
                source = placeSource,
                modifier = Modifier.weight(1f),
            )
            TripItemPlaceField(
                label = "Destination",
                placeholder = "Search",
                text = leg.endPlaceText,
                onTextChange = { text -> updateLeg { it.copy(endPlaceText = text) } },
                place = leg.endPlace,
                onPlaceChange = { place -> updateLeg { it.copy(endPlace = place) } },
                onTimeZoneChange = { zone -> updateLeg { it.withEndPlaceZone(zone) } },
                floatingResults = PlaceSearchFieldFloatingResults(
                    width = rowWidth,
                    edge = PlaceSearchFieldFloatingResults.Edge.Trailing,
                ),
                source = placeSource,
                modifier = Modifier.weight(1f),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TripItemTimeBlock(
                title = "Departs",
                placeholder = "Add start",
                date = leg.startsAt,
                onDateChange = { date -> updateLeg { it.copy(startsAt = date) } },
                zone = leg.startZone,
                onZoneChange = { zone -> updateLeg { it.copy(startZone = zone) } },
                onTap = { onEditTime(DateTimePopupField.Start) },
                modifier = Modifier.weight(1f),
            )
            TripItemTimeBlock(
                title = "Arrives",
                placeholder = "Add end",
                date = leg.endsAt,
                onDateChange = { date -> updateLeg { it.copy(endsAt = date) } },
                zone = leg.endZone,
                onZoneChange = { zone -> updateLeg { it.copy(endZone = zone) } },
                onTap = { onEditTime(DateTimePopupField.End) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** The leg's number as a tag, with an inline delete on the right. */
@Composable
private fun Header(number: Int, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Segment $number".uppercase(),
            style = AppFont.caption.semibold.copy(letterSpacing = 1.sp),
            color = Color.appForeground.copy(alpha = 0.8f),
            modifier = Modifier
                .background(Color.appForeground.copy(alpha = 0.12f), CircleShape)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
        Spacer(Modifier.weight(1f))
        Text(
            "Delete",
            style = AppFont.subheadline.medium,
            color = Color.appError,
            modifier = Modifier
                .plainClickable(onClick = onDelete)
                .semantics { contentDescription = "Delete segment $number" },
        )
    }
}

/** A row of icons, one per way to travel; the picked one is lit. */
@Composable
private fun ModePicker(transitMode: TransitMode?, onTransitModeChange: (TransitMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (mode in TransitMode.pickable) {
            val isPicked = transitMode == mode
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
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .plainClickable { onTransitModeChange(mode) }
                    .background(background, RoundedCornerShape(appFieldCornerRadius))
                    .clearAndSetSemantics {
                        contentDescription = mode.label
                        role = Role.Button
                        selected = isPicked
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    mode.systemImage,
                    contentDescription = null,
                    tint = foreground,
                    modifier = Modifier.size(scaledSymbolSize(AppFont.system(17f, FontWeight.SemiBold))),
                )
            }
        }
    }
}
