//
//  NotificationsView.kt
//  outbound
//

package earth.outbound

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The notification inbox, pushed from the bell on the trips screen. Newest first,
 * loading older ones as the list nears its end. Tapping one marks it read and opens
 * its trip, if it's about one; swiping dismisses it or toggles read.
 */
@Composable
fun NotificationsView(
    store: NotificationStore,
    /** Finds a trip by id for a notification to open, or null if the user can't see it. */
    trip: suspend (String) -> Trip?,
    /** Goes back to the trips screen. */
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    /** The trip a tapped notification opened, pushed by id like on the trips screen. */
    var openedTripId by remember { mutableStateOf<String?>(null) }
    var openedTrip by remember { mutableStateOf<Trip?>(null) }
    val scope = rememberCoroutineScope()

    fun open(notification: AppNotification) {
        store.setRead(notification, true)
        val tripId = notification.tripId ?: return
        scope.launch {
            val found = trip(tripId) ?: return@launch
            openedTrip = found
            openedTripId = found.id
        }
    }

    Box(modifier.fillMaxSize()) {
        ForegroundStyle(Color.appForeground) {
            Column(
                Modifier
                    .fillMaxSize()
                    // Notifications aren't saved for offline, so the pill just says "Offline".
                    .offlinePillAtBottom()
                    .coveredByPushedScreen(openedTripId != null)
            ) {
                PushedScreenBar(title = "Notifications", onBack = onBack) {
                    if (store.unreadCount > 0) {
                        BarButton(onClick = { store.markAllRead() }) {
                            Icon(
                                Icons.Outlined.CheckCircle,
                                contentDescription = "Mark All as Read",
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    if (store.isLoading && store.notifications.isEmpty()) {
                        ProgressView(controlSize = ControlSize.Large, tint = Color.appForeground)
                    } else if (store.notifications.isEmpty()) {
                        EmptyState(
                            message = store.errorMessage ?: "Updates on your trips will show up here.",
                            // Loading swaps the empty state for the spinner, so the load is
                            // the screen's to see through, not the empty state's.
                            onRefresh = { scope.launch { store.load() }.join() },
                        )
                    } else {
                        NotificationList(store = store, onOpen = ::open)
                    }
                }
            }
        }

        PushedScreen(isPresented = openedTripId != null, onBack = { openedTripId = null }) {
            openedTrip?.let { opened ->
                TripDetailView(trip = opened, onBack = { openedTripId = null }, onDeleted = { openedTripId = null })
            }
        }
    }

    LaunchedEffect(Unit) { store.load() }

    // Kept while the alert fades away.
    var shownError by remember { mutableStateOf("") }
    store.errorMessage?.let { if (shownError != it) shownError = it }
    AppAlert(
        title = "Something went wrong",
        isPresented = store.errorMessage != null && store.notifications.isNotEmpty(),
        onDismiss = { store.errorMessage = null },
        message = shownError,
    )
}

@Composable
private fun NotificationList(store: NotificationStore, onOpen: (AppNotification) -> Unit) {
    /** The row whose swipe actions are showing; another row's swipe puts them away. */
    var swipedId by remember { mutableStateOf<String?>(null) }

    Refreshable(onRefresh = { store.load() }) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = WindowInsets.navigationBars.asPaddingValues(),
        ) {
            items(store.notifications, key = { it.id }) { notification ->
                SwipeActionsRow(
                    isActive = swipedId == notification.id,
                    onActiveChange = { isActive ->
                        if (isActive) swipedId = notification.id else if (swipedId == notification.id) swipedId = null
                    },
                    leading = SwipeAction(
                        title = if (notification.isRead) "Unread" else "Read",
                        icon = if (notification.isRead) Icons.Outlined.MarkEmailUnread else Icons.Outlined.Drafts,
                        tint = Color.tripHeader,
                        action = { store.setRead(notification, !notification.isRead) },
                    ),
                    trailing = SwipeAction(
                        title = "Dismiss",
                        icon = Icons.Rounded.Close,
                        tint = Color.systemDestructive,
                        action = { store.dismiss(notification) },
                    ),
                    onClick = { onOpen(notification) },
                    modifier = Modifier.animateItem(),
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(if (notification.isRead) Color.Transparent else Color.White.copy(alpha = 0.05f))
                            .padding(start = rowInset)
                    ) {
                        NotificationRow(
                            notification = notification,
                            modifier = Modifier.padding(end = rowInset).padding(vertical = 11.dp),
                        )
                        // From the text's leading edge, past the picture.
                        ListSeparator(Modifier.padding(start = 48.dp))
                    }
                }
            }
            if (store.hasMore) {
                item(key = "more") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        ProgressView(tint = Color.appForeground)
                    }
                    // Again after each page, in case it left the spinner on screen.
                    LaunchedEffect(store.notifications.size) { store.loadMore() }
                }
            }
        }
    }
}

