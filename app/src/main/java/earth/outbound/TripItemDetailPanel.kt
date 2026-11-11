//
//  TripItemDetailPanel.kt
//  outbound
//

package earth.outbound

import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import earth.outbound.networking.GooglePlacesClient
import earth.outbound.networking.PlaceDetails
import kotlinx.coroutines.CancellationException
import java.text.NumberFormat
import java.time.ZoneId
import java.util.Locale
import kotlin.math.cos
import kotlin.math.log2

/**
 * An item's details, folded open beneath its card in the itinerary: a soft
 * off-white panel that grows down out of the card's bottom edge, with the item's
 * details and an Edit button. Tapping the card again folds it away.
 *
 * Square along the top and rounded along the bottom, so with the card's bottom
 * corners squared off too (`ItineraryEntryCard`'s `isExpanded`) the two read as one
 * card. Its top sits up behind the card, so there's white behind the card's
 * corners while they animate square.
 */
@Composable
fun TripItemDetailPanel(
    item: TripItem,
    /**
     * How far the panel's top sits up behind the card (see
     * `TripItemDetailPanelMetrics.anchor`); zero under a see-through card, where it
     * would show through.
     */
    anchor: Dp = TripItemDetailPanelMetrics.anchor,
    /**
     * Google Place Details the itinerary already has, by google place id (see
     * `TripItemDetailContent`'s `knownDetails`).
     */
    placeDetails: Map<String, PlaceDetails> = emptyMap(),
    /**
     * Off for a copy laid out only to be measured, so it doesn't fetch too (nor load
     * maps nobody sees).
     */
    loadsMissingDetails: Boolean = true,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
) {
    CompositionLocalProvider(LocalTint provides Color.appAccent) {
        ForegroundStyle(TripItemDetailContentMetrics.foreground) {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .background(TripItemDetailContentMetrics.background, panelShape)
                    .padding(horizontal = 20.dp)
                    // Clear the part hidden behind the card.
                    .padding(top = 20.dp + anchor, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                TripItemDetailContent(item = item, knownDetails = placeDetails, loadsMissingDetails = loadsMissingDetails)
                DarkPillButton("Edit", onClick = onEdit, modifier = Modifier.align(Alignment.End))
            }
        }
    }
}

private val panelShape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)

object TripItemDetailPanelMetrics {
    /**
     * How far above its card's bottom edge the panel's top is pinned, hidden
     * behind the card. The panel only grows downward from there, so as it opens
     * (and the card's corners square off) there's always white under the seam.
     */
    val anchor: Dp = 20.dp
}

/**
 * Unfolds a view downward from its top edge: the top stays put and only the
 * visible height grows (or shrinks back), like a drawer opening, with nothing
 * sliding. Hand `enter` and `exit` to the `AnimatedVisibility` showing the view.
 */
object UnfoldTransition {
    fun enter(animationSpec: FiniteAnimationSpec<IntSize> = AppAnimation.default()): EnterTransition =
        expandVertically(animationSpec = animationSpec, expandFrom = Alignment.Top)

    fun exit(animationSpec: FiniteAnimationSpec<IntSize> = AppAnimation.default()): ExitTransition =
        shrinkVertically(animationSpec = animationSpec, shrinkTowards = Alignment.Top)
}

/**
 * The body of an item's details — badges, place(s), times, stay details, notes,
 * tags and source — shown in the inline panel under its itinerary card. Loads Google
 * Place Details for its place(s) itself.
 */
