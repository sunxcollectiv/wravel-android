//
//  TripDetailView.kt
//  outbound
//

package earth.outbound

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import earth.outbound.auth.AuthStore
import earth.outbound.auth.LocalAuthStore
import earth.outbound.networking.APIClient
import earth.outbound.networking.Connectivity
import earth.outbound.networking.GooglePlacesClient
import earth.outbound.networking.MultipartFile
import earth.outbound.networking.PlaceDetails
import earth.outbound.networking.localizedDescription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The itinerary screen's measurements and animations. They live here rather than on
 * `TripDetailView`, which is a composable function and so can't hold them itself.
 */
internal object TripDetailViewMetrics {
    /** How far below the pinned block cards start fading once a page is scrolled. */
    val topFadeReach: Dp = 60.dp

    /** The space under the pinned block before the itinerary starts. */
    val pinnedBlockBottomPadding: Dp = 8.dp

    /** See `TripDetailModel.scrollRunway`. */
    val scrollRunwayHeight: Dp = 2000.dp

    /**
     * How long a card's details take to fold open or shut, and the page to scroll
     * the opened card into place.
     */
    const val expandDuration: Double = 0.3

    /**
     * The curve a card's details fold open or shut on, with the page scrolling
     * alongside: the itinerary's steep ease in and out.
     */
    fun <T> expandAnimation(): AnimationSpec<T> = AppAnimation.steepEase(expandDuration)

    /**
     * The least space left between the pinned block and an opened card too tall to
     * center.
     */
    val expandedHeaderGap: Dp = 20.dp

    /** The card's own padding above and below, which the row carries. */
    val rowPadding: Dp = 6.dp

    /** The room kept at the bottom of the screen for the day picker. */
    val dayPickerClearance: Dp = 72.dp

    /** The gap between one row of a day's itinerary and the next. */
    val rowSpacing: Dp = 16.dp

    /**
     * How long the rows of a deleted item are kept on the page for, which sees their
     * fade through.
     */
    val rowLeaveDuration: Duration = 1.seconds

    /**
     * When the card at `index` starts its entrance: just after the pinned header and
     * day picker, then each card a beat after the one before. Capped so a long day
     * doesn't keep the cards further down waiting.
     */
    fun cardRevealDelay(index: Int): Double = 0.12 + minOf(index, 6) * 0.07
}

/**
 * The itinerary for a single trip, presented one day per page. You swipe
 * horizontally between days, and a day picker pinned to the bottom stays in
 * sync (tap a day to jump, swipe to scrub). The day-by-day itinerary loads
 * asynchronously, showing placeholder scaffolding while in flight.
 *
 * It fills the screen and draws its own header chip in place of a navigation bar;
 * `onBack` closes it. `groupTrips` are the user's other trips in this trip's group
 * (an end of the day picker one of them reaches gets no add-segment button),
 * `onSegmentCreated` is called with a segment created from the day picker, for the
 * trip list, and `onDeleted` after the trip is deleted from its menu or edit form;
 * the presenter closes this screen.
 */