/** The margin either side of the inbox's rows. */
private val rowInset = 20.dp

@Composable
private fun EmptyState(message: String, onRefresh: suspend () -> Unit) {
    // Scrolls, though there's nothing to, so pull-to-refresh works on the empty screen too.
    Refreshable(onRefresh = onRefresh) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                Icon(
                    Icons.Rounded.NotificationsNone,
                    contentDescription = null,
                    tint = Color.appForeground.copy(alpha = 0.5f),
                    modifier = Modifier.padding(bottom = 4.dp).size(40.dp),
                )
                Text("No notifications", style = AppFont.title3.bold)
                Text(
                    message,
                    style = AppFont.subheadline,
                    color = Color.appForeground.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * One inbox entry: who or what it's from, the text, when, and an unread dot. What
 * it's put in reads it out as one element (the inbox's rows are buttons).
 */
@Composable
fun NotificationRow(notification: AppNotification, modifier: Modifier = Modifier) {
    ForegroundStyle(Color.appForeground) {
        Box(
            modifier
                .padding(vertical = 6.dp)
                .semantics {
                    if (!notification.isRead) stateDescription = "Unread"
                }
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Leading(notification)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            notification.title,
                            style = if (notification.isRead) AppFont.subheadline else AppFont.subheadline.semibold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).alignByBaseline(),
                        )
                        Text(
                            relativeTimeText(notification.visibleAt),
                            style = AppFont.caption,
                            color = Color.appForeground.copy(alpha = 0.5f),
                            maxLines = 1,
                            modifier = Modifier.alignByBaseline(),
                        )
                    }
                    val body = notification.body
                    if (!body.isNullOrEmpty()) {
                        Text(
                            body,
                            style = AppFont.subheadline,
                            color = Color.appForeground.copy(alpha = 0.7f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (!notification.isRead) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = (-14).dp)
                        .size(8.dp)
                        .background(Color.appError, CircleShape)
                        .clearAndSetSemantics {}
                )
            }
        }
    }
}