@Composable
fun TripItemDetailContent(
    item: TripItem,
    /**
     * Google Place Details already fetched elsewhere (the itinerary's cache), by
     * google place id. Used straight away, so the details lay out at their full
     * height from the first frame instead of growing once a fetch lands; that
     * first height is what the itinerary measures to scroll an opened card into
     * place.
     */
    knownDetails: Map<String, PlaceDetails> = emptyMap(),
    /**
     * Whether to fetch details `knownDetails` doesn't have. Off, the maps are left
     * blank too: the details are only being measured.
     */
    loadsMissingDetails: Boolean = true,
    modifier: Modifier = Modifier,
) {
    /** Google Place Details fetched here, for a place `knownDetails` doesn't cover. */
    var fetchedStartDetails by remember { mutableStateOf<PlaceDetails?>(null) }
    var fetchedEndDetails by remember { mutableStateOf<PlaceDetails?>(null) }

    // Details for the item's place and (for transit) its destination: the known
    // ones if there are any, else whatever was fetched here.
    val startDetails = item.place?.googlePlaceId?.let { knownDetails[it] } ?: fetchedStartDetails
    val endDetails = item.endPlace?.googlePlaceId?.let { knownDetails[it] } ?: fetchedEndDetails

    // Changes whenever the item's place(s) change, so the loader re-runs after an edit.
    val placeTaskKey = "${item.place?.id ?: ""}|${item.endPlace?.id ?: ""}"

    // Loads Google Place Details for whichever places have a google place id. Failures
    // are ignored: the section falls back to the stored place fields.
    LaunchedEffect(placeTaskKey) {
        fetchedStartDetails = null
        fetchedEndDetails = null
        if (!loadsMissingDetails) return@LaunchedEffect
        item.place?.googlePlaceId?.takeIf { knownDetails[it] == null }?.let { fetchedStartDetails = placeDetailsOrNull(it) }
        if (item.kind == TripItemKind.Transit) {
            item.endPlace?.googlePlaceId?.takeIf { knownDetails[it] == null }?.let { fetchedEndDetails = placeDetailsOrNull(it) }
        }
    }

    // The per-kind body. Every kind currently funnels to the same generic layout;
    // this switch is where activity / lodging / transit layouts will diverge.
    when (item.kind) {
        TripItemKind.Activity, TripItemKind.Lodging, TripItemKind.Transit ->
            GenericDetail(item, startDetails = startDetails, endDetails = endDetails, drawsMaps = loadsMissingDetails, modifier = modifier)
    }
}

object TripItemDetailContentMetrics {
    /**
     * A light gray, a step darker than the light itinerary cards, so an open card
     * stands apart from the details beneath it; near-black text on it.
     */
    val background = Color.white(0.82f)
    val foreground = Color.white(0.1f)
}

private val textColor = TripItemDetailContentMetrics.foreground

private suspend fun placeDetailsOrNull(placeId: String): PlaceDetails? =
    try {
        GooglePlacesClient.shared.placeDetails(placeId = placeId)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

// MARK: - Generic layout

@Composable
private fun GenericDetail(
    item: TripItem,
    startDetails: PlaceDetails?,
    endDetails: PlaceDetails?,
    drawsMaps: Boolean,
    modifier: Modifier,
) {
    val isLodging = item.kind == TripItemKind.Lodging
    Column(modifier, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Header(item)

        if (item.kind == TripItemKind.Transit) {
            item.place?.let { PlaceSection(item, title = "From", place = it, details = startDetails, drawsMap = drawsMaps) }
            item.endPlace?.let { PlaceSection(item, title = "To", place = it, details = endDetails, drawsMap = drawsMaps) }
        } else {
            item.place?.let { PlaceSection(item, title = "Location", place = it, details = startDetails, drawsMap = drawsMaps) }
        }

        DetailSection(title = if (isLodging) "Check-in" else "Starts") {
            Text(listOfNotNull(item.startText(WallClock.weekdayMonthDayHourMinute), item.startZoneAbbreviation).joinToString(" "))
        }

        item.endText(WallClock.weekdayMonthDayHourMinute)?.let { endText ->
            DetailSection(title = if (isLodging) "Check-out" else "Ends") {
                Text(listOfNotNull(endText, item.endZoneAbbreviation).joinToString(" "))
            }
        }

        if (isLodging) {
            StaySections(item, lodging = item.lodging ?: LodgingDetails(), rooms = item.rooms)
        }

        val notes = item.notes
        if (!notes.isNullOrEmpty()) {
            DetailSection(title = "Notes") {
                Text(notes)
            }
        }

        if (item.tags.isNotEmpty()) {
            DetailSection(title = "Tags") {
                TagChips(item.tags)
            }
        }

        SourceSection(item)
    }
}

/**
 * Where an imported item came from, and whether anyone has changed the booking
 * since. Left out for one entered by hand.
 */
@Composable
private fun SourceSection(item: TripItem) {
    val source = item.source ?: return
    val label = source.importLabel ?: return
    DetailSection(title = "Source") {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            DetailLabel(label, icon = source.systemImage)
            editedText(item)?.let { edited ->
                Text(edited, style = AppFont.subheadline, color = textColor.copy(alpha = 0.7f))
            }
        }
    }
}