@Composable
fun TripDetailView(
    trip: Trip,
    days: List<ItineraryDay>? = null,
    groupTrips: List<Trip> = emptyList(),
    onSegmentCreated: (Trip) -> Unit = {},
    onBack: () -> Unit,
    onDeleted: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val auth = LocalAuthStore.current
    val credits = LocalCreditStore.current
    val animationScope = rememberCoroutineScope()
    val model = remember(trip.id) { TripDetailModel(trip, days, auth, credits, animationScope) }
    val density = LocalDensity.current
    val connectivity = Connectivity.shared
    val bottomInset = WindowInsets.navigationBars.getBottom(density).toFloat()
    SideEffect {
        model.groupTrips = groupTrips
        model.onSegmentCreated = onSegmentCreated
        model.onDeleted = onDeleted
        model.density = density
        model.bottomInset = bottomInset
    }

    LaunchedEffect(model) { if (model.days == null || model.savedCopyDate != null) model.load() }
    // Back online: swap the saved copy (or the error) for the server's.
    val isConnected = connectivity.isConnected
    LaunchedEffect(isConnected) { if (isConnected && model.loadFailed) model.reload() }
    DisposableEffect(model) {
        onDispose {
            if (model.days != null) LastVisitedDay.record(model.selection, model.trip.id)
        }
    }

    // A haptic tap as the edit form opens from a long press.
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(model.editHaptic) {
        if (model.editHaptic > 0) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    // The photo picker for a booking screenshot.
    val bookingImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { item ->
        if (item != null) model.scope.launch { model.importScreenshot(item) }
    }
    LaunchedEffect(model.showingBookingImagePicker) {
        if (!model.showingBookingImagePicker) return@LaunchedEffect
        model.showingBookingImagePicker = false
        bookingImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    // The open menu closes before the screen does.
    BackHandler(enabled = model.showingTripMenu) { model.closeTripMenu() }

    ForegroundStyle(Color.appForeground) {
        Box(
            modifier
                .fillMaxSize()
                .onPlaced { model.screen = it }
                .onSizeChanged { model.screenHeight = it.height.toFloat() }
        ) {
            Background(model.trip)

            val shownDays = model.days
            val errorMessage = model.errorMessage
            if (shownDays != null) {
                DayPager(model, shownDays)
                // Pinned on top of the itinerary rather than laid out with it, so its
                // (wide, scrollable) content can never change the page content behind it.
                DaySelectorBar(
                    days = shownDays,
                    selection = model.selection,
                    onSelectionChange = { model.selection = it },
                    dragProgress = model.pageDragProgress,
                    onAddSegment = { edge -> model.segmentRequest = SegmentRequest(edge = edge) },
                    segmentEdges = model.openSegmentEdges,
                    primaryTripId = model.trip.id,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // Below its fade, where the circles start.
                        .onGloballyPositioned {
                            model.dayPickerTopOnScreen =
                                model.positionOnScreen(it).y + with(density) { DaySelectorBarMetrics.fadeHeight.toPx() }
                        }
                        .revealIn(model.hasRevealed),
                )
                // An effect rather than set as the days arrive: the first frame draws
                // everything hidden, and revealing in that same frame would skip the
                // cards' entrance.
                LaunchedEffect(Unit) { model.hasRevealed = true }
            } else if (errorMessage != null) {
                ErrorState(model, errorMessage)
            }

            PinnedBlock(model, onBack)
            TripMenuOverlay(model)
            OfflinePillAnchor(model)
            ToastView(model.toast)
            ImportProgressPillOverlay(model)
        }
    }

    LodgingPopupOverlay(model)
    AddLodgingPopupOverlay(model)
    DeleteTripPopupOverlay(model)
    Sheets(model)
}

// MARK: - State

/**
 * The itinerary screen's state and what acts on it, kept in one place for the
 * screen's parts to share.
 */
@Stable
private class TripDetailModel(
    trip: Trip,
    days: List<ItineraryDay>?,
    private val auth: AuthStore,
    private val credits: CreditStore,
    /** Runs the page's scroll animations, which need the screen's frame clock. */
    private val animationScope: CoroutineScope,
) {
    var trip by mutableStateOf(trip)

    var days by mutableStateOf<List<ItineraryDay>?>(null)

    /**
     * The trips merged into the itinerary (this one and the user's others in its
     * group), for the members of whichever one an item sits on.
     */
    var itineraryTrips by mutableStateOf(listOf<Trip>())

    /**
     * The trip's days keyed by date, for the zones to default the add-item picker to.
     * Loaded alongside the itinerary (which doesn't carry zones yet) and joined on date.
     */
    var dayTimeZones by mutableStateOf(mapOf<String, List<ZoneId>>())

    /**
     * Google Place Details for every place in the itinerary, keyed by google place id.
     * The backend enriches its own place records (name/address) asynchronously, so
     * those are often still null here; we fetch Google's copy once and cache it so
     * cards can show a location immediately. See `loadPlaceDetails(days)`.
     */
    var placeDetails by mutableStateOf(mapOf<String, PlaceDetails>())

    /**
     * One copy of each place in the itinerary, by id, which every card and the item
     * sheet read from. Patched in place when stale places come back refreshed, so all
     * cards for a place update together. See `refreshStalePlaces(ids)`.
     */
    var places by mutableStateOf(ItineraryPlaces())
    var selection by mutableIntStateOf(1)
    var errorMessage by mutableStateOf<String?>(null)

    /**
     * When the itinerary showing is the copy saved by an earlier load (opened before
     * this load finished, or with no connection), the time that copy was saved; null
     * once a load succeeds.
     */
    var savedCopyDate by mutableStateOf<Instant?>(null)

    /** Whether the last load couldn't reach the server, leaving the saved copy showing. */
    var loadFailed by mutableStateOf(false)

    /**
     * A pending add-item sheet, carrying the day that was shown when add was tapped,
     * so the form receives the value directly.
     */
    var addItemRequest by mutableStateOf<AddItemRequest?>(null)

    /** The trip open in the edit form: this one, or the extension the viewed day is on. */
    var tripToEdit by mutableStateOf<Trip?>(null)

    /** The trip the delete popup is open for (see `menuTrip`). */
    var tripToDelete by mutableStateOf<Trip?>(null)

    /** A pending add-segment sheet, from the day picker's plus at either end. */
    var segmentRequest by mutableStateOf<SegmentRequest?>(null)

    /** Whether the dropdown under the header's more button is open. */
    var showingTripMenu by mutableStateOf(false)

    /** The status pill above the day picker (an address copied, a trip added). */
    var toast by mutableStateOf<TripToast?>(null)

    /** Hides the current toast after a moment; replaced whenever a new toast shows. */
    private var toastHideTask: Job? = null

    /**
     * Where an in-flight screenshot import has got to, shown in the progress pill;
     * null when none is running.
     */
    var importStep by mutableStateOf<ImportProgressPillStep?>(null)

    /** A fresh id per import, so each one's pill starts from empty. */
    var importID by mutableStateOf(UUID.randomUUID())

    /**
     * The screen's own layout, which everything on it is measured against (see
     * `positionOnScreen`).
     */
    var screen: LayoutCoordinates? = null

    /**
     * The header chip's frame on screen, in pixels, to hang the trip menu just below
     * its trailing edge.
     */
    var headerFrame by mutableStateOf(Rect.Zero)

    /**
     * The entry whose details are folded open beneath its card, from tapping it;
     * tapping it again (or another card) folds them away. Keyed by entry, not item,
     * so the panel opens under the card that was tapped (a transit's departure or
     * its arrival).
     */
    var expandedEntryID by mutableStateOf<String?>(null)

    /** The stay whose lodging popup is open, from tapping its lodging bar. */
    var popupLodging by mutableStateOf<TripItem?>(null)

    /**
     * An item opened straight into its edit form: long-pressing its card or lodging
     * strip, or Edit in the lodging popup.
     */
    var editingItem by mutableStateOf<TripItem?>(null)

    /** Bumped on each long press, to play a haptic as the edit form opens. */
    var editHaptic by mutableIntStateOf(0)

    /** The day whose add-lodging popup is open, from tapping its empty lodging bar. */
    var addLodgingDay by mutableStateOf<ItineraryDay?>(null)

    /**
     * Showing the photo picker for a screenshot of a booking, from the add-lodging popup
     * or the trip menu.
     */
    var showingBookingImagePicker by mutableStateOf(false)

    /**
     * Live horizontal progress of an in-flight page swipe, in day-units
     * (-1…1, negative dragging toward a later day). Lets the day picker track
     * the drag and settle in sync with the page.
     */
    var pageDragProgress by mutableFloatStateOf(0f)

    /**
     * Flips once when the itinerary first shows, playing the entrance animation: the
     * pinned header and lodging and the day picker fade in, then each card slides in
     * after the one before, then the Add Activity line. Never reset, so reloads and
     * later pages don't replay it.
     */
    var hasRevealed by mutableStateOf(false)

    /**
     * Height of the pinned header block in pixels, so the cards can scroll up
     * underneath it and fade out at its top edge.
     */
    var pinnedHeight by mutableFloatStateOf(0f)

    /**
     * Where the pinned block ends on screen, in pixels. The itinerary's positions
     * are measured down from here (the detail space): a card at rest starts just
     * below zero, and one scrolled under the block has a negative top.
     */
    var detailTop by mutableFloatStateOf(0f)

    /** The screen's height, and the navigation bar's strip at its bottom, in pixels. */
    var screenHeight by mutableFloatStateOf(0f)
    var bottomInset by mutableFloatStateOf(0f)

    /** How tall the detail space is: from the pinned block down to the navigation bar. */
    val detailHeight: Float get() = screenHeight - detailTop - bottomInset

    /**
     * Where the day picker's circles start on screen, in pixels: the bottom of the
     * band the itinerary is fully visible in.
     */
    var dayPickerTopOnScreen by mutableFloatStateOf(0f)

    /** `dayPickerTopOnScreen` in the detail space. */
    private val dayPickerTop: Float get() = dayPickerTopOnScreen - detailTop

    /**
     * Where `coordinates` sits on this screen, in pixels. Measured against the screen
     * itself rather than the window, so it's right while the screen is still zooming
     * open out of its trip's tile (scaled down and off to one side).
     */
    fun positionOnScreen(coordinates: LayoutCoordinates): Offset =
        screen?.takeIf { it.isAttached }?.localPositionOf(coordinates, Offset.Zero) ?: coordinates.positionInRoot()

    /**
     * While the page scrolls an opened card into view; touch scrolling is off so the
     * two don't fight.
     */
    var isAutoScrolling by mutableStateOf(false)

    /**
     * The card just tapped open, while its details are laid out unseen to measure
     * them. Nothing moves until that's done; then the details open, any open card
     * closes and the page scrolls, all at once, so they stay in step.
     */
    var measuringEntryID by mutableStateOf<String?>(null)

    /** The tapped card's details' height in pixels, from that unseen layout. */
    var measuredPanelHeight by mutableStateOf<Int?>(null)

    /**
     * How much taller the open card's row is than the card alone, in pixels, so
     * opening another card can allow for this one closing.
     */
    var openRowExtra = 0f

    /**
     * The latest open's id, so a stale cleanup from an earlier one leaves a newer
     * one alone.
     */
    private var autoScrollID = UUID.randomUUID()

    /** Each day page's scroll state while it's on display, by day number. */
    val pageScroll = mutableMapOf<Int, ScrollState>()

    /**
     * Extra blank space under a day's itinerary while an opened card scrolls into
     * place. The page's real height only catches up once the details finish
     * unfolding, so without it a scroll that starts alongside them is cut short at
     * the old bottom. Removed once the scroll lands, which never goes past the real
     * bottom, so nothing moves when it goes.
     */
    var scrollRunway by mutableStateOf(mapOf<Int, Dp>())

    /**
     * The rows of an item just deleted, kept where they were on their days for a
     * moment while they fade away.
     */
    var leavingEntries by mutableStateOf(listOf<LeavingEntry>())
        private set

    /** Runs what should finish whether or not the screen is still open, like an upload. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val api = APIClient.shared

    /**
     * The user's other trips in this trip's group; an end of the day picker one of them
     * reaches gets no add-segment button.
     */
    var groupTrips: List<Trip> = emptyList()

    /** Called with a segment created from the day picker, for the trip list. */
    var onSegmentCreated: (Trip) -> Unit = {}

    /** Called after the trip is deleted from its edit form; the presenter closes this screen. */
    var onDeleted: () -> Unit = {}

    /** The screen's density, for the measurements kept in pixels. */
    var density: Density = Density(1f)

    init {
        // Without days given, start from the itinerary saved by an earlier load (or the
        // trip list's background save), so it shows at once, and offline; the load
        // then replaces it.
        val endpoint = TripsAPI.itinerary(tripId = trip.id)
        val saved = if (days == null) api.cached<Itinerary>(endpoint) else null
        this.days = days ?: saved?.days
        itineraryTrips = saved?.trips ?: emptyList()
        places = saved?.let { ItineraryPlaces(it.days) } ?: ItineraryPlaces()
        savedCopyDate = if (saved == null) null else api.cachedDate(endpoint)
        // Back on the day last viewed when the trip was opened within the hour; a day
        // that no longer exists falls back to the first once the itinerary loads.
        selection = days?.firstOrNull()?.dayNumber ?: LastVisitedDay.dayNumber(trip.id) ?: 1
    }

    // MARK: Header

    /** Copies the trips inbox address and shows a toast confirming it for a moment. */
    fun copyTripsEmail() {
        val clipboard = OutboundApp.context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("Email address", AddLodgingPopupMetrics.tripsEmail))
        showToast(TripToast(text = "Email address copied", systemImage = Icons.Rounded.CheckCircle))
    }

    /**
     * Sends a picked booking screenshot to be processed into itinerary items, then
     * reloads the itinerary to pick up what the server made of it. The progress pill
     * shows the upload, then the server's work once the image is sent, then done once
     * the server answers (it responds only when processing has finished).
     */
    suspend fun importScreenshot(item: Uri) {
        val id = UUID.randomUUID()
        importID = id
        importStep = ImportProgressPillStep.Uploading
        // Moves this import's pill on, unless it's been dismissed or replaced since.
        fun show(step: ImportProgressPillStep) {
            if (importID != id || importStep == null) return
            importStep = step
            if (step == ImportProgressPillStep.Added || step == ImportProgressPillStep.Failed) {
                hideImportPill(id, after = 60.seconds)
            }
        }
        try {
            val file = uploadFile(item)
            // A request's own timeout only limits gaps between data, so cap the whole
            // call here.
            val result = withTimeout(ReceivedImagesAPI.processTimeout) {
                api.send<ImageImportResult>(ReceivedImagesAPI.process(file)) {
                    // Only forward: on a quick response this can land after the steps below.
                    scope.launch { if (importStep == ImportProgressPillStep.Uploading) show(ImportProgressPillStep.Extracting) }
                }
            }
            // Anything but placing the booking in a trip (not a booking, no matching
            // trip, or several) counts as a failed import.
            if (result.outcome != "placed") {
                show(ImportProgressPillStep.Failed)
                return
            }
            show(ImportProgressPillStep.Added)
            load()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            show(ImportProgressPillStep.Failed)
        }
    }

    /**
     * Hides import `id`'s pill, after `after` if given; a newer import's pill is left
     * alone.
     */
    fun hideImportPill(id: UUID, after: Duration? = null) {
        scope.launch {
            if (after != null) delay(after)
            if (importID != id) return@launch
            importStep = null
        }
    }

    /**
     * Runs `work`, throwing `ScreenshotError.TimedOut` if it takes longer than
     * `duration`; the work is cancelled once the time is up.
     */
    private suspend fun <T> withTimeout(duration: Duration, work: suspend () -> T): T =
        try {
            kotlinx.coroutines.withTimeout(duration) { work() }
        } catch (_: TimeoutCancellationException) {
            throw ScreenshotError.TimedOut()
        }

    /**
     * The picked image as an upload: PNGs and JPEGs as they are, anything else (e.g.
     * HEIC or WebP photos) re-encoded as JPEG so the server can read it. Read off the
     * main thread.
     */
    private suspend fun uploadFile(item: Uri): MultipartFile = withContext(Dispatchers.IO) {
        val resolver = OutboundApp.context.contentResolver
        try {
            val type = resolver.getType(item)
            if (type == "image/png" || type == "image/jpeg") {
                val data = resolver.openInputStream(item)?.use { it.readBytes() } ?: throw ScreenshotError.Unreadable()
                MultipartFile(field = "file", filename = "screenshot.${type.substringAfter('/')}", contentType = type, data = data)
            } else {
                MultipartFile(field = "file", filename = "screenshot.jpg", contentType = "image/jpeg", data = jpegData(resolver, item))
            }
        } catch (_: IOException) {
            throw ScreenshotError.Unreadable()
        } catch (_: SecurityException) {
            throw ScreenshotError.Unreadable()
        }
    }

    /**
     * The image at `item` as a JPEG, the right way up. One bigger than
     * `maxUploadDimension` is read at a half, a quarter… of its size, which keeps a
     * camera's largest photos from running the app out of memory.
     */
    private fun jpegData(resolver: ContentResolver, item: Uri): ByteArray {
        fun sampleSize(width: Int, height: Int): Int {
            var sampleSize = 1
            while (maxOf(width, height) / sampleSize > maxUploadDimension) sampleSize *= 2
            return sampleSize
        }
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, item)) { decoder, info, _ ->
                // In ordinary memory, where it can be compressed from.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSampleSize(sampleSize(info.size.width, info.size.height))
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(item)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
            resolver.openInputStream(item)?.use { BitmapFactory.decodeStream(it, null, options) }
        } ?: throw ScreenshotError.Unreadable()
        try {
            val jpeg = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, jpeg)) throw ScreenshotError.Unreadable()
            return jpeg.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /** The longest side, in pixels, an image re-encoded for upload is read at. */
    private val maxUploadDimension = 4096

    /** Shows a toast, hiding it after `hideAfter` (null keeps it up until replaced). */
    fun showToast(newToast: TripToast, hideAfter: Duration? = 1.8.seconds) {
        toastHideTask?.cancel()
        toast = newToast
        if (hideAfter == null) return
        toastHideTask = scope.launch {
            delay(hideAfter)
            toast = null
        }
    }

    /** Deletes the trip from its menu, then leaves it for the presenter to close. */
    fun deleteTrip(target: Trip) {
        scope.launch {
            try {
                api.sendIgnoringResponse(TripsAPI.delete(id = target.id))
                // The refund, if any, is on the balance now.
                scope.launch { credits.load() }
                tripDeleted(target)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                showToast(TripToast(text = "Couldn't delete the trip", systemImage = Icons.Rounded.Warning))
            }
        }
    }

    /**
     * After `deleted` is gone: this trip closes the screen; an extension of it just
     * drops out of the merged itinerary.
     */
    fun tripDeleted(deleted: Trip) {
        if (deleted.id == trip.id) {
            onDeleted()
        } else {
            reload()
        }
    }

    /**
     * The trip the menu's trip actions apply to: the extension the viewed day is on,
     * or this trip (also for days no trip covers).
     */
    val menuTrip: Trip
        get() {
            val tripId = selectedDay?.tripId
            if (tripId == null || tripId == trip.id) return trip
            return itineraryTrips.firstOrNull { it.id == tripId } ?: trip
        }

    /** Whether the signed-in user owns the menu's trip, and so may delete it. */
    val ownsMenuTrip: Boolean get() = menuTrip.ownerId == auth.currentUser?.id

    val currentUserId: String? get() = auth.currentUser?.id

    fun closeTripMenu() {
        showingTripMenu = false
    }

    /**
     * The ends of the trip with no group trip of the user's past them yet; one segment
     * on each side is all a user needs.
     */
    val openSegmentEdges: Set<HorizontalEdge>
        get() {
            val edges = mutableSetOf(HorizontalEdge.Leading, HorizontalEdge.Trailing)
            for (other in groupTrips) {
                if (other.id == trip.id) continue
                // `trip`'s dates may already span its group (as the list sends them), so a
                // trip reaching either end counts.
                if (other.startDate <= trip.startDate) edges.remove(HorizontalEdge.Leading)
                if (other.endDate >= trip.endDate) edges.remove(HorizontalEdge.Trailing)
            }
            return edges
        }

    // MARK: Paged days

    /**
     * Once a day change has landed, folds away any open card's details: the day it
     * was on is off screen by then, and comes back as it first appears, at the top
     * with every card shut. Left alone until then so the page doesn't change as it
     * slides away.
     */
    fun collapseOpenCard() {
        for ((dayNumber, scroll) in pageScroll) {
            if (dayNumber != selection && scroll.value != 0) animationScope.launch { scroll.scrollTo(0) }
        }
        if (expandedEntryID == null && measuringEntryID == null) return
        expandedEntryID = null
        measuringEntryID = null
    }

    // MARK: Rows

    /** Opens an item's edit form directly, with a haptic tap to confirm the long press. */
    fun editItem(item: TripItem) {
        editHaptic += 1
        editingItem = places.resolving(item)
    }

    /**
     * Where an opened card's row should sit, measured down from the top of the page's
     * visible area (just under the pinned block's bottom padding): the card and its
     * details centered between the pinned block and the day picker, or, when they're
     * too tall for that, `expandedHeaderGap` below the pinned block.
     *
     * The row carries the card's 6dp padding above and below, which doesn't count
     * toward what's seen.
     */
    private fun expandedRowTop(rowHeight: Float): Float = with(density) {
        val rowPadding = TripDetailViewMetrics.rowPadding.toPx()
        val bandTop = -TripDetailViewMetrics.pinnedBlockBottomPadding.toPx()
        val band = dayPickerTop - bandTop
        val visibleHeight = rowHeight - rowPadding * 2
        val gap = maxOf((band - visibleHeight) / 2, TripDetailViewMetrics.expandedHeaderGap.toPx())
        bandTop + gap - rowPadding
    }

    /**
     * Starts opening a card: makes room to scroll into, holds off touches, and lays
     * its details out unseen to measure them (see `finishOpening`).
     */
    fun beginOpening(entry: ItineraryEntry, day: ItineraryDay) {
        val id = UUID.randomUUID()
        autoScrollID = id
        scrollRunway = scrollRunway + (day.dayNumber to TripDetailViewMetrics.scrollRunwayHeight)
        isAutoScrolling = true
        measuredPanelHeight = null
        measuringEntryID = entry.id
        // A backstop, whatever happened since: if the measuring never finished, open
        // the card anyway; either way, end the auto-scroll, so a measure cut short (a
        // day change landing mid-tap clears it) can't leave the page untouchable.
        // Harmless after a normal open, which has already ended it by now.
        scope.launch {
            delay(1.seconds)
            if (autoScrollID != id) return@launch
            if (measuringEntryID == entry.id) {
                measuringEntryID = null
                expandedEntryID = entry.id
            }
            endAutoScroll(id, day.dayNumber)
        }
    }

    /**
     * Once the tapped card's details are measured: works out where its row will
     * sit once open (allowing for any other open card on the page closing), then
     * opens it, closes the other and scrolls the page there, all on one curve.
     *
     * `rowTop` (in the detail space) and `rowHeight` are the row as it sits now, and
     * `anchor` how far its details tuck up behind the card, all in pixels.
     */
    fun finishOpening(entry: ItineraryEntry, day: ItineraryDay, anchor: Float, rowTop: Float, rowHeight: Float) {
        val panelHeight = measuredPanelHeight
        if (measuringEntryID != entry.id || panelHeight == null) return
        measuringEntryID = null
        val id = autoScrollID
        val dayNumber = day.dayNumber

        // The card open now on this page (if any) closes as this one opens, taking its
        // extra height with it; if it's above, this row moves up by that much.
        val entryIDs = day.entries.map { it.id }
        val closingIndex = expandedEntryID?.let { entryIDs.indexOf(it) }?.takeIf { it >= 0 }
        val closingExtra = if (closingIndex == null) 0f else openRowExtra
        val closingAbove = closingIndex != null && closingIndex < maxOf(entryIDs.indexOf(entry.id), 0)

        // The row's added height: its details, less where they tuck under the card.
        val addedHeight = panelHeight - (with(density) { TripDetailViewMetrics.rowPadding.toPx() } + anchor)
        val finalTop = rowTop - (if (closingAbove) closingExtra else 0f)
        val finalHeight = rowHeight + addedHeight

        val scroll = pageScroll[dayNumber]
        expandedEntryID = entry.id
        if (scroll != null) {
            // Kept within how far the page can scroll once settled (without the
            // runway), so a card near the end of a short day stops short of centered
            // rather than overshooting and snapping back when the runway goes.
            val runway = with(density) { (scrollRunway[dayNumber] ?: 0.dp).toPx() }
            val finalMaxY = maxOf(scroll.maxValue - runway - closingExtra + addedHeight, 0f)
            val y = (scroll.value + finalTop - expandedRowTop(finalHeight)).coerceIn(0f, finalMaxY)
            animationScope.launch { scroll.animateScrollTo(y.roundToInt(), TripDetailViewMetrics.expandAnimation()) }
        }
        // On a timer rather than when the scroll ends, so it's the same whether or
        // not there was anywhere to scroll.
        scope.launch {
            delay((TripDetailViewMetrics.expandDuration + 0.15).seconds)
            endAutoScroll(id, dayNumber)
        }
    }

    /**
     * Takes the runway away and lets touches back in, unless a newer open has
     * started since.
     */
    private fun endAutoScroll(id: UUID, dayNumber: Int) {
        if (autoScrollID != id) return
        scrollRunway = scrollRunway - dayNumber
        isAutoScrolling = false
    }

    /**
     * What to call a stay: its place's name, as the itinerary cards do, else its own
     * title (which may be something generic like "Check-in").
     */
    fun lodgingName(item: TripItem): String {
        val place = places.current(item.place)
        return place?.googlePlaceId?.let { placeDetails[it] }?.name ?: place?.name ?: item.displayTitle
    }

    /** A stay's address, from Google's details when they're in, else the backend's. */
    fun lodgingAddress(item: TripItem): String? {
        val place = places.current(item.place)
        return place?.googlePlaceId?.let { placeDetails[it] }?.address ?: place?.address
    }

    fun showLodgingPopup(item: TripItem) {
        popupLodging = item
    }

    fun closeDeleteTrip() {
        tripToDelete = null
    }

    fun closeLodgingPopup() {
        popupLodging = null
    }

    fun memberSelection(tripId: String?): ItemMemberSelection =
        ItemMemberSelection(members = members(tripId), currentUserId = auth.currentUser?.id)

    /**
     * The trip owning each itinerary day, by date, for a new item to save to the trip
     * its date falls in. Days no trip covers are left out.
     */
    val tripsByDate: Map<String, Trip>
        get() {
            val trips = itineraryTrips.reversed().associateBy { it.id }
            return (days ?: emptyList()).reversed()
                .mapNotNull { day -> day.tripId?.let { trips[it] }?.let { day.date to it } }
                .toMap()
        }

    /**
     * The members of one of the itinerary's trips: an item's participants are member
     * rows on its own trip. Falls back to this trip's.
     */
    fun members(tripId: String?): List<TripMember> =
        itineraryTrips.firstOrNull { it.id == tripId }?.members ?: trip.members ?: emptyList()

    fun closeAddLodgingPopup() {
        addLodgingDay = null
    }

    /** The day currently shown in the pager. */
    val selectedDay: ItineraryDay?
        get() = days?.firstOrNull { it.dayNumber == selection }

    /**
     * The day to show after a reload: the day holding the just-saved item when given,
     * otherwise the current day if it still exists, falling back to the first day. Never
     * a gap day, which has no page (e.g. once the last item on a day outside the trips
     * is deleted).
     */
    private fun resolveSelection(days: List<ItineraryDay>, focusItemId: String?): Int {
        if (focusItemId != null) {
            val day = days.firstOrNull { day -> day.uniqueItems.any { it.id == focusItemId } }
            if (day != null) return day.dayNumber
        }
        if (days.any { it.dayNumber == selection && !it.isGap }) return selection
        return days.firstOrNull()?.dayNumber ?: 1
    }

    /**
     * Drops a deleted item from the itinerary straight away, then reloads to pick up
     * anything the server derived from it.
     */
    fun removeItem(id: String) {
        val leaving = days.orEmpty().flatMap { LeavingEntry.entries(it, itemId = id) }
        leavingEntries = leavingEntries + leaving
        days = days?.map { it.removing(itemId = id) }
        scope.launch {
            delay(TripDetailViewMetrics.rowLeaveDuration)
            leavingEntries = leavingEntries - leaving.toSet()
        }
        reload()
    }

    /** Reloads the itinerary without waiting for it; see `load`. */
    fun reload(focusItemId: String? = null) {
        scope.launch { load(focusItemId) }
    }

    /**
     * Reloads the itinerary. Pass `focusItemId` after a save to land on the day that
     * item sits on; otherwise the current day is kept (falling back to the first).
     */
    suspend fun load(focusItemId: String? = null) {
        try {
            val itinerary = api.send<Itinerary>(TripsAPI.itinerary(tripId = trip.id))
            days = itinerary.days
            itineraryTrips = itinerary.trips
            places = ItineraryPlaces(itinerary.days)
            selection = resolveSelection(itinerary.days, focusItemId)
            errorMessage = null
            savedCopyDate = null
            loadFailed = false
            // The itinerary is already on screen; stale places refresh alongside the
            // Google details and patch their cards when they land.
            coroutineScope {
                val refresh = async { refreshStalePlaces(itinerary.stalePlaceIds) }
                loadPlaceDetails(itinerary.days)
                refresh.await()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // With a saved copy showing, this just marks it as possibly out of date.
            errorMessage = error.localizedDescription
            loadFailed = true
        }
        // The day zones are a best-effort join for the add-item picker; a failure here
        // shouldn't block the itinerary, so it's loaded separately and ignored on error.
        try {
            val tripDays = api.send<List<TripDay>>(TripDaysAPI.list(tripId = trip.id))
            dayTimeZones = tripDays.reversed().associate { it.date to it.timeZones }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        }
    }

    /**
     * Fetches Google Place Details for every place referenced by the itinerary and
     * caches them by google place id, so cards can render a name/address even before
     * the backend has enriched its own place records. Best-effort: places already
     * cached are skipped, and individual lookups that fail are simply left out.
     */
    private suspend fun loadPlaceDetails(days: List<ItineraryDay>) {
        val ids = days.flatMap { it.places }.mapNotNull { it.googlePlaceId }.toSet()
        val missing = ids - placeDetails.keys
        if (missing.isEmpty()) return

        val fetched = coroutineScope {
            missing.map { id ->
                async {
                    try {
                        id to GooglePlacesClient.shared.placeDetails(placeId = id)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        placeDetails = placeDetails + fetched
    }

    /**
     * Asks the server to refresh the places the itinerary flagged as stale (in batches
     * of `PlacesAPI.refreshBatchLimit`), then patches the shared copies so every card
     * for those places picks up the new data, cover photo included. Best-effort: a
     * failed batch leaves its places as the itinerary delivered them.
     */
    private suspend fun refreshStalePlaces(ids: List<String>) {
        if (ids.isEmpty()) return
        for (batch in ids.chunked(PlacesAPI.refreshBatchLimit)) {
            val response = try {
                api.send<PlacesAPI.RefreshResponse>(PlacesAPI.refresh(placeIds = batch))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                continue
            }
            places.update(response.places)
        }
    }
}

// MARK: - Header

/**
 * The header and the day's lodging, pinned under the status bar while the cards
 * scroll beneath, joined into one block: square where they meet, rounded on the
 * outside.
 */
@Composable
private fun PinnedBlock(model: TripDetailModel, onBack: () -> Unit) {
    val selectedDay = model.selectedDay
    Column(
        Modifier
            .statusBarsPadding()
            .onGloballyPositioned {
                model.pinnedHeight = it.size.height.toFloat()
                model.detailTop = model.positionOnScreen(it).y + it.size.height
            }
            .padding(horizontal = 16.dp)
            .padding(bottom = TripDetailViewMetrics.pinnedBlockBottomPadding)
            .revealIn(model.hasRevealed || model.days == null)
            // Touches on the block stop here, rather than scrolling the page behind it.
            .pointerInput(Unit) {},
    ) {
        Header(model, joinsLodging = selectedDay != null, onBack = onBack)
        if (selectedDay != null) {
            LodgingBar(
                dayNumber = selectedDay.dayNumber,
                lodging = selectedDay.lodging,
                name = model::lodgingName,
                onOpen = model::showLodgingPopup,
                onEdit = model::editItem,
                onAdd = { model.addLodgingDay = selectedDay },
            )
        }
    }
}

/**
 * A solid chip standing in for the navigation bar: back on the left, then the trip's
 * title and the day being viewed (left aligned, taking the free width, truncating the
 * title first), then more on the right, which opens the trip menu (adding items too).
 * Squared off at the bottom when the day's lodging bar hangs below it.
 */
@Composable
private fun Header(model: TripDetailModel, joinsLodging: Boolean, onBack: () -> Unit) {
    val selectedDay = model.selectedDay
    val bottomRadius = if (joinsLodging) 0.dp else 10.dp
    val shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomStart = bottomRadius, bottomEnd = bottomRadius)
    val moreHighlight by animateFloatAsState(
        targetValue = if (model.showingTripMenu) 0.18f else 0f,
        animationSpec = AppAnimation.snappy(0.25),
        label = "more",
    )
    ForegroundStyle(Color.White) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned {
                    model.headerFrame = Rect(model.positionOnScreen(it), it.size.toSize())
                }
                .frosted(tint = Color.tripHeader.copy(alpha = 0.7f), shape = shape)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .plainClickable(onClick = onBack)
                    .semantics { contentDescription = "Back" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.ArrowBackIosNew, contentDescription = null, modifier = Modifier.size(18.dp))
            }

            Row(
                modifier = Modifier.weight(1f).semantics(mergeDescendants = true) { heading() },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    model.trip.title,
                    style = AppFont.subheadline.semibold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (selectedDay != null) {
                    // The number rolls up to a later day and down to an earlier one.
                    AnimatedContent(
                        targetState = selectedDay.dayNumber,
                        transitionSpec = {
                            val direction = if (targetState > initialState) 1 else -1
                            (slideInVertically(AppAnimation.snappy()) { it * direction } + fadeIn(AppAnimation.snappy()))
                                .togetherWith(slideOutVertically(AppAnimation.snappy()) { -it * direction } + fadeOut(AppAnimation.snappy()))
                                .using(SizeTransform(clip = false))
                        },
                        label = "day",
                    ) { dayNumber ->
                        Text(
                            "Day $dayNumber",
                            style = AppFont.subheadline.semibold,
                            color = Color.White.copy(alpha = 0.6f),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }

            Box(
                Modifier
                    .size(32.dp)
                    .background(Color.White.copy(alpha = moreHighlight), CircleShape)
                    .plainClickable { model.showingTripMenu = !model.showingTripMenu }
                    .semantics { contentDescription = "More" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.MoreHoriz, contentDescription = null, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/**
 * The screenshot import's progress, above the day picker. Tapping it dismisses it
 * (the import carries on); it also goes by itself a minute after finishing.
 */
@Composable
private fun BoxScope.ImportProgressPillOverlay(model: TripDetailModel) {
    val step = model.importStep
    var shownStep by remember { mutableStateOf(step) }
    if (step != null && shownStep != step) shownStep = step
    // Drops a short way while fading out (and rises the same way in), rather than
    // sliding off the screen.
    val drop = with(LocalDensity.current) { 50.dp.roundToPx() }

    AnimatedVisibility(
        visible = step != null,
        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        enter = slideInVertically(AppAnimation.snappy()) { drop } + fadeIn(AppAnimation.snappy()),
        exit = slideOutVertically(AppAnimation.snappy()) { drop } + fadeOut(AppAnimation.snappy()),
    ) {
        val importID = model.importID
        key(importID) {
            shownStep?.let { shown ->
                ImportProgressPill(
                    step = shown,
                    modifier = Modifier
                        // 60% of the screen's width, centered.
                        .fillMaxWidth(0.6f)
                        // Just above the day picker pinned to the bottom.
                        .padding(bottom = TripDetailViewMetrics.dayPickerClearance)
                        .plainClickable(dimsWhenPressed = false, onClickLabel = "Dismiss") { model.hideImportPill(importID) },
                )
            }
        }
    }
}

/**
 * Places the offline pill just above the day picker, saying how old the saved
 * copy is. Steps aside while a toast or the import pill has the spot.
 */
@Composable
private fun BoxScope.OfflinePillAnchor(model: TripDetailModel) {
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .fillMaxWidth()
            .height(TripDetailViewMetrics.dayPickerClearance)
            .offlinePillAnchor(
                savedDate = if (model.loadFailed) model.savedCopyDate else null,
                // With a connection too, while the server can't be reached.
                showsWhenOnline = model.savedCopyDate != null && model.loadFailed,
                isHidden = model.toast != null || model.importStep != null,
            )
    )
}

/** A small pill above the day picker: a spinner or icon, then the message. */
@Composable
private fun BoxScope.ToastView(toast: TripToast?) {
    AnimatedContent(
        targetState = toast,
        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        transitionSpec = {
            (slideInVertically(AppAnimation.snappy(0.25)) { it } + fadeIn(AppAnimation.snappy(0.25)))
                .togetherWith(slideOutVertically(AppAnimation.snappy(0.25)) { it } + fadeOut(AppAnimation.snappy(0.25)))
                .using(SizeTransform(clip = false))
        },
        contentAlignment = Alignment.BottomCenter,
        label = "toast",
    ) { shown ->
        if (shown != null) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    // Clear of the day picker pinned to the bottom.
                    .padding(bottom = 96.dp)
                    .floatingPillBackground(RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    // Read out as it shows.
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (shown.systemImage != null) {
                    Icon(shown.systemImage, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                } else {
                    ProgressView(controlSize = ControlSize.Small, tint = Color.White)
                }
                Text(shown.text, style = AppFont.subheadline.semibold, color = Color.White, textAlign = TextAlign.Start)
            }
        }
    }
}

/**
 * The open trip menu, hung below the header's trailing edge; tapping anywhere else
 * closes it.
 */
@Composable
private fun TripMenuOverlay(model: TripDetailModel) {
    val density = LocalDensity.current
    var width by remember { mutableIntStateOf(0) }
    val origin = TransformOrigin(1f, 0f)

    Box(Modifier.fillMaxSize().onSizeChanged { width = it.width }) {
        if (model.showingTripMenu) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { model.closeTripMenu() } })
        }
        AnimatedVisibility(
            visible = model.showingTripMenu,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(
                    top = with(density) { model.headerFrame.bottom.toDp() } + 6.dp,
                    end = with(density) { maxOf(width - model.headerFrame.right, 0f).toDp() },
                ),
            enter = scaleIn(AppAnimation.snappy(0.25), initialScale = 0.9f, transformOrigin = origin) + fadeIn(AppAnimation.snappy(0.25)),
            exit = scaleOut(AppAnimation.snappy(0.2), targetScale = 0.9f, transformOrigin = origin) + fadeOut(AppAnimation.snappy(0.2)),
        ) {
            val menuTrip = model.menuTrip
            TripMenu(
                trip = menuTrip,
                onAddItem = { kind ->
                    model.closeTripMenu()
                    model.addItemRequest = AddItemRequest(date = model.selectedDay?.calendarDate, kind = kind)
                },
                onImportScreenshot = {
                    model.closeTripMenu()
                    model.showingBookingImagePicker = true
                },
                onCopyTripsEmail = {
                    model.closeTripMenu()
                    model.copyTripsEmail()
                },
                onEditTrip = {
                    model.closeTripMenu()
                    model.tripToEdit = menuTrip
                },
                // Not built yet.
                onShare = { model.closeTripMenu() },
                onDeleteTrip = if (model.ownsMenuTrip) {
                    {
                        model.closeTripMenu()
                        model.tripToDelete = menuTrip
                    }
                } else {
                    null
                },
            )
        }
    }
}

/**
 * A fill for the pinned header, dark enough that the cards scrolling underneath
 * only show through faintly, with `tint` laid over it. Kept dark whatever the
 * system appearance.
 */
private fun Modifier.frosted(tint: Color, shape: RoundedCornerShape): Modifier =
    clip(shape).background(Color.white(0.12f, 0.75f)).background(tint)

// MARK: - Background

/**
 * The trip's cover art (the same image as its tile in the grid), dimmed to a dark
 * wash so the cards stay legible. Matching the tile lets opening a trip read as the
 * tile growing into this screen.
 */
@Composable
private fun Background(trip: Trip) {
    Box(Modifier.fillMaxSize().background(Color.white(0.12f)).clipToBounds()) {
        TripCover(trip = trip, showsAttribution = false, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color.white(0.12f, 0.8f)))
    }
}

// MARK: - Paged days

@Composable
private fun DayPager(model: TripDetailModel, days: List<ItineraryDay>) {
    val density = LocalDensity.current
    // Gap days between the user's trips show only in the day picker.
    val pages = remember(days) { days.filter { !it.isGap } }
    val zoneChanges = remember(days) { ItineraryZoneChanges.changes(days) }
    DayPager(
        days = pages,
        selection = model.selection,
        onSelectionChange = { model.selection = it },
        dragProgress = model.pageDragProgress,
        onDragProgressChange = { model.pageDragProgress = it },
        onSettled = model::collapseOpenCard,
        // The pages run up behind the pinned block so cards slide up under it; this
        // cuts them off near the block's top, fading in over the first stretch so they
        // dissolve rather than end at a hard line.
        modifier = Modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val inset = TripDetailViewMetrics.pinnedBlockBottomPadding.toPx()
                val top = model.detailTop - maxOf(model.pinnedHeight - inset, 0f)
                val fade = 32.dp.toPx()
                if (size.height > 0f && top + fade > 0f) {
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            (top / size.height).coerceIn(0f, 1f) to Color.Transparent,
                            ((top + fade) / size.height).coerceIn(0f, 1f) to Color.Black,
                            1f to Color.Black,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
            },
    ) { day, scrollLocked ->
        DayPage(model, day, zoneChanges, scrollLocked, topInset = with(density) { model.detailTop.toDp() })
    }
}

@Composable
private fun DayPage(
    model: TripDetailModel,
    day: ItineraryDay,
    zoneChanges: Map<String, ZoneId>,
    scrollLocked: Boolean,
    topInset: Dp,
) {
    val density = LocalDensity.current
    val isScrollLocked by rememberUpdatedState(scrollLocked)
    // One scroll state per day: a page starts at the top each time its day comes
    // back on display.
    val scroll = remember { ScrollState(0) }
    DisposableEffect(day.dayNumber) {
        model.pageScroll[day.dayNumber] = scroll
        onDispose {
            if (model.pageScroll[day.dayNumber] === scroll) model.pageScroll.remove(day.dayNumber)
        }
    }
    val topFadeReach = with(density) { TripDetailViewMetrics.topFadeReach.toPx() }
    // How far the page has scrolled toward revealing the top fade, 0 at rest to 1 once
    // scrolled `topFadeReach`. Clamped, so it stops changing once scrolled past the reach.
    val topFade = { (scroll.value / topFadeReach).coerceIn(0f, 1f) }
    val bottomInset = with(density) { model.bottomInset.toDp() }
    val rowSpacing = TripDetailViewMetrics.rowSpacing
    val leaving = model.leavingEntries.filter { it.dayNumber == day.dayNumber }
    val rows = remember(day.entries, leaving) { dayRows(day.entries, leaving) }

    // Each row carries the gap to the next under it rather than the column spacing
    // them, so the gap goes with a deleted row as it shrinks away.
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Untouchable while an opened card scrolls into place, so a finger can't fight
            // it. (Not by disabling scrolling, which a swipe between days does.) And while
            // the pages are swiped or slide to another day, so a swipe that starts on a
            // card or a button moves the page without also tapping what's under the
            // finger, and nothing on a page in motion can be tapped.
            .allowsHitTesting { !model.isAutoScrolling && !isScrollLocked }
            .verticalScroll(scroll, enabled = !scrollLocked)
            .padding(horizontal = 16.dp)
            // Under the pinned block, which the cards scroll up behind.
            .padding(top = topInset + 8.dp),
    ) {
        if (rows.isEmpty() && day.lodging.isEmpty()) {
            Text(
                "Nothing planned",
                style = AppFont.subheadline.copy(fontStyle = FontStyle.Italic),
                color = Color.appForeground.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 100.dp)
                    .padding(bottom = rowSpacing)
                    .revealIn(model.hasRevealed, delay = TripDetailViewMetrics.cardRevealDelay(0)),
            )
        } else {
            rows.forEachIndexed { index, row ->
                val entry = row.entry
                key(entry.id) {
                    val delay = TripDetailViewMetrics.cardRevealDelay(index)
                    // A deleted item's row fades where it is while those below close up.
                    AnimatedVisibility(
                        visible = !row.isLeaving,
                        enter = EnterTransition.None,
                        exit = fadeOut(AppAnimation.snappy()) + shrinkVertically(AppAnimation.snappy()),
                    ) {
                        Column(Modifier.padding(bottom = rowSpacing), verticalArrangement = Arrangement.spacedBy(rowSpacing)) {
                            zoneChanges[entry.id]?.let { zone ->
                                ZoneChangeDivider(zone, entry.displayInstant, Modifier.revealIn(model.hasRevealed, delay = delay))
                            }
                            ItemRow(model, entry, day, topFade, Modifier.revealIn(model.hasRevealed, delay = delay, slide = 16.dp))
                        }
                    }
                }
            }
        }

        // Centered between faint rules that run out to the content's edges.
        Row(
            modifier = Modifier
                // Less the buttons' own 6dp above and below.
                .padding(top = 8.dp, bottom = 4.dp + rowSpacing)
                .revealIn(model.hasRevealed, delay = TripDetailViewMetrics.cardRevealDelay(day.entries.size) + 0.05),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AddActivityRule()
            // One plus for both: "+ ACTIVITY | TRANSIT", each word its own button.
            ForegroundStyle(Color.appForeground.copy(alpha = 0.6f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                    AddItemButton(model, "Activity", TripItemKind.Activity, day.calendarDate)
                    Box(Modifier.size(width = 1.dp, height = 10.dp).background(Color.appForeground.copy(alpha = 0.3f)))
                    AddItemButton(model, "Transit", TripItemKind.Transit, day.calendarDate)
                }
            }
            AddActivityRule()
        }

        // Clear the bottom day-picker bar and the offline pill just above it,
        // whether or not the pill is showing, so the end of the day never shifts.
        Spacer(Modifier.height(168.dp + bottomInset + (model.scrollRunway[day.dayNumber] ?: 0.dp)))
    }
}

/** One row of a day's itinerary: an entry, or one whose item was just deleted, on its way out. */
private class DayRow(val entry: ItineraryEntry, val isLeaving: Boolean)

/** A day's entries in order, with the `leaving` ones back where they were among them. */
private fun dayRows(entries: List<ItineraryEntry>, leaving: List<LeavingEntry>): List<DayRow> {
    fun leavingRows(after: String?) = leaving.filter { it.after == after }.map { DayRow(it.entry, isLeaving = true) }
    return leavingRows(after = null) + entries.flatMap { listOf(DayRow(it, isLeaving = false)) + leavingRows(after = it.id) }
}

/**
 * An entry of an item that was just deleted, with where it sat: on which day, and
 * under which of the entries that are staying (`after`, by id; null at the top).
 */
private class LeavingEntry(val dayNumber: Int, val entry: ItineraryEntry, val after: String?) {
    companion object {
        /** `day`'s entries for the item, which is about to be removed from it. */
        fun entries(day: ItineraryDay, itemId: String): List<LeavingEntry> {
            var after: String? = null
            return day.entries.mapNotNull { entry ->
                if (entry.item.id == itemId) {
                    LeavingEntry(day.dayNumber, entry, after)
                } else {
                    after = entry.id
                    null
                }
            }
        }
    }
}

/**
 * Stops touches from reaching what's inside while `enabled` says not to let them.
 * `enabled` is kept from the first composition, so it must read state rather than
 * capture a value.
 */
private fun Modifier.allowsHitTesting(enabled: () -> Boolean): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (!enabled()) event.changes.forEach { it.consume() }
            }
        }
    }

