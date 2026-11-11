//
//  ItineraryEntryCard.kt
//  outbound
//

package earth.outbound

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import earth.outbound.networking.PlaceDetails

/**
 * One card in a day's itinerary, styled for the spot its entry stands for:
 *
 * - a derived moment of an item (arrival, check-out) is a light compact card headed
 *   "Arrival at <place>" / "Check-out from <place>", naming the item for reference;
 * - a layover is a light compact card with no title, just its details and the stop;
 * - other transit entries (departures) are a dark compact card under the booking's
 *   title;
 * - everything else is a plain card — or, when its place has a photo, a cover card
 *   topped by that photo.
 *
 * `places` is the itinerary's single copy of each place, looked up by id, so a place
 * patched there updates every card showing it. `placeDetails` is its cache of Google
 * Place Details by google place id, preferred over the backend's place fields (which
 * are often still being enriched).
 *
 * `membersSummary` (e.g. "You and 2 others") shows in a box under the kind label on
 * the item's own card, in place of the assignee avatar. Derived moments and layovers
 * don't show it. Null keeps the avatar.
 *
 * `isCondensed` swaps the item's own card for a light one-line one — time, title and
 * members — for items others are doing that you can see but aren't on.
 */
@Composable
fun ItineraryEntryCard(
    entry: ItineraryEntry,
    places: ItineraryPlaces,
    placeDetails: Map<String, PlaceDetails>,
    membersSummary: String? = null,
    /** How many are going, which picks the box's icon. */
    membersCount: Int = 0,
    isCondensed: Boolean = false,
    /**
     * Whether the item's details are folded open beneath the card. The card's
     * bottom corners square off so the panel below reads as its continuation.
     */
    isExpanded: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // The corners square off (and round again) on the curve the details fold open on.
    val bottom by animateDpAsState(
        targetValue = if (isExpanded) 0.dp else 16.dp,
        animationSpec = TripDetailViewMetrics.expandAnimation(),
        label = "cardCorners",
    )
    val card = EntryCard(
        entry = entry,
        // The place this entry is at, from the itinerary's shared copy.
        place = places.current(entry.place),
        placeDetails = placeDetails,
        membersSummary = membersSummary,
        membersCount = membersCount,
        // Every card's outline: rounded all round, or only along the top while the
        // details panel hangs off its bottom edge.
        cardShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = bottom, bottomStart = bottom),
    )
    val item = entry.item

    Box(modifier) {
        when (entry.phase) {
            ItineraryPhase.Arrival, ItineraryPhase.CheckOut -> card.DerivedCard()
            ItineraryPhase.Layover -> card.CompactCard(
                badge = card.badge, title = null, detail = card.transitDetail, style = CompactCardStyle.light,
            )
            else -> {
                val photo = card.place?.coverPhoto
                if (isCondensed) {
                    card.CondensedCard()
                } else if (item.kind == TripItemKind.Transit) {
                    card.CompactCard(
                        badge = card.badge, title = item.displayTitle, detail = card.transitDetail, showsMembers = true,
                    )
                } else if (photo != null) {
                    card.CoverCard(photo)
                } else {
                    card.PlainCard()
                }
            }
        }
    }
}

/**
 * Colors for a compact card: the regular dark card, or a light one (90% white with
 * near-black text) for derived moments and layovers.
 */
private class CompactCardStyle(val background: Color, val foreground: Color) {
    companion object {
        val regular = CompactCardStyle(background = Color.appCard, foreground = Color.appForeground)

        /**
         * A step lighter than the details panel (`TripItemDetailContentMetrics.background`),
         * so an open light card stands apart from the details beneath it.
         */
        val light = CompactCardStyle(background = Color.white(0.9f), foreground = Color.white(0.1f))
    }
}

