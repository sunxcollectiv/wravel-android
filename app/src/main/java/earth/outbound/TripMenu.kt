//
//  TripMenu.kt
//  outbound
//

package earth.outbound

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bed
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The dropdown under the itinerary header's more button: the trip's dates up top, then
 * adding items (by hand, one row per kind, or from a screenshot), then the trip's
 * actions. Drawn by hand rather than as a system menu, so it can take the app's
 * styling and grow richer rows than a menu allows.
 */
@Composable
fun TripMenu(
    trip: Trip,
    /** Adds an item by hand, opening the form for the kind picked. */
    onAddItem: (TripItemKind) -> Unit,
    onImportScreenshot: () -> Unit,
    onCopyTripsEmail: () -> Unit,
    onEditTrip: () -> Unit,
    onShare: () -> Unit,
    /** Null hides the row, for anyone but the trip's owner. */
    onDeleteTrip: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    ForegroundStyle(Color.White) {
        Column(
            modifier
                .width(260.dp)
                .shadow(18.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(shape)
                // Dark gray, the lodging popup's card color, all but opaque so the cards
                // behind don't show through.
                .background(Color.white(0.15f).copy(alpha = 0.97f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
                .semantics { isTraversalGroup = true }
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(trip.title, style = AppFont.subheadline.semibold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(trip.dateRangeText, style = AppFont.caption, color = Color.White.copy(alpha = 0.6f))
            }

            Divider()
            SectionHeading("Itinerary")
            MenuRow("Add Activity", Icons.Rounded.Add) { onAddItem(TripItemKind.Activity) }
            MenuRow("Add Lodging", Icons.Rounded.Bed) { onAddItem(TripItemKind.Lodging) }
            MenuRow("Add Transit", Icons.Rounded.Flight) { onAddItem(TripItemKind.Transit) }
            // Offline, rows that save straight away are disabled; the forms the others open
            // stay editable and only block saving.
            MenuRow("Import from Screenshot", Icons.Outlined.Photo, modifier = Modifier.requiresConnection(), action = onImportScreenshot)
            // Dotted rather than solid: forwarding is another way into the itinerary, so
            // it stays grouped with the rows above.
            DottedDivider()
            ForwardEmailRow(onCopyTripsEmail)
            Divider()
            MenuRow("Edit Trip", Icons.Rounded.Edit, action = onEditTrip)
            MenuRow("Share", Icons.Rounded.IosShare, action = onShare)
            if (onDeleteTrip != null) {
                Divider()
                MenuRow(
                    "Delete Trip",
                    Icons.Rounded.DeleteOutline,
                    tint = Color(red = 1f, green = 0.45f, blue = 0.42f),
                    modifier = Modifier.requiresConnection(),
                    action = onDeleteTrip,
                )
            }
        }
    }
}

/** A small caption naming the rows below it. */
@Composable
private fun SectionHeading(title: String) {
    Text(
        title.uppercase(),
        style = AppFont.caption2.semibold.copy(letterSpacing = 1.sp),
        color = Color.White.copy(alpha = 0.55f),
        modifier = Modifier
            .padding(horizontal = 14.dp)
            .padding(top = 10.dp, bottom = 2.dp)
            .semantics { heading() },
    )
}

/**
 * A reminder that booking emails can be forwarded to the trips inbox; tapping copies
 * the address.
 */
@Composable
private fun ForwardEmailRow(onCopyTripsEmail: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tripMenuRow(onCopyTripsEmail)
            .clearAndSetSemantics { contentDescription = "Copy ${AddLodgingPopupMetrics.tripsEmail}" }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(Icons.Rounded.MailOutline)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Or forward emails to", style = AppFont.caption, color = Color.White.copy(alpha = 0.6f))
            Text(AddLodgingPopupMetrics.tripsEmail, style = AppFont.subheadline.medium)
        }
        Icon(
            Icons.Rounded.ContentCopy,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.12f)))
}

/** A lighter break within a section, inset to line up with the row text. */
@Composable
private fun DottedDivider() {
    DashedLine(
        color = Color.White.copy(alpha = 0.25f),
        dash = listOf(2.dp, 3.dp),
        modifier = Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(1.dp),
    )
}

@Composable
private fun MenuRow(
    title: String,
    systemImage: ImageVector,
    tint: Color = Color.White,
    modifier: Modifier = Modifier,
    action: () -> Unit,
) {
    ForegroundStyle(tint) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .tripMenuRow(action)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowIcon(systemImage)
            Text(title, style = AppFont.subheadline.medium, modifier = Modifier.weight(1f))
        }
    }
}

/** A row's icon, centered in a column as wide for every row so their titles line up. */
@Composable
private fun RowIcon(icon: ImageVector) {
    Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    }
}

/**
 * A horizontal line across the middle of its frame, drawn in dashes: `dash` gives the
 * lengths of a dash and the gap after it, in turn.
 */
@Composable
fun DashedLine(color: Color, lineWidth: Dp = 1.dp, dash: List<Dp>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2),
            end = Offset(size.width, size.height / 2),
            strokeWidth = lineWidth.toPx(),
            pathEffect = PathEffect.dashPathEffect(dash.map { it.toPx() }.toFloatArray()),
        )
    }
}

/** Makes a row tappable, highlighting it while it's pressed, as a system menu does. */
private fun Modifier.tripMenuRow(onClick: () -> Unit): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    this
        .background(Color.White.copy(alpha = if (isPressed) 0.12f else 0f))
        .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = onClick)
}

@Preview
@Composable
private fun TripMenuPreview() {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.TopEnd) {
        TripMenu(
            trip = Trip.sample,
            onAddItem = {},
            onImportScreenshot = {},
            onCopyTripsEmail = {},
            onEditTrip = {},
            onShare = {},
            onDeleteTrip = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
