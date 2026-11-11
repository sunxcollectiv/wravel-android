//
//  HomeView.kt
//  outbound
//

package earth.outbound

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.LifecycleResumeEffect
import earth.outbound.auth.LocalAuthStore
import earth.outbound.auth.User
import earth.outbound.networking.Connectivity
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * The main app screen, shown once the user is signed in: a list of the user's trips.
 * Tapping a trip opens its itinerary; long-pressing one opens the edit form.
 */
@Composable
fun HomeView(modifier: Modifier = Modifier) {
    val auth = LocalAuthStore.current

    val store = remember { TripStore() }
    val notifications = remember { NotificationStore() }
    val credits = remember { CreditStore() }
    val pricing = remember { PricingStore() }
    var showingNotifications by remember { mutableStateOf(false) }
    var showingCredits by remember { mutableStateOf(false) }
    var showingAddTrip by remember { mutableStateOf(false) }
    /** The trip whose itinerary is open, by id so the screen reads the latest copy. */
    var openedTripId by remember { mutableStateOf<String?>(null) }
    /** The trip being edited from a long press. */
    var editingTrip by remember { mutableStateOf<Trip?>(null) }
    var isDrawerOpen by remember { mutableStateOf(false) }
    val push = PushNotifications.shared
    val connectivity = Connectivity.shared
    /** Pairs each trip's tile with its itinerary for the zoom transition. */
    val tripZoom = remember { TripZoomSource() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val requestNotificationPermission = rememberNotificationPermissionRequest()

    /**
     * A trip from the list for a notification to open, reloading the list first if
     * it isn't there (e.g. someone just added this user to it).
     */
    val trip: suspend (String) -> Trip? = remember(store) {
        { id ->
            store.trips.firstOrNull { it.id == id } ?: run {
                store.load()
                store.trips.firstOrNull { it.id == id }
            }
        }
    }

    // For the drawer's balance, the credits page, and buying credits from a trip form.
    CompositionLocalProvider(
        LocalCreditStore provides credits,
        LocalPricingStore provides pricing,
    ) {
        LeadingDrawer(
            isOpen = isDrawerOpen,
            onIsOpenChange = { isDrawerOpen = it },
            edgeSwipeEnabled = openedTripId == null && !showingNotifications && !showingCredits,
            // Behind the whole stack, over the app's terracotta, so a trip zooming in or out
            // shows this at its rounded corners.
            modifier = modifier.background(Brush.tripsBackground),
            content = {
                // The trips screen with what's opened from it on top: a trip's itinerary
                // zooming out of its tile, and the pages pushed in from the side.
                Box(Modifier.fillMaxSize().onGloballyPositioned { tripZoom.container = it }) {
                    TripsScreen(
                        store = store,
                        user = auth.currentUser,
                        unreadCount = notifications.unreadCount,
                        tripZoom = tripZoom,
                        onOpenDrawer = { isDrawerOpen = true },
                        onOpenNotifications = { showingNotifications = true },
                        onAddTrip = { showingAddTrip = true },
                        onOpenTrip = { openedTripId = it.id },
                        onEditTrip = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            editingTrip = it
                        },
                        modifier = Modifier
                            .coveredByPushedScreen(showingNotifications || showingCredits)
                            // Out of accessibility's sight under an open trip, which covers it.
                            .then(if (openedTripId != null) Modifier.clearAndSetSemantics {} else Modifier),
                    )

                    TripZoom(openedTripId = openedTripId, source = tripZoom, onBack = { openedTripId = null }) { id ->
                        // Kept while the screen zooms away from a trip that was just deleted.
                        val current = store.trips.firstOrNull { it.id == id }
                        var shown by remember { mutableStateOf(current) }
                        if (current != null && shown != current) shown = current
                        shown?.let { trip ->
                            TripDetailView(
                                trip = trip,
                                groupTrips = trip.extensions ?: emptyList(),
                                onSegmentCreated = {
                                    // The list nests a segment under the trip it was added to.
                                    scope.launch { store.load() }
                                },
                                onBack = { openedTripId = null },
                                onDeleted = {
                                    openedTripId = null
                                    store.remove(id)
                                },
                            )
                        }
                    }

                    PushedScreen(isPresented = showingNotifications, onBack = { showingNotifications = false }) {
                        NotificationsView(store = notifications, trip = trip, onBack = { showingNotifications = false })
                    }
                    PushedScreen(isPresented = showingCredits, onBack = { showingCredits = false }) {
                        CreditsView(onBack = { showingCredits = false })
                    }
                }
            },
            drawer = {
                ProfileDrawer(
                    onSignOut = { isDrawerOpen = false },
                    onOpenCredits = {
                        isDrawerOpen = false
                        showingCredits = true
                    },
                )
            },
        )

        AppSheet(isPresented = showingAddTrip, onDismiss = { showingAddTrip = false }) {
            TripFormView(onSaved = { created: Trip -> store.add(created) }, onDismiss = { showingAddTrip = false })
        }
        AppSheet(item = editingTrip, onDismiss = { editingTrip = null }) { trip ->
            TripFormView(
                trip = trip,
                groupPart = TripGroupPart.forEditing(
                    edited = trip,
                    primary = trip,
                    extensions = trip.extensions ?: emptyList(),
                    userId = auth.currentUser?.id,
                ),
                onSaved = { updated: Trip -> store.update(updated) },
                onDeleted = { store.remove(trip.id) },
                onDismiss = { editingTrip = null },
            )
        }
    }

    LaunchedEffect(Unit) { credits.load() }
    // The current rates, saved for offline, and those of any older trips.
    LaunchedEffect(Unit) { pricing.loadCurrent() }
    val priceSchedules = store.trips.mapNotNull { it.priceSchedule }
    LaunchedEffect(priceSchedules) { pricing.load(store.trips) }
    // Credits purchases that finish outside the purchase popup, e.g. a pending one paid for later.
    LaunchedEffect(Unit) { credits.observeTransactions() }

    LaunchedEffect(Unit) { store.load() }
    LaunchedEffect(Unit) {
        snapshotFlow { connectivity.isConnected }.drop(1).collect { isConnected ->
            // Back online: swap the saved copy for the server's.
            if (isConnected && store.isShowingSavedCopy) launch { store.load() }
        }
    }
    LifecycleResumeEffect(Unit) {
        val refresh = scope.launch { notifications.refreshUnreadCount() }
        onPauseOrDispose { refresh.cancel() }
    }
    LaunchedEffect(Unit) { push.registerIfAllowed(requestNotificationPermission) }
    LaunchedEffect(push.receivedCount) {
        // A push arrived or was tapped: the inbox has something new.
        if (push.receivedCount <= 0) return@LaunchedEffect
        if (showingNotifications) {
            notifications.load()
        } else {
            notifications.refreshUnreadCount()
        }
    }
    // Opens the trip a tapped push is about, over whatever the trips screen shows.
    LaunchedEffect(push.pendingTripId) {
        val id = push.pendingTripId ?: return@LaunchedEffect
        // Cleared only once the lookup is done: clearing restarts this effect, which
        // would cancel a trips load still in flight on a cold launch.
        val found = trip(id)
        ensureActive()
        push.pendingTripId = null
        if (found == null) return@LaunchedEffect
        isDrawerOpen = false
        showingNotifications = false
        showingCredits = false
        openedTripId = found.id
    }
}