// MARK: - Rows

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemRow(
    model: TripDetailModel,
    entry: ItineraryEntry,
    day: ItineraryDay,
    topFade: () -> Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val itemMembers = model.memberSelection(entry.item.tripId)
    val going = itemMembers.initialSelection(entry.item)
    // Open items others are doing shrink to a one-line card.
    val isCondensed = entry.item.visibility == TripItemVisibility.Open && itemMembers.includesCurrentUser(entry.item) == false
    val isExpanded = model.expandedEntryID == entry.id
    val isMeasuring = model.measuringEntryID == entry.id
    // The condensed card is see-through, so the panel can't sit up behind it.
    val panelAnchor = if (isCondensed) 0.dp else TripItemDetailPanelMetrics.anchor
    // Pulls the panel's top up over the card's own 6dp bottom padding, then
    // `panelAnchor` further, behind the card's bottom edge.
    val overlap = with(density) { (TripDetailViewMetrics.rowPadding + panelAnchor).toPx() }
    val item = model.places.resolving(entry.item)

    // How far the details have unfolded, 0 shut to 1 open.
    val unfold by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        animationSpec = TripDetailViewMetrics.expandAnimation(),
        label = "unfold",
    )
    /** The row's top in the detail space, and its height, in pixels. */
    var rowTop by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableIntStateOf(0) }

    // Just tapped: once its details are measured, `finishOpening` works out the
    // scroll from them and from where the row sits now.
    if (isMeasuring) {
        LaunchedEffect(Unit) {
            snapshotFlow { model.measuredPanelHeight }.filterNotNull().first()
            model.finishOpening(entry, day, anchor = with(density) { panelAnchor.toPx() }, rowTop = rowTop, rowHeight = rowHeight.toFloat())
        }
    }

    val toggle: () -> Unit = {
        if (isExpanded) {
            model.expandedEntryID = null
        } else {
            model.beginOpening(entry, day)
        }
    }
    Layout(
        // Measured ahead of `modifier`, so where the row rests rather than where its
        // entrance has slid it to.
        modifier = Modifier
            .onGloballyPositioned {
                rowTop = model.positionOnScreen(it).y - model.detailTop
                rowHeight = it.size.height
            }
            .then(modifier)
            .cardFade(model, cardTop = { rowTop }, topFade = topFade),
        content = {
            Box(
                Modifier
                    .layoutId(RowPart.Card)
                    // Tapping folds the item's details open beneath the card, or shut again; a
                    // long press skips them and goes straight to editing the item.
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClickLabel = if (isExpanded) "Hide details" else "Show details",
                        onLongClickLabel = "Edit",
                        onLongClick = { model.editItem(entry.item) },
                        onClick = toggle,
                    )
            ) {
                ItineraryEntryCard(
                    entry = entry,
                    places = model.places,
                    placeDetails = model.placeDetails,
                    // A redacted item's participants are blanked, so there's no one to count.
                    membersSummary = if (entry.item.redacted) null else itemMembers.summary(going),
                    membersCount = going.size,
                    isCondensed = isCondensed,
                    isExpanded = isExpanded,
                )
            }

            if (isExpanded || unfold > 0f) {
                Box(
                    Modifier
                        .layoutId(RowPart.Panel)
                        // Unfolds downward from its top edge: the top stays put and only
                        // the visible height grows (or shrinks back), with nothing sliding.
                        .drawWithContent { clipRect(bottom = size.height * unfold) { this@drawWithContent.drawContent() } }
                        .onSizeChanged { if (isExpanded) model.openRowExtra = it.height - overlap }
                        // In place of the card's bottom padding, which the panel covers.
                        .padding(bottom = TripDetailViewMetrics.rowPadding)
                ) {
                    TripItemDetailPanel(
                        item = item,
                        anchor = panelAnchor,
                        placeDetails = model.placeDetails,
                        onEdit = { model.editItem(entry.item) },
                    )
                }
            }

            // Just tapped: its details laid out unseen at their natural height.
            if (isMeasuring) {
                Box(
                    Modifier
                        .layoutId(RowPart.Measure)
                        .alpha(0f)
                        .clearAndSetSemantics {}
                        .onSizeChanged { model.measuredPanelHeight = it.height }
                        .padding(bottom = TripDetailViewMetrics.rowPadding)
                ) {
                    TripItemDetailPanel(
                        item = item,
                        anchor = panelAnchor,
                        placeDetails = model.placeDetails,
                        loadsMissingDetails = false,
                        onEdit = {},
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val natural = Constraints.fixedWidth(constraints.maxWidth)
        val card = measurables.first { it.layoutId == RowPart.Card }.measure(natural)
        val panel = measurables.firstOrNull { it.layoutId == RowPart.Panel }?.measure(natural)
        val measure = measurables.firstOrNull { it.layoutId == RowPart.Measure }?.measure(natural)
        // The row grows by the panel's height, less where it tucks under the card.
        val extra = panel?.let { ((it.height - overlap) * unfold).roundToInt().coerceAtLeast(0) } ?: 0
        layout(constraints.maxWidth, card.height + extra) {
            // Over the panel, whose top sits behind the card's bottom edge.
            card.place(0, 0, zIndex = 1f)
            panel?.place(0, card.height - overlap.roundToInt())
            // Laid out but not part of the row's height.
            measure?.place(0, card.height - overlap.roundToInt())
        }
    }
}

/** The parts of an itinerary row, for laying them out. */
private enum class RowPart { Card, Panel, Measure }

/**
 * Fades a card as it scrolls into either band where chrome sits over the itinerary;
 * see `cardFadeStops`. `cardTop` is its top in the detail space, in pixels.
 */
private fun Modifier.cardFade(model: TripDetailModel, cardTop: () -> Float, topFade: () -> Float): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val stops = cardFadeStops(
                cardTop = cardTop(),
                cardHeight = size.height,
                detailHeight = model.detailHeight,
                pinnedHeight = model.pinnedHeight,
                topFadeStart = TripDetailViewMetrics.topFadeReach.toPx() * topFade(),
                bottomFadeHeight = 120.dp.toPx(),
            )
            if (stops.any { it.second < 1f }) {
                val colors = stops.map { (location, opacity) -> location to Color.Black.copy(alpha = opacity) }
                drawRect(Brush.verticalGradient(*colors.toTypedArray()), blendMode = BlendMode.DstIn)
            }
        }

/**
 * A per-card vertical gradient that fades the card as it scrolls into either band
 * where chrome sits over the itinerary: down into the day picker at the bottom, or
 * up under the pinned header block at the top. Each fades to a 30% floor. Driven
 * purely by the card's own position, so only the cards fade, not the chrome.
 *
 * Returns the gradient's stops down the card: where each is (0 at its top to 1 at
 * its bottom) and how opaque the card is there.
 */
private fun cardFadeStops(
    cardTop: Float,
    cardHeight: Float,
    detailHeight: Float,
    pinnedHeight: Float,
    topFadeStart: Float,
    bottomFadeHeight: Float,
): List<Pair<Float, Float>> {
    val minOpacity = 0.3f

    // The bottom band, measured up from the bottom of the detail space, roughly
    // covering the day picker. Content below `bottomFadeStart` fades down.
    val bottomFadeStart = detailHeight - bottomFadeHeight

    // The top band runs from below the pinned block (whose bottom edge is y = 0 here)
    // up to the block's top edge, where the pager's mask cuts the cards off. It
    // reaches further below the block as the page scrolls (`topFadeStart`), so cards
    // at rest stay crisp. Content above `topFadeStart` fades down.
    val topFadeEnd = -pinnedHeight
    val topFadeHeight = maxOf(topFadeStart - topFadeEnd, 1f)

    fun opacity(y: Float): Float {
        val bottom = ((y - bottomFadeStart) / bottomFadeHeight).coerceIn(0f, 1f)
        val top = ((topFadeStart - y) / topFadeHeight).coerceIn(0f, 1f)
        return minOpacity + (1 - minOpacity) * (1 - maxOf(bottom, top))
    }

    // Stops at the card's edges and at every point where the fade curve bends, so
    // the parts of the card outside both bands stay fully opaque.
    val height = maxOf(cardHeight, 1f)
    val cardBottom = cardTop + cardHeight
    val bends = listOf(topFadeEnd, topFadeStart, bottomFadeStart, detailHeight).filter { it > cardTop && it < cardBottom }
    return (listOf(cardTop) + bends + cardBottom).sorted().map { y -> (y - cardTop) / height to opacity(y) }
}

/** One word of the add line under a day's items, adding that kind on the day. */
@Composable
private fun AddItemButton(model: TripDetailModel, label: String, kind: TripItemKind, date: Instant?) {
    Text(
        label.uppercase(),
        style = AppFont.caption.semibold.copy(letterSpacing = 1.5.sp),
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .plainClickable { model.addItemRequest = AddItemRequest(date = date, kind = kind) }
            .semantics { contentDescription = "Add $label" }
            // A taller target than the caption's own line.
            .padding(vertical = 6.dp),
    )
}

/**
 * Where the clock changes, e.g. after landing in a new zone: the new zone between
 * dotted rules, over the first card in it. Times from here on are that zone's wall
 * clock, until the next divider.
 */
@Composable
private fun ZoneChangeDivider(zone: ZoneId, instant: Instant, modifier: Modifier = Modifier) {
    val label = ItineraryZoneChanges.label(zone, instant)
    Row(
        modifier = modifier
            .padding(vertical = 2.dp)
            .clearAndSetSemantics { contentDescription = "Times below are in ${label.replace(" · ", ", ")}" },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZoneChangeRule()
        ForegroundStyle(Color.appForeground.copy(alpha = 0.6f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(14.dp))
                Text(
                    label.uppercase(),
                    style = AppFont.caption.semibold.copy(letterSpacing = 1.sp),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        ZoneChangeRule()
    }
}

@Composable
private fun RowScope.ZoneChangeRule() {
    DashedLine(
        color = Color.appForeground.copy(alpha = 0.35f),
        dash = listOf(2.dp, 4.dp),
        modifier = Modifier.weight(1f).height(1.dp),
    )
}

/** A faint hairline flanking the Add Activity button. */
@Composable
private fun RowScope.AddActivityRule() {
    Box(Modifier.weight(1f).height(1.dp).background(Color.appForeground.copy(alpha = 0.2f)))
}

// MARK: - Popups and sheets

/** Asks before deleting a trip from its menu, with the credits it refunds. */
@Composable
private fun DeleteTripPopupOverlay(model: TripDetailModel) {
    val target = lastPresented(model.tripToDelete)
    DimmedPopup(isPresented = model.tripToDelete != null, onClose = model::closeDeleteTrip) {
        if (target != null) {
            DeleteTripPopup(
                tripId = target.id,
                onDelete = {
                    model.closeDeleteTrip()
                    model.deleteTrip(target)
                },
                onClose = model::closeDeleteTrip,
            )
        }
    }
}

/** The open lodging popup over a dimmed screen; tapping outside closes it. */
@Composable
private fun LodgingPopupOverlay(model: TripDetailModel) {
    val item = lastPresented(model.popupLodging)
    DimmedPopup(isPresented = model.popupLodging != null, onClose = model::closeLodgingPopup) {
        if (item != null) {
            LodgingPopup(
                name = model.lodgingName(item),
                propertyType = item.lodging?.propertyType,
                address = model.lodgingAddress(item),
                googlePlaceId = model.places.current(item.place)?.googlePlaceId,
                onEdit = {
                    model.closeLodgingPopup()
                    model.editingItem = model.places.resolving(item)
                },
                onClose = model::closeLodgingPopup,
            )
        }
    }
}

/** The open add-lodging popup over a dimmed screen; tapping outside closes it. */
@Composable
private fun AddLodgingPopupOverlay(model: TripDetailModel) {
    val day = lastPresented(model.addLodgingDay)
    DimmedPopup(isPresented = model.addLodgingDay != null, onClose = model::closeAddLodgingPopup) {
        AddLodgingPopup(
            onUploadImage = {
                model.closeAddLodgingPopup()
                model.showingBookingImagePicker = true
            },
            onAddManually = {
                model.closeAddLodgingPopup()
                model.addItemRequest = AddItemRequest(date = day?.calendarDate, kind = TripItemKind.Lodging)
            },
            onClose = model::closeAddLodgingPopup,
        )
    }
}

/** The forms the itinerary opens: adding and editing an item, editing the trip, adding a segment. */
@Composable
private fun Sheets(model: TripDetailModel) {
    AppSheet(item = model.addItemRequest, onDismiss = { model.addItemRequest = null }) { request ->
        TripItemFormView(
            tripId = model.trip.id,
            kind = request.kind,
            members = model.trip.members ?: emptyList(),
            tripsByDate = model.tripsByDate,
            tripDates = model.trip.dateRange,
            defaultDate = request.date,
            onSaved = { saved -> model.reload(focusItemId = saved.id) },
            onDismiss = { model.addItemRequest = null },
        )
    }
    AppSheet(item = model.editingItem, onDismiss = { model.editingItem = null }) { item ->
        TripItemFormView(
            tripId = item.tripId,
            members = model.members(item.tripId),
            tripDates = model.trip.dateRange,
            item = item,
            viewedDay = model.selectedDay?.calendarDate,
            onSaved = { saved -> model.reload(focusItemId = saved.id) },
            onDeleted = { model.removeItem(item.id) },
            onDismiss = { model.editingItem = null },
        )
    }
    AppSheet(item = model.tripToEdit, onDismiss = { model.tripToEdit = null }) { edited ->
        TripFormView(
            trip = edited,
            groupPart = TripGroupPart.forEditing(
                edited = edited,
                primary = model.trip,
                extensions = model.itineraryTrips.filter { it.id != model.trip.id },
                userId = model.currentUserId,
            ),
            onSaved = { updated: Trip ->
                if (updated.id == model.trip.id) model.trip = updated
                // Dates may have shifted, so rebuild the day-by-day itinerary.
                model.reload()
            },
            onDeleted = { model.tripDeleted(edited) },
            onDismiss = { model.tripToEdit = null },
        )
    }
    AppSheet(item = model.segmentRequest, onDismiss = { model.segmentRequest = null }) { request ->
        TripFormView(
            segmentOf = model.trip,
            segmentEdge = request.edge,
            onSaved = { segment: Trip ->
                model.onSegmentCreated(segment)
                // The itinerary merges the user's trips in a group, so it now has the new days.
                model.reload()
                model.showToast(TripToast(text = "Added ${segment.title}", systemImage = Icons.Rounded.CheckCircle))
            },
            onDismiss = { model.segmentRequest = null },
        )
    }
}

// MARK: - Error

@Composable
private fun BoxScope.ErrorState(model: TripDetailModel, message: String) {
    Column(
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            message,
            style = AppFont.subheadline,
            color = Color.appForeground.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
        )
        ProminentButton(onClick = { model.reload() }) { Text("Try Again") }
    }
}

/** A request to present the add-item form, defaulting to `date`'s calendar day. */
private data class AddItemRequest(
    val id: UUID = UUID.randomUUID(),
    val date: Instant?,
    val kind: TripItemKind = TripItemKind.Activity,
)

/** A request to present the add-segment form, for days on `edge`'s side of the trip. */
private data class SegmentRequest(
    val id: UUID = UUID.randomUUID(),
    val edge: HorizontalEdge,
)

/**
 * A horizontally paged container that renders only the current day and its
 * immediate neighbors, so swiping never scrolls through intermediate pages.
 *
 * A swipe commits to the adjacent day (±1). Changing `selection` from elsewhere
 * (the day picker) slides a single transition toward the target (entering from
 * the right if it's a later day, from the left if earlier) with the far target
 * placed directly in the neighbor slot so nothing in between is ever built.
 *
 * `dragProgress` is the live swipe progress published outward (-1…1) so the day
 * picker can track the drag. `onSettled` is called once a slide to another day has
 * landed and the day left behind is off screen. `content` draws a day's page, told
 * whether its own scrolling is locked.
 */
@Composable
private fun DayPager(
    days: List<ItineraryDay>,
    selection: Int,
    onSelectionChange: (Int) -> Unit,
    dragProgress: Float,
    onDragProgressChange: (Float) -> Unit,
    onSettled: () -> Unit = {},
    modifier: Modifier = Modifier,
    content: @Composable (ItineraryDay, Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val currentDays by rememberUpdatedState(days)
    val currentSelection by rememberUpdatedState(selection)
    val currentDragProgress by rememberUpdatedState(dragProgress)
    val currentOnSelectionChange by rememberUpdatedState(onSelectionChange)
    val currentOnDragProgressChange by rememberUpdatedState(onDragProgressChange)
    val currentOnSettled by rememberUpdatedState(onSettled)

    // Starts on the selected day rather than the first, so the pager never swaps
    // pages right after it appears (which would show through the entrance animation).
    var index by remember { mutableIntStateOf(maxOf(days.indexOfFirst { it.dayNumber == selection }, 0)) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var pageWidth by remember { mutableFloatStateOf(0f) }
    var isAnimating by remember { mutableStateOf(false) }

    // A far jump places its target in one neighbor slot: `(side, dayIndex)`,
    // where `side` is -1 for the left slot or +1 for the right.
    var incoming by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // Per-gesture axis lock so vertical scrolling isn't hijacked by the pager.
    var dragDecided by remember { mutableStateOf(false) }
    var dragHorizontal by remember { mutableStateOf(false) }

    // Slides a single page toward `target` and settles on it. For a non-adjacent
    // target the day is dropped into the neighbor slot so no intermediate page renders.
    fun slide(target: Int) {
        val w = pageWidth
        val targetDay = currentDays.getOrNull(target)
        if (target == index || w <= 0f || targetDay == null) return
        isAnimating = true
        val side = if (target > index) 1 else -1
        if (abs(target - index) > 1) {
            incoming = side to target
        }
        // Commit the selection up front so the day picker re-centers on the new day
        // in parallel with the page, and retract any live drag-pull to zero: the two
        // motions share the same curve and duration, so the bar settles seamlessly.
        if (currentSelection != targetDay.dayNumber) {
            currentOnSelectionChange(targetDay.dayNumber)
        }
        scope.launch {
            val startOffset = dragOffset
            val startProgress = currentDragProgress
            animate(0f, 1f, animationSpec = tween(280, easing = EaseInOut)) { fraction, _ ->
                dragOffset = lerp(startOffset, if (side > 0) -w else w, fraction)
                currentOnDragProgressChange(startProgress * (1 - fraction))
            }
            index = target
            incoming = null
            dragOffset = 0f
            isAnimating = false
            currentOnSettled()
        }
    }

    // A day picked elsewhere slides to it; one picked while a slide is under way is
    // left alone.
    LaunchedEffect(selection) {
        val target = days.indexOfFirst { it.dayNumber == selection }
        if (target < 0 || target == index || isAnimating || pageWidth <= 0f) return@LaunchedEffect
        slide(target)
    }

    // Opacity for a slot given the live drag: the outgoing center fades out as pages
    // slide, while the incoming neighbor stays fully opaque. Driven by `dragOffset`,
    // so programmatic slides (taps, jumps) animate the same way.
    fun slotOpacity(position: Int): Float {
        if (dragOffset == 0f || pageWidth <= 0f || position != 0) return 1f
        return 1f - minOf(abs(dragOffset) / pageWidth, 1f)
    }

    // The day index shown in a slot, `position` being -1 (left), 0 (center), +1 (right).
    fun dayIndex(position: Int): Int {
        if (position == 0) return index
        incoming?.let { (side, dayIndex) -> if (side == position) return dayIndex }
        return index + position
    }

    // Lock vertical scrolling once a swipe commits to the horizontal axis
    // (and while a page transition animates), so day-paging never fights
    // the page's own scrolling.
    val scrollLocked = (dragDecided && dragHorizontal) || isAnimating

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { pageWidth = it.width.toFloat() }
            // Clip the neighboring pages off the sides.
            .clipToBounds()
            .pointerInput(Unit) {
                val minimumDistance = 8.dp.toPx()
                awaitEachGesture {
                    // Watched on the way down to the page, alongside its own gestures; a
                    // swipe that turns out horizontal is then kept from it.
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var translation = Offset.Zero
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) break
                        translation = change.position - down.position
                        if (isAnimating) continue
                        if (!dragDecided) {
                            if (translation.getDistance() < minimumDistance) continue
                            dragDecided = true
                            dragHorizontal = abs(translation.x) > abs(translation.y)
                        }
                        if (!dragHorizontal) continue
                        change.consume()
                        val hasLeft = index > 0
                        val hasRight = index < currentDays.size - 1
                        dragOffset = if ((translation.x > 0 && !hasLeft) || (translation.x < 0 && !hasRight)) {
                            translation.x * 0.25f // resist past the ends
                        } else {
                            translation.x
                        }
                        // Pull the day picker along, one day-width per page-width dragged.
                        if (pageWidth > 0f) currentOnDragProgressChange((dragOffset / pageWidth).coerceIn(-1f, 1f))
                    }

                    val wasHorizontal = dragHorizontal
                    dragDecided = false
                    dragHorizontal = false
                    if (isAnimating || !wasHorizontal) return@awaitEachGesture
                    val threshold = pageWidth * 0.25f
                    if (translation.x < -threshold && index < currentDays.size - 1) {
                        slide(index + 1)
                    } else if (translation.x > threshold && index > 0) {
                        slide(index - 1)
                    } else {
                        scope.launch {
                            val startOffset = dragOffset
                            val startProgress = currentDragProgress
                            animate(1f, 0f, animationSpec = tween(200, easing = EaseOut)) { fraction, _ ->
                                dragOffset = startOffset * fraction
                                currentOnDragProgressChange(startProgress * fraction)
                            }
                        }
                    }
                }
            }
    ) {
        for (position in -1..1) {
            val day = days.getOrNull(dayIndex(position))
            // One page per day, which follows its day from slot to slot.
            if (day != null) key(day.dayNumber) {
                Box(
                    Modifier
                        .fillMaxSize()
                        // A layer of its own, so the page fades as a whole. Otherwise
                        // wherever two things in it overlap (an open card over the top of
                        // its details) the two half-faded layers stack into a darker band.
                        .graphicsLayer {
                            translationX = position * pageWidth + dragOffset
                            alpha = slotOpacity(position)
                        }
                ) {
                    content(day, scrollLocked)
                }
            }
        }
    }
}

/** Fades content in, optionally sliding it up, once `isRevealed` turns true. */
private fun Modifier.revealIn(isRevealed: Boolean, delay: Double = 0.0, slide: Dp = 0.dp): Modifier = composed {
    // With animations turned off in the system settings, nothing slides.
    val reduceMotion = rememberReduceMotion()
    val revealed by animateFloatAsState(
        targetValue = if (isRevealed) 1f else 0f,
        animationSpec = tween(durationMillis = 350, delayMillis = (delay * 1000).toInt(), easing = EaseOut),
        label = "reveal",
    )
    graphicsLayer {
        alpha = revealed
        translationY = if (reduceMotion) 0f else (1 - revealed) * slide.toPx()
    }
}

/**
 * A short status message for the itinerary's toast. A null `systemImage` shows a
 * spinner, for work still in progress.
 */
private data class TripToast(
    val text: String,
    val systemImage: ImageVector? = null,
)

private sealed class ScreenshotError(message: String) : Exception(message) {
    class Unreadable : ScreenshotError("Couldn't read that image. Please try another.")
    class TimedOut : ScreenshotError("The server took too long to respond. Please try again.")
}