/**
 * "Edited since by Sam on Nov 5" (or without the name when nobody's known), or
 * null when the booking hasn't been changed since it was imported.
 */
private fun editedText(item: TripItem): String? {
    val editedAt = item.editedAt ?: return null
    val day = WallClock.monthDay.withZone(ZoneId.systemDefault()).format(editedAt)
    val name = item.editedBy?.displayName ?: item.editedBy?.email
    return if (name != null) "Edited since by $name on $day" else "Edited since on $day"
}

/** Kind and visibility badges across the top. */
@Composable
private fun Header(item: TripItem) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Badge(item.kind.label.uppercase())
        val type = item.lodging?.propertyType
        if (item.kind == TripItemKind.Lodging && type != null) {
            Badge(type.label.uppercase(), systemImage = type.systemImage)
        }
        Badge(item.visibility.label.uppercase())
    }
}

@Composable
private fun Badge(text: String, systemImage: ImageVector? = null) {
    Row(
        modifier = Modifier
            .background(textColor.copy(alpha = 0.1f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (systemImage != null) {
            Icon(systemImage, contentDescription = null, modifier = Modifier.size(14.dp))
        }
        Text(text, style = AppFont.caption.bold.copy(letterSpacing = 1.5.sp), maxLines = 1)
    }
}

/** The item's tags as chips, wrapping onto more lines when one won't hold them. */
@Composable
private fun TagChips(tags: List<String>) {
    FlowLayout(spacing = 8.dp) {
        for (tag in tags) {
            Text(
                tag,
                style = AppFont.caption,
                modifier = Modifier
                    .background(textColor.copy(alpha = 0.1f), CircleShape)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/** A titled block: a small caption label above its content. */
@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title.uppercase(),
            style = AppFont.caption.semibold.copy(letterSpacing = 1.sp),
            color = textColor.copy(alpha = 0.6f),
        )
        ProvideTextStyle(AppFont.body) {
            Box(Modifier.fillMaxWidth()) { content() }
        }
    }
}

/** An icon beside a line of text, in the text style and color around it. */
@Composable
private fun DetailLabel(text: String, icon: ImageVector, modifier: Modifier = Modifier, style: TextStyle = AppFont.body) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(if (style.fontSize < AppFont.body.fontSize) 17.dp else 20.dp))
        Text(text, style = style)
    }
}

/** A label that opens `url` when tapped, drawn in the tint like a link. */
@Composable
private fun DetailLink(text: String, icon: ImageVector, url: String, style: TextStyle = AppFont.body) {
    val uriHandler = LocalUriHandler.current
    ForegroundStyle(LocalTint.current) {
        DetailLabel(
            text,
            icon = icon,
            style = style,
            modifier = Modifier.plainClickable(role = Role.Button) {
                try {
                    uriHandler.openUri(url)
                } catch (_: IllegalArgumentException) {
                    // Nothing on the device opens this kind of link.
                }
            },
        )
    }
}

/** The link that dials `phone`, as written but without its spaces. */
private fun phoneURL(phone: String): String = "tel:" + phone.filterNot { it.isWhitespace() }

// MARK: - Stay details

/**
 * What the booking says about a stay beyond its dates and place: its rooms, the
 * free-cancellation deadline, and the property's own phone. Each block is left
 * out when the booking doesn't state it.
 */
@Composable
private fun StaySections(item: TripItem, lodging: LodgingDetails, rooms: List<LodgingRoom>) {
    if (rooms.isNotEmpty()) {
        DetailSection(title = if (rooms.size == 1) "Room" else "Rooms") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rooms.forEachIndexed { index, room ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(room.title(number = index + 1), style = AppFont.body.semibold)
                        room.summary?.let { summary ->
                            Text(summary, style = AppFont.subheadline, color = textColor.copy(alpha = 0.7f))
                        }
                    }
                }
            }
        }
    }

    lodging.cancellationText(zone = item.startTimezone)?.let { deadline ->
        val passed = lodging.cancellationHasPassed(zone = item.startTimezone)
        DetailSection(title = "Free cancellation") {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (passed) "Ended $deadline" else "Until $deadline")
                if (passed) {
                    Text("Cancelling now may cost you.", style = AppFont.subheadline, color = textColor.copy(alpha = 0.7f))
                }
            }
        }
    }

    val phone = lodging.phone
    if (phone != null && phone.isNotBlank()) {
        DetailSection(title = "Property phone") {
            DetailLink(phone, icon = Icons.Rounded.Phone, url = phoneURL(phone))
        }
    }
}