/** The pieces of one entry's card, drawn from what `ItineraryEntryCard` was given. */
private class EntryCard(
    val entry: ItineraryEntry,
    val place: Place?,
    val placeDetails: Map<String, PlaceDetails>,
    val membersSummary: String?,
    val membersCount: Int,
    val cardShape: Shape,
) {
    private val item: TripItem get() = entry.item
    private val time: String get() = entry.timeText(WallClock.hourMinute)

    /**
     * The label top-right of a card: the spot the entry stands for (e.g. "CHECK-IN",
     * with a stay's property type after it: "CHECK-IN · HOTEL"), else the item's first
     * tag (e.g. "RESERVED"), else its type ("HOTEL", or the kind).
     */
    val badge: String
        get() {
            val phase = entry.phase
            val label = if (phase != null) {
                listOfNotNull(phase.label, propertyTypeLabel).joinToString(" · ")
            } else {
                item.tags.firstOrNull() ?: item.typeLabel
            }
            return label.uppercase()
        }

    /** A stay's property type ("Hotel"), when it has one. */
    private val propertyTypeLabel: String?
        get() = if (item.kind == TripItemKind.Lodging) item.lodging?.propertyType?.label else null

    // MARK: - Full-width cards

    /**
     * The default full-width card: time + badge (with the members box under it) over
     * the title in a short top section, then a faint rule and the info strip with the
     * location — left off when there's nothing for the strip to show.
     */
    @Composable
    fun PlainCard() {
        Column(
            Modifier
                .padding(vertical = 6.dp)
                .fillMaxWidth()
                .clip(cardShape)
                .background(Color.appCard)
        ) {
            ForegroundStyle(Color.appForeground) {
                // Sized to its content: a fixed gap above the title, then however many
                // lines the title takes. The gap shrinks when the members box already
                // spaces the title down.
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(if (membersSummary == null) 30.dp else 12.dp),
                ) {
                    TimeAndBadge()
                    Text(item.displayTitle, style = AppFont.title3.bold)
                }
            }

            if (showsInfoStrip) {
                // Inset to line up with the text above and below it.
                Box(
                    Modifier
                        .padding(horizontal = 14.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.appForeground.copy(alpha = 0.3f))
                )

                InfoStrip()
            }
        }
    }

    /**
     * A full-width card for a place with a photo: the photo as a cover with time +
     * badge overlaid and the title across its bottom, over the info strip.
     */
    @Composable
    fun CoverCard(photo: CoverPhoto) {
        Column(Modifier.padding(vertical = 6.dp).fillMaxWidth().clip(cardShape)) {
            Box(contentAlignment = Alignment.BottomStart) {
                // The gradient shows while the photo loads, or if it fails to.
                PlacePhotoView(
                    photo = photo,
                    modifier = Modifier.fillMaxWidth().height(170.dp).clipToBounds(),
                ) {
                    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color.appCard, Color.appAccent))))
                }
                Box(
                    Modifier
                        .matchParentSize()
                        .background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f)))
                )
                ForegroundStyle(Color.White) {
                    Box(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(14.dp)) {
                        TimeAndBadge(tint = Color.White)
                    }

                    Text(
                        item.displayTitle,
                        style = AppFont.title3.bold,
                        modifier = Modifier
                            .padding(14.dp)
                            // Clear the photo credit along the cover's bottom edge.
                            .padding(bottom = 8.dp),
                    )
                }
            }

            if (showsInfoStrip) {
                InfoStrip()
            }
        }
    }

    /**
     * The row across the top of a full-width card: the time, and the badge opposite
     * with the members box (if any) right under it.
     */
    @Composable
    private fun TimeAndBadge(tint: Color = Color.appForeground) {
        Row(verticalAlignment = Alignment.Top) {
            Text(time, style = headerStyle.monospacedDigit)
            Spacer(Modifier.weight(1f))
            BadgeAndMembers(badge, tint = tint)
        }
    }

    /** A card's badge, with the members box under it when there's a summary to show. */
    @Composable
    private fun BadgeAndMembers(badge: String, tint: Color, lineLimit: Int? = null) {
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Badge(badge, lineLimit = lineLimit)
            if (membersSummary != null) {
                MembersBox(membersSummary, tint = tint)
            }
        }
    }

    @Composable
    private fun Badge(badge: String, lineLimit: Int?) {
        Text(
            badge,
            style = headerStyle.copy(letterSpacing = 1.5.sp),
            maxLines = lineLimit ?: Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
        )
    }

    /**
     * Whether a full-width card has an info strip: always while it holds the avatar,
     * else only when there's a location to show.
     */
    private val showsInfoStrip: Boolean
        get() = membersSummary == null || hasLocation(hidingName = item.displayTitle)

    /**
     * The bottom of a full-width card: the location, and an assignee avatar when the
     * members box isn't standing in for it.
     */
    @Composable
    private fun InfoStrip() {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.appCard)
                .padding(horizontal = 14.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                LocationLabel(hidingName = item.displayTitle)
            }
            if (membersSummary == null) {
                AssigneeAvatar()
            }
        }
    }

    // MARK: - Compact cards

    /**
     * An item you can see but aren't on, kept out of the way: one line with the time,
     * the title (or its kind, if it has none) and the members, in white on a mostly
     * see-through card with a white border.
     */
    @Composable
    fun CondensedCard() {
        val title = item.displayTitle.trim(' ', '\t')
        Row(
            modifier = Modifier
                .padding(vertical = 6.dp)
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.3f), cardShape)
                .border(0.5.dp, Color.White, cardShape)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(time, style = headerStyle.monospacedDigit, color = Color.White.copy(alpha = 0.75f))
            Text(
                title.ifEmpty { item.typeLabel },
                // A point under subheadline (15), scaled with the system's font size
                // like the text styles around it.
                style = AppFont.system(14f).semibold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (membersSummary != null) {
                MembersBox(membersSummary, tint = Color.white(0.85f))
            }
        }
    }

    /**
     * A derived moment of an item rather than the item itself: a light card headed by
     * where it happens, with the item named top-right for reference — or its kind,
     * when the name just repeats the place. A stay without a place falls back to its
     * own name (that's where you're leaving).
     */
    @Composable
    fun DerivedCard() {
        val name = placeName ?: if (item.kind == TripItemKind.Lodging) item.displayTitle else null
        val phase = entry.phase?.label ?: item.kind.label
        // You arrive at a place but check out from one.
        val preposition = if (entry.phase == ItineraryPhase.CheckOut) "from" else "at"
        val reference = if (item.displayTitle == name) item.typeLabel else item.displayTitle
        CompactCard(
            badge = reference.uppercase(),
            title = name?.let { "$phase $preposition $it" } ?: phase,
            detail = transitDetail,
            placeNameShown = name,
            style = CompactCardStyle.light,
        )
    }

    /**
     * The line under a transit card's title: at a layover the time on the ground and
     * onward departure, then the leg's carrier and flight number. Null when empty.
     */
    val transitDetail: String?
        get() {
            val details = mutableListOf<String>()
            if (entry.phase == ItineraryPhase.Layover) {
                entry.layoverMinutes?.let { details.add("${durationText(it)} layover") }
                entry.layoverDepartureText(WallClock.hourMinute)?.let { details.add("departs $it") }
            }
            entry.segment?.label?.let { details.add(it) }
            return if (details.isEmpty()) null else details.joinToString(" · ")
        }

    /**
     * A cover-less card: time + badge across the top, the title (if any) and an
     * optional detail line, then a location row with the assignee avatar — or, when
     * `showsMembers` and there's a summary, the members box under the badge instead.
     * `placeNameShown` is a place name already in the title, so the location row
     * doesn't repeat it (defaults to the title itself).
     */
    @Composable
    fun CompactCard(
        badge: String,
        title: String?,
        detail: String?,
        placeNameShown: String? = null,
        style: CompactCardStyle = CompactCardStyle.regular,
        showsMembers: Boolean = false,
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = 6.dp)
                .fillMaxWidth()
                .background(style.background, cardShape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ForegroundStyle(style.foreground.copy(alpha = 0.7f)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(time, style = headerStyle.monospacedDigit)
                    Spacer(Modifier.weight(1f))
                    if (showsMembers) {
                        BadgeAndMembers(badge, tint = style.foreground, lineLimit = 1)
                    } else {
                        Badge(badge, lineLimit = 1)
                    }
                }
            }

            ForegroundStyle(style.foreground) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (title != null) {
                            Text(title, style = AppFont.title3.bold)
                        }
                        if (detail != null) {
                            Text(detail, style = AppFont.caption.medium, color = style.foreground.copy(alpha = 0.75f))
                        }
                        LocationLabel(hidingName = placeNameShown ?: title ?: "", foreground = style.foreground)
                    }

                    // The members box up top stands in for the avatar.
                    if (!showsMembers || membersSummary == null) {
                        AssigneeAvatar(tint = style.foreground)
                    }
                }
            }
        }
    }

    // MARK: - Shared pieces

    /** The place's display name: the cached Google name, else the backend's. */
    private val placeName: String?
        get() = place?.googlePlaceId?.let { placeDetails[it] }?.name ?: place?.name

    /**
     * The name and address a card's location row shows: the name is dropped when it
     * matches `hiddenName` (already shown elsewhere on the card).
     */
    private fun locationParts(hiddenName: String): Pair<String?, String?> {
        val name = placeName?.takeIf { it != hiddenName }
        val address = place?.googlePlaceId?.let { placeDetails[it] }?.address ?: place?.address
        return name to address
    }

    private fun hasLocation(hidingName: String): Boolean {
        val (name, address) = locationParts(hidingName)
        return name != null || address != null
    }

    /**
     * A card's location: the place name over its address, hidden when there's neither
     * a name nor an address to show.
     */
    @Composable
    private fun LocationLabel(hidingName: String, foreground: Color = Color.appForeground) {
        val (name, address) = locationParts(hidingName)
        if (name != null || address != null) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (name != null) {
                    Text(
                        name,
                        style = AppFont.caption.semibold,
                        color = foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (address != null) {
                    Text(
                        address,
                        style = AppFont.caption2,
                        color = foreground.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    /**
     * Who the item is for, in very dark gray on a small box tinted to the card. Three or
     * more add a plain plus snug against the two-person icon.
     */
    @Composable
    private fun MembersBox(summary: String, tint: Color = Color.appForeground) {
        ForegroundStyle(Color.white(0.15f)) {
            Row(
                modifier = Modifier
                    // Near-solid so the dark text reads over photos too.
                    .background(tint.copy(alpha = 0.85f), RoundedCornerShape(7.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp)
                    .clearAndSetSemantics { contentDescription = "Members: $summary" },
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Negative spacing tucks the plus into the icon's trailing padding.
                Row(horizontalArrangement = Arrangement.spacedBy((-2).dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(ItemMemberSelection.symbol(forCount = membersCount), contentDescription = null, modifier = Modifier.size(13.dp))
                    if (membersCount >= 3) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(10.dp))
                    }
                }
                Text(summary, style = AppFont.caption2.semibold, maxLines = 1, softWrap = false)
            }
        }
    }

    /**
     * The thin circular stand-in for an item's assignee avatar, stroked in `tint` so it
     * reads on both dark and light cards.
     */
    @Composable
    private fun AssigneeAvatar(tint: Color = Color.appForeground) {
        Box(Modifier.size(30.dp).border(1.dp, tint.copy(alpha = 0.35f), CircleShape))
    }

    private companion object {
        /** The time and badge across the top of every card. */
        val headerStyle = AppFont.caption.bold

        /** A stretch of minutes in hours and minutes, narrowly: "1h 30m", "2h", "45m". */
        fun durationText(minutes: Int): String {
            val parts = listOfNotNull(
                (minutes / 60).takeIf { it > 0 }?.let { "${it}h" },
                (minutes % 60).takeIf { it > 0 }?.let { "${it}m" },
            )
            return if (parts.isEmpty()) "0m" else parts.joinToString(" ")
        }
    }
}