/** The person behind it, or an icon for its category. */
@Composable
private fun Leading(notification: AppNotification) {
    val actor = notification.actor
    if (actor != null) {
        AvatarView(user = actor, size = 36.dp)
    } else {
        Box(Modifier.size(36.dp).background(Color.White.copy(alpha = 0.1f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(
                NotificationRowMetrics.symbol(notification.category),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * What `NotificationRow` works out from a notification. It lives here rather than on
 * `NotificationRow`, which is a composable function and so can't hold it itself.
 */
object NotificationRowMetrics {
    fun symbol(category: NotificationCategory): ImageVector = when (category) {
        NotificationCategory.Announcement -> Icons.Outlined.Campaign
        NotificationCategory.Promotion -> Icons.Outlined.LocalOffer
        NotificationCategory.TripActivity -> Icons.Outlined.Group
        NotificationCategory.TravelAlert -> Icons.Rounded.Flight
        NotificationCategory.Other -> Icons.Rounded.NotificationsNone
    }
}

/**
 * How long ago `date` was, in few words, naming the nearby ones: "now", "5 min. ago",
 * "2 hr. ago", "yesterday", "last wk.", "3 mo. ago".
 */
internal fun relativeTimeText(date: Instant, now: Instant = Instant.now()): String {
    val seconds = Duration.between(date, now).seconds
    val isPast = seconds >= 0
    val magnitude = abs(seconds)
    val minute = 60L
    val hour = 60 * minute
    val day = 24 * hour
    val (count, unit) = when {
        magnitude < minute -> magnitude to "sec."
        magnitude < hour -> magnitude / minute to "min."
        magnitude < day -> magnitude / hour to "hr."
        magnitude < 7 * day -> magnitude / day to "day"
        magnitude < 30 * day -> magnitude / (7 * day) to "wk."
        magnitude < 365 * day -> magnitude / (30 * day) to "mo."
        else -> magnitude / (365 * day) to "yr."
    }
    if (count == 0L) return "now"
    if (count == 1L) {
        when (unit) {
            "day" -> return if (isPast) "yesterday" else "tomorrow"
            "wk.", "mo.", "yr." -> return if (isPast) "last $unit" else "next $unit"
        }
    }
    val amount = if (unit == "day" && count > 1) "$count days" else "$count $unit"
    return if (isPast) "$amount ago" else "in $amount"
}

/** The bell on the trips screen's bar, with a count of unread notifications. */
@Composable
fun NotificationBell(unreadCount: Int, modifier: Modifier = Modifier) {
    Box(
        // Not an element of its own: the bar's button around it reads these out.
        modifier.semantics {
            contentDescription = "Notifications"
            if (unreadCount > 0) stateDescription = "$unreadCount unread"
        }
    ) {
        Icon(Icons.Rounded.NotificationsNone, contentDescription = null, modifier = Modifier.size(24.dp))
        if (unreadCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 8.dp, y = (-6).dp)
                    .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                    .background(Color.appError, CircleShape)
                    .padding(horizontal = 4.dp)
                    .clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (unreadCount > 99) "99+" else "$unreadCount",
                    style = AppFont.system(10f, FontWeight.Bold).monospacedDigit,
                    color = Color.White,
                    maxLines = 1,
                )
            }
        }
    }
}

// MARK: - Swipe actions

/** What swiping a row one way does, and the button it shows for it. */
private class SwipeAction(
    val title: String,
    val icon: ImageVector,
    val tint: Color,
    val action: () -> Unit,
)

/** How wide a swipe action's button is once it's open. */
private val swipeActionWidth: Dp = 74.dp

/**
 * A list row with SwiftUI's `.swipeActions`: tapping it runs `onClick`, swiping it
 * right shows the `leading` action's button and swiping it left the `trailing` one's.
 * A short swipe leaves the button open to tap; a swipe most of the way across does
 * the action straight away. Accessibility gets both as actions on the row.
 *
 * `isActive` says whether this is the row whose button may stay open, so opening
 * another row's closes this one's.
 */
@Composable
private fun SwipeActionsRow(
    isActive: Boolean,
    onActiveChange: (Boolean) -> Unit,
    leading: SwipeAction,
    trailing: SwipeAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val actionWidth = with(LocalDensity.current) { swipeActionWidth.toPx() }
    /** How far the row is moved aside, in pixels: right is positive. */
    var offset by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(0) }
    var settling by remember { mutableStateOf<Job?>(null) }

    fun settle(target: Float) {
        settling?.cancel()
        settling = scope.launch {
            animate(initialValue = offset, targetValue = target, animationSpec = AppAnimation.smooth(0.3)) { value, _ ->
                offset = value
            }
        }
    }

    fun close() {
        onActiveChange(false)
        settle(0f)
    }

    fun perform(action: SwipeAction) {
        close()
        action.action()
    }

    // Another row took over: put this one's button away.
    LaunchedEffect(isActive) {
        if (!isActive && offset != 0f) settle(0f)
    }

    Box(
        modifier
            .fillMaxWidth()
            .onSizeChanged { width = it.width }
            .clipToBounds()
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    offset = (offset + delta).coerceIn(-width.toFloat(), width.toFloat())
                },
                onDragStarted = {
                    settling?.cancel()
                    onActiveChange(true)
                },
                onDragStopped = { velocity ->
                    val action = if (offset > 0) leading else trailing
                    val isFlung = abs(velocity) > 800f && offset * velocity > 0
                    when {
                        // Most of the way across: the action, without a tap.
                        abs(offset) > width * 0.6f -> perform(action)
                        abs(offset) > actionWidth / 2 || isFlung -> settle(if (offset > 0) actionWidth else -actionWidth)
                        else -> close()
                    }
                },
            )
    ) {
        if (offset > 0f) {
            SwipeActionButton(
                action = leading,
                shownWidth = offset,
                labelAlignment = Alignment.CenterEnd,
                onClick = { perform(leading) },
                modifier = Modifier.matchParentSize().wrapContentWidth(Alignment.Start),
            )
        } else if (offset < 0f) {
            SwipeActionButton(
                action = trailing,
                shownWidth = -offset,
                labelAlignment = Alignment.CenterStart,
                onClick = { perform(trailing) },
                modifier = Modifier.matchParentSize().wrapContentWidth(Alignment.End),
            )
        }
        Box(
            Modifier
                .offset { IntOffset(offset.roundToInt(), 0) }
                .plainClickable(onClick = onClick)
                // On the button itself, where accessibility looks for them.
                .semantics {
                    customActions = listOf(leading, trailing).map { action ->
                        CustomAccessibilityAction(action.title) {
                            action.action()
                            true
                        }
                    }
                }
        ) {
            content()
            // A tap on a row moved aside puts it back rather than opening it.
            if (offset != 0f) {
                Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { close() } })
            }
        }
    }
}

/**
 * The button uncovered behind a swiped row: `shownWidth` pixels of the action's
 * color, its label kept at the end next to the row.
 */
@Composable
private fun SwipeActionButton(
    action: SwipeAction,
    shownWidth: Float,
    labelAlignment: Alignment,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(with(LocalDensity.current) { shownWidth.toDp() })
            .fillMaxHeight()
            .clipToBounds()
            .background(action.tint)
            .plainClickable(onClick = onClick),
        contentAlignment = labelAlignment,
    ) {
        Column(
            modifier = Modifier.requiredWidth(swipeActionWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(action.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            Text(action.title, style = AppFont.caption, color = Color.White, maxLines = 1)
        }
    }
}

@Preview
@Composable
private fun NotificationBellPreview() {
    AppTheme {
        Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            NotificationBell(unreadCount = 0)
            NotificationBell(unreadCount = 3)
            NotificationBell(unreadCount = 120)
        }
    }
}