// MARK: - Place details

/**
 * A place block: name, type, rating, address, hours, and contact links. Uses live
 * Google details when available, otherwise the place's stored fields.
 */
@Composable
private fun PlaceSection(item: TripItem, title: String, place: Place, details: PlaceDetails?, drawsMap: Boolean) {
    val name = details?.name ?: place.name
    val address = details?.address ?: place.address

    DetailSection(title = title) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (name != null && name != item.displayTitle) {
                Text(name, style = AppFont.body.semibold)
            }

            val type = details?.typeLabel
            val rating = details?.rating
            if (type != null || rating != null) {
                ProvideTextStyle(AppFont.subheadline) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (type != null) {
                            Text(type)
                        }
                        if (rating != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Star, contentDescription = null, tint = starYellow, modifier = Modifier.size(17.dp))
                                Text(String.format(Locale.ROOT, "%.1f", rating))
                                details.userRatingCount?.let { count ->
                                    Text("(${NumberFormat.getIntegerInstance().format(count)})", color = textColor.copy(alpha = 0.6f))
                                }
                            }
                        }
                    }
                }
            }

            if (address != null) {
                Text(address, color = textColor.copy(alpha = 0.85f))
            }

            if (details != null && details.weekdayHours.isNotEmpty()) {
                HoursDisclosure(details)
            }

            ContactLinks(details)

            coordinate(place, details)?.let { coordinate ->
                PlaceMap(coordinate, name = name ?: place.name ?: item.displayTitle, isDrawn = drawsMap)
            }
        }
    }
}

/**
 * Prefers Google's coordinates (reliably present) and falls back to the
 * backend-enriched place coordinates when details haven't loaded.
 */
private fun coordinate(place: Place, details: PlaceDetails?): LatLng? {
    val detailsLatitude = details?.latitude
    val detailsLongitude = details?.longitude
    if (detailsLatitude != null && detailsLongitude != null) return LatLng(detailsLatitude, detailsLongitude)
    val latitude = place.latitude
    val longitude = place.longitude
    if (latitude != null && longitude != null) return LatLng(latitude, longitude)
    return null
}

/**
 * A compact, non-interactive map pinning the place. Without a Maps key there's no
 * map to draw, so the same frame shows the place's name under a pin instead. Not
 * `isDrawn`, the frame is left empty.
 */
@Composable
private fun PlaceMap(coordinate: LatLng, name: String, isDrawn: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(12.dp))
    ) {
        if (!isDrawn) return@Box
        if (hasMapsKey && !LocalInspectionMode.current) {
            PinnedMap(coordinate, name = name)
            // Over the map to take its touches, so it does nothing when tapped and a
            // drag that starts on it still scrolls the page.
            Box(Modifier.matchParentSize().pointerInput(Unit) {})
        } else {
            MapPlaceholder(name)
        }
    }
}

/** The map itself, drawn once as a still image (lite mode) around the place's pin. */
@Composable
private fun PinnedMap(coordinate: LatLng, name: String) {
    val cameraPositionState = rememberCameraPositionState(key = "${coordinate.latitude},${coordinate.longitude}") {
        position = CameraPosition.fromLatLngZoom(coordinate, mapZoom(latitude = coordinate.latitude))
    }
    GoogleMap(
        modifier = Modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState,
        googleMapOptionsFactory = { GoogleMapOptions().liteMode(true) },
        uiSettings = MapUiSettings(
            compassEnabled = false,
            indoorLevelPickerEnabled = false,
            mapToolbarEnabled = false,
            myLocationButtonEnabled = false,
            rotationGesturesEnabled = false,
            scrollGesturesEnabled = false,
            scrollGesturesEnabledDuringRotateOrZoom = false,
            tiltGesturesEnabled = false,
            zoomControlsEnabled = false,
            zoomGesturesEnabled = false,
        ),
    ) {
        Marker(
            state = remember(coordinate) { MarkerState(position = coordinate) },
            title = name,
            icon = BitmapDescriptorFactory.defaultMarker(markerHue),
        )
    }
}