// MARK: - Trips screen

/** The trips screen itself: its bar over the list of trips. */
@Composable
private fun TripsScreen(
    store: TripStore,
    user: User?,
    unreadCount: Int,
    tripZoom: TripZoomSource,
    onOpenDrawer: () -> Unit,
    onOpenNotifications: () -> Unit,
    onAddTrip: () -> Unit,
    onOpenTrip: (Trip) -> Unit,
    onEditTrip: (Trip) -> Unit,
    modifier: Modifier = Modifier,
) {
    ForegroundStyle(Color.appForeground) {
        Column(
            modifier
                .fillMaxSize()
                .offlinePillAtBottom(
                    savedDate = if (store.isShowingSavedCopy) store.savedDate else null,
                    showsWhenOnline = store.isShowingSavedCopy,
                )
        ) {
            TripsBar(
                user = user,
                unreadCount = unreadCount,
                onOpenDrawer = onOpenDrawer,
                onOpenNotifications = onOpenNotifications,
                onAddTrip = onAddTrip,
            )
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                if (store.isLoading && store.trips.isEmpty()) {
                    ProgressView(controlSize = ControlSize.Large, tint = Color.appForeground)
                } else if (store.trips.isEmpty()) {
                    EmptyState(message = store.errorMessage ?: "Tap + to plan your first trip.")
                } else {
                    val (upcoming, past) = Trip.upcomingAndPast(store.trips)
                    val tile: @Composable (Trip, Boolean) -> Unit = { trip, isFeatured ->
                        TripTile(
                            trip = trip,
                            isFeatured = isFeatured,
                            tripZoom = tripZoom,
                            onOpen = { onOpenTrip(trip) },
                            onEdit = { onEditTrip(trip) },
                        )
                    }
                    Refreshable(onRefresh = { store.load() }) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            // Upcoming and current trips lead the screen unlabeled.
                            TripSection(
                                title = null,
                                trips = upcoming,
                                emptyText = "No upcoming trips",
                                featuresFirst = true,
                                modifier = Modifier.padding(16.dp).padding(bottom = 8.dp),
                                tile = tile,
                            )
                            TripSection(
                                title = "Past",
                                trips = past,
                                emptyText = "No past trips",
                                modifier = Modifier
                                    .drawBehind { drawPastBackground() }
                                    .padding(16.dp)
                                    .padding(top = 4.dp)
                                    // Room for the offline pill over the bottom, whether or not it's
                                    // showing, so the list's end never shifts.
                                    .padding(bottom = 48.dp)
                                    .navigationBarsPadding(),
                                tile = tile,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The bar over the trips: the user's picture, which opens the drawer, the title, and
 * the bell and plus.
 */
@Composable
private fun TripsBar(
    user: User?,
    unreadCount: Int,
    onOpenDrawer: () -> Unit,
    onOpenNotifications: () -> Unit,
    onAddTrip: () -> Unit,
) {
    ForegroundStyle(Color.White) {
        Box(Modifier.fillMaxWidth().statusBarsPadding().height(navigationBarHeight)) {
            if (user != null) {
                // In line with the title and grid's 16dp margin.
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 10.dp)
                        .size(navigationBarHeight)
                        .plainClickable(onClick = onOpenDrawer)
                        .semantics { contentDescription = "Profile" },
                    contentAlignment = Alignment.Center,
                ) {
                    AvatarView(
                        user = user,
                        size = 32.dp,
                        modifier = Modifier.shadow(3.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black),
                    )
                }
            }
            // A step larger than the pushed pages' titles.
            Text(
                "Trips",
                style = AppFont.title3.semibold,
                modifier = Modifier.align(Alignment.Center).semantics { heading() },
            )
            Row(Modifier.align(Alignment.CenterEnd).padding(end = barButtonInset)) {
                BarButton(onClick = onOpenNotifications) {
                    NotificationBell(unreadCount = unreadCount)
                }
                BarButton(onClick = onAddTrip) {
                    Icon(Icons.Rounded.Add, contentDescription = "Add Trip", modifier = Modifier.size(26.dp))
                }
            }
        }
    }
}

/**
 * A grid of trips under an optional title, or a placeholder line when there are none,
 * so the section keeps its place on the screen. With `featuresFirst`, the first trip spans the grid
 * as a large square above the rest.
 */
@Composable
private fun TripSection(
    title: String?,
    trips: List<Trip>,
    emptyText: String,
    featuresFirst: Boolean = false,
    modifier: Modifier = Modifier,
    tile: @Composable (trip: Trip, isFeatured: Boolean) -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (title != null) {
            Text(
                title.uppercase(),
                style = AppFont.caption.semibold.copy(letterSpacing = 1.sp),
                color = Color.appForeground.copy(alpha = 0.6f),
                modifier = Modifier.semantics { heading() },
            )
        }
        if (trips.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 56.dp)
                    .dashedOutline(Color.appForeground.copy(alpha = 0.3f), cornerRadius = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(emptyText, style = AppFont.subheadline, color = Color.appForeground.copy(alpha = 0.5f))
            }
        } else {
            val featured = if (featuresFirst) trips.first() else null
            if (featured != null) {
                tile(featured, true)
            }
            val rest = if (featured == null) trips else trips.drop(1)
            if (rest.isNotEmpty()) {
                Column(
                    modifier = Modifier.padding(top = if (featured == null) 0.dp else 4.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    for (pair in rest.chunked(gridColumns)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            for (trip in pair) {
                                key(trip.id) {
                                    Box(Modifier.weight(1f)) { tile(trip, false) }
                                }
                            }
                            // An odd one out keeps to its column.
                            repeat(gridColumns - pair.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

/** How many trips sit side by side in the grid. */
private const val gridColumns = 2

/**
 * A lighter panel behind the past trips, rounded on top and running far past their
 * bottom so it fills the rest of the screen however few there are, and stays put
 * when the list bounces.
 */
private fun DrawScope.drawPastBackground() {
    val radius = 24.dp.toPx()
    val bottom = size.height + 2000.dp.toPx()
    val panel = Path().apply {
        addRoundRect(
            RoundRect(
                rect = Rect(0f, 0f, size.width, bottom),
                topLeft = CornerRadius(radius),
                topRight = CornerRadius(radius),
            )
        )
    }
    drawPath(panel, Color.White.copy(alpha = 0.06f))

    // A hairline along the top edge, fading out down the sides.
    val stroke = 1.dp.toPx()
    val fade = 48.dp.toPx()
    val inset = stroke / 2
    val edge = Path().apply {
        moveTo(inset, fade)
        lineTo(inset, radius)
        arcTo(Rect(inset, inset, 2 * radius - inset, 2 * radius - inset), 180f, 90f, forceMoveTo = false)
        lineTo(size.width - radius, inset)
        arcTo(
            Rect(size.width - 2 * radius + inset, inset, size.width - inset, 2 * radius - inset),
            270f, 90f, forceMoveTo = false,
        )
        lineTo(size.width - inset, fade)
    }
    drawPath(
        edge,
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.08f), Color.Transparent), startY = 0f, endY = fade),
        style = Stroke(stroke),
    )
}

/** A trip's card in the grid: a tap opens its itinerary, a long press its edit form. */
@Composable
private fun TripTile(
    trip: Trip,
    isFeatured: Boolean,
    tripZoom: TripZoomSource,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
) {
    TripCard(
        trip = trip,
        isFeatured = isFeatured,
        modifier = Modifier
            .onGloballyPositioned { tripZoom.setTile(trip.id, it) }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onLongClickLabel = "Edit Trip",
                onLongClick = onEdit,
                onClick = onOpen,
            ),
    )
}

@Composable
private fun EmptyState(message: String) {
    Column(
        modifier = Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("No trips yet", style = AppFont.title3.bold)
        Text(
            message,
            style = AppFont.subheadline,
            color = Color.appForeground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
    }
}

// MARK: - Zoom transition

/**
 * Where the trip tiles are on the trips screen, for a trip's itinerary to grow out
 * of its tile and shrink back into it.
 */
@Stable
private class TripZoomSource {
    /** The area the itinerary fills once open. */
    var container: LayoutCoordinates? = null

    private val tiles = mutableMapOf<String, LayoutCoordinates>()

    fun setTile(id: String, coordinates: LayoutCoordinates) {
        tiles[id] = coordinates
    }

    /**
     * The frame of the trip's tile within `container` right now, in pixels, or null
     * if the trip has no tile on screen (e.g. it was just deleted).
     */
    fun bounds(id: String): Rect? {
        val container = container?.takeIf { it.isAttached } ?: return null
        val tile = tiles[id]?.takeIf { it.isAttached } ?: return null
        return container.localBoundingBoxOf(tile, clipBounds = false)
    }
}

/**
 * Shows the opened trip's screen over the trips, growing out of the trip's tile as
 * it opens and shrinking back into it as it closes, clipped to the tile's rounded
 * corners on the way. The tile stays where it is underneath, so the screen fades in
 * over it at the start and gives way to it at the end. A trip with no tile on screen
 * grows from the middle instead.
 */
@Composable
private fun TripZoom(
    openedTripId: String?,
    source: TripZoomSource,
    onBack: () -> Unit,
    content: @Composable (id: String) -> Unit,
) {
    /** How far open the screen is, from the tile (0) to the whole screen (1). */
    val zoom = remember { Animatable(0f) }
    /** The trip on display, which stays while its screen zooms away. */
    var zoomedTripId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(openedTripId) {
        if (openedTripId != null) {
            if (zoomedTripId != openedTripId) {
                zoom.snapTo(0f)
                zoomedTripId = openedTripId
            }
            zoom.animateTo(1f, AppAnimation.smooth(0.45))
        } else if (zoomedTripId != null) {
            zoom.animateTo(0f, AppAnimation.smooth(0.4))
            zoomedTripId = null
        }
    }

    BackHandler(enabled = openedTripId != null, onBack = onBack)

    val id = zoomedTripId ?: return
    val tileCornerRadius = with(LocalDensity.current) { 16.dp.toPx() }

    // Dims the trips as the screen grows over them.
    Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black, alpha = 0.35f * zoom.value.coerceIn(0f, 1f)) })

    key(id) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val progress = zoom.value.coerceIn(0f, 1f)
                    val whole = Rect(Offset.Zero, size)
                    val from = source.bounds(id) ?: whole.deflate(size.minDimension * 0.06f)
                    val left = lerp(from.left, whole.left, progress)
                    val top = lerp(from.top, whole.top, progress)
                    val width = lerp(from.width, whole.width, progress)
                    val height = lerp(from.height, whole.height, progress)

                    // The screen at its full size, scaled down to the frame's width and
                    // cut off at the frame's height.
                    val scale = width / size.width
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = scale
                    scaleY = scale
                    translationX = left
                    translationY = top
                    alpha = (progress / 0.25f).coerceAtMost(1f)
                    clip = progress < 1f
                    shape = ZoomClip(
                        height = height / scale,
                        cornerRadius = lerp(tileCornerRadius, 0f, progress) / scale,
                    )
                }
                // Touches stop here, not at the trips underneath.
                .pointerInput(Unit) {}
                .then(if (openedTripId == null) Modifier.clearAndSetSemantics {} else Modifier)
        ) {
            content(id)
        }
    }
}

/** The part of the zooming screen on show: its top `height` pixels, with rounded corners. */
private data class ZoomClip(val height: Float, val cornerRadius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(
            RoundRect(
                rect = Rect(0f, 0f, size.width, min(height, size.height)),
                cornerRadius = CornerRadius(cornerRadius),
            )
        )
}