/** Stands in for the map when there's no Maps key: a pin over the place's name, on the card color. */
@Composable
private fun MapPlaceholder(name: String) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color.appCard).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Icon(Icons.Rounded.Place, contentDescription = null, tint = Color.appForeground, modifier = Modifier.size(34.dp))
        Text(
            name,
            style = AppFont.subheadline.semibold,
            color = Color.appForeground,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Whether the app was built with a Google Maps key, which the map needs to draw anything. */
private val hasMapsKey: Boolean by lazy {
    val context = OutboundApp.context
    try {
        val info = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        !info.metaData?.getString("com.google.android.geo.API_KEY").isNullOrBlank()
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}

/** The app's accent, as the hue Google's default marker is tinted by. */
private const val markerHue = 19f

/**
 * The zoom at which the map's 180dp height spans about 0.01° of latitude (a little
 * over a kilometer) around the place.
 */
private fun mapZoom(latitude: Double): Float {
    val metersPerDp = 0.01 * 111_320 / 180
    // At zoom 0 the whole world is 256dp wide: 156,543m per dp at the equator.
    val zoom = log2(156_543.03392 * cos(Math.toRadians(latitude)) / metersPerDp)
    return zoom.toFloat().coerceIn(2f, 20f)
}

/** The place's hours, folded shut under an "Hours" row that opens them when tapped. */
@Composable
private fun HoursDisclosure(details: PlaceDetails) {
    var isExpanded by remember { mutableStateOf(false) }
    val turns by animateFloatAsState(if (isExpanded) 1f else 0f, AppAnimation.default(), label = "chevron")
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .plainClickable(dimsWhenPressed = false, onClickLabel = if (isExpanded) "Hide hours" else "Show hours") {
                    isExpanded = !isExpanded
                },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Hours", style = AppFont.subheadline.medium)
            details.openNow?.let { openNow ->
                val color = if (openNow) Color.systemGreen else Color.systemRed
                Text(
                    if (openNow) "Open now" else "Closed",
                    style = AppFont.caption.semibold,
                    color = color,
                    modifier = Modifier
                        .background(color.copy(alpha = 0.18f), CircleShape)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(20.dp).quarterTurns(turns),
            )
        }
        AnimatedVisibility(
            visible = isExpanded,
            enter = UnfoldTransition.enter(),
            exit = UnfoldTransition.exit(),
        ) {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (line in details.weekdayHours) {
                    Text(line, style = AppFont.subheadline)
                }
            }
        }
    }
}

@Composable
private fun ContactLinks(details: PlaceDetails?) {
    details?.phone?.let { phone ->
        DetailLink(phone, icon = Icons.Rounded.Phone, url = phoneURL(phone), style = AppFont.subheadline)
    }
    details?.website?.let { website ->
        DetailLink("Website", icon = Icons.Rounded.Language, url = website, style = AppFont.subheadline)
    }
    details?.mapsURL?.let { maps ->
        DetailLink("Open in Google Maps", icon = Icons.Rounded.Map, url = maps, style = AppFont.subheadline)
    }
}

/** The yellow iOS draws ratings in. */
private val starYellow = Color(red = 1f, green = 0.8f, blue = 0f)

/**
 * A compact button: light text on a dark pill, so it reads clearly against the
 * light panel background.
 */
@Composable
private fun DarkPillButton(title: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    Text(
        title,
        style = AppFont.subheadline.semibold,
        color = Color.white(0.95f),
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .alpha(if (isPressed) 0.7f else 1f)
            .clip(CircleShape)
            .background(Color.white(0.15f))
            .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Preview
@Composable
private fun TripItemDetailPanelPreview() {
    AppTheme {
        Box(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            TripItemDetailPanel(item = TripItem.sample, anchor = 0.dp) {}
        }
    }
}
