//
//  OfflinePill.kt
//  outbound
//

package earth.outbound

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.node.Ref
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import earth.outbound.networking.Connectivity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.abs

/** What the offline pill says after "Offline". */
sealed interface OfflinePillDetail {
    /** Showing a copy saved at this date. */
    data class Saved(val date: Instant) : OfflinePillDetail

    /** Edits can be made, but not saved. */
    data object CantSave : OfflinePillDetail
}

/**
 * A small pill saying there's no connection, with a word on what that means for the
 * screen on display, e.g. how old the saved copy is.
 */
@Composable
fun OfflinePill(detail: OfflinePillDetail? = null, modifier: Modifier = Modifier) {
    val iconSize = scaledSymbolSize(AppFont.footnote, scale = 1.15f)
    Row(
        modifier = modifier
            // Its clip keeps the outgoing words inside the pill while it narrows.
            .floatingPillBackground(CircleShape)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Icon(Icons.Rounded.WifiOff, contentDescription = null, modifier = Modifier.size(iconSize))
            Spacer(Modifier.width(6.dp))
            Text("Offline", style = AppFont.footnote.semibold, maxLines = 1)
            // A change of detail crossfades the words as the pill takes their width.
            AnimatedContent(
                targetState = detail,
                transitionSpec = {
                    fadeIn(tween(200)) togetherWith fadeOut(tween(200)) using SizeTransform(clip = false) { _, _ -> tween(200) }
                },
                contentAlignment = Alignment.CenterStart,
                label = "detail",
            ) { shown ->
                if (shown != null) {
                    Text(
                        detailText(shown),
                        style = AppFont.footnote.semibold,
                        color = Color.White.copy(alpha = 0.65f),
                        maxLines = 1,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * The offline pill's measurements. They live here rather than on `OfflinePill`,
 * which is a composable function and so can't hold them itself.
 */
object OfflinePillMetrics {
    /**
     * The gap between the pill and the bottom safe area where it rests on a screen
     * with nothing at its bottom, like the trips list. It's the lowest the pill rests
     * anywhere, which tells a screen sliding away from one at rest.
     */
    val restingGap: Dp = 8.dp

    /**
     * How far above the screen's bottom edge the pill sits over things with no spot
     * of their own for it, like popups: level with a form's Save button. On a device
     * whose navigation bar is taller than that, see `offlinePillNearBottomGap()`.
     */
    val nearBottomGap: Dp get() = FormBottomBarMetrics.saveBottomGap

    /**
     * The pill's height at the default text size: a footnote line plus its padding.
     * Use `scaledHeight()` to keep room for it at the text size in use.
     */
    val height: Dp = 34.dp

    /** The pill's height at the system's font scale. */
    @Composable
    fun scaledHeight(): Dp = height * LocalDensity.current.fontScale
}

private fun detailText(detail: OfflinePillDetail): String = when (detail) {
    is OfflinePillDetail.Saved -> "· saved ${relativeDateText(detail.date)}"
    OfflinePillDetail.CantSave -> "· can't save yet"
}

/**
 * How long ago (or how far ahead) `date` is from `now`, in words, naming the nearby
 * ones: "now", "3 hours ago", "yesterday", "last week", "in 2 days".
 */
internal fun relativeDateText(date: Instant, now: Instant = Instant.now()): String {
    val seconds = Duration.between(date, now).seconds
    val isPast = seconds >= 0
    val magnitude = abs(seconds)
    val minute = 60L
    val hour = 60 * minute
    val day = 24 * hour
    val (count, unit) = when {
        magnitude < minute -> magnitude to "second"
        magnitude < hour -> magnitude / minute to "minute"
        magnitude < day -> magnitude / hour to "hour"
        magnitude < 7 * day -> magnitude / day to "day"
        magnitude < 30 * day -> magnitude / (7 * day) to "week"
        magnitude < 365 * day -> magnitude / (30 * day) to "month"
        else -> magnitude / (365 * day) to "year"
    }
    if (count == 0L) return "now"
    if (count == 1L) {
        when (unit) {
            "day" -> return if (isPast) "yesterday" else "tomorrow"
            "week", "month", "year" -> return if (isPast) "last $unit" else "next $unit"
        }
    }
    val amount = if (count == 1L) "1 $unit" else "$count ${unit}s"
    return if (isPast) "$amount ago" else "in $amount"
}

/**
 * Where the offline pill goes, and what it says, as told by the screens on display.
 * The pill itself is drawn over the whole app (see `OfflinePillOverlay`), so sheets
 * and popups opened over a screen never hide it.
 */
class OfflinePillCenter {
    /** One screen's say on the pill. */
    data class Placement(
        /** Where the pill's bottom edge goes, in pixels from the top of the window. */
        val bottomY: Float,
        /** Forms can't save offline, so they say so instead of how old the copy is. */
        val isForm: Boolean,
        val savedDate: Instant?,
        /**
         * Shows the pill with a connection too, e.g. while the server is erroring
         * and the saved copy is showing.
         */
        val showsWhenOnline: Boolean,
        /** Steps aside for something else in the spot, like a toast. */
        val isHidden: Boolean,
        /**
         * Only moves the pill, keeping what the screen below says about it, e.g. for
         * a popup over a form.
         */
        val keepsScreenBelow: Boolean = false,
    )

    /** A screen's placement, under the id it was set with. */
    data class Entry(val id: UUID, val placement: Placement)

    /** In the order the screens appeared; the last is the one on top. */
    var placements by mutableStateOf(listOf<Entry>())
        private set

    val current: Placement? get() = current { false }

    /**
     * The top screen's placement, leaving out those `isLeaving` says are on their
     * way out, with what the pill says taken from the first screen below that has
     * its own say, if the top one only moves it.
     */
    fun current(skipping: (Placement) -> Boolean): Placement? {
        val placements = placements.map { it.placement }.filterNot(skipping)
        val top = placements.lastOrNull() ?: return null
        if (!top.keepsScreenBelow) return top
        val below = placements.dropLast(1).lastOrNull { !it.keepsScreenBelow } ?: return top
        return top.copy(
            isForm = below.isForm,
            savedDate = below.savedDate,
            showsWhenOnline = below.showsWhenOnline,
            isHidden = below.isHidden,
        )
    }

    fun set(placement: Placement, id: UUID) {
        val index = placements.indexOfFirst { it.id == id }
        if (index < 0) {
            placements = placements + Entry(id, placement)
        } else if (placements[index].placement != placement) {
            placements = placements.toMutableList().also { it[index] = Entry(id, placement) }
        }
    }

    fun remove(id: UUID) {
        if (placements.any { it.id == id }) placements = placements.filterNot { it.id == id }
    }

    companion object {
        val shared = OfflinePillCenter()
    }
}

/**
 * Places the offline pill `spacing` above this element's top edge while this screen
 * is on display, and on top of anything later presented over it that doesn't
 * place it itself.
 */
fun Modifier.offlinePillAnchor(
    spacing: Dp = 0.dp,
    isForm: Boolean = false,
    savedDate: Instant? = null,
    showsWhenOnline: Boolean = false,
    isHidden: Boolean = false,
    keepsScreenBelow: Boolean = false,
    isActive: Boolean = true,
): Modifier = offlinePillPlacement(isActive) { frame ->
    OfflinePillCenter.Placement(
        bottomY = frame.top - with(LocalDensity.current) { spacing.toPx() },
        isForm = isForm,
        savedDate = savedDate,
        showsWhenOnline = showsWhenOnline,
        isHidden = isHidden,
        keepsScreenBelow = keepsScreenBelow,
    )
}

/**
 * Places the offline pill `OfflinePillMetrics.restingGap` over the navigation bar (or
 * the keyboard) while this element is on display, for screens with nothing at their
 * bottom. Apply it to an element filling the screen.
 */
fun Modifier.offlinePillAtBottom(
    isForm: Boolean = false,
    savedDate: Instant? = null,
    showsWhenOnline: Boolean = false,
): Modifier = offlinePillPlacement(isActive = true) { frame ->
    val density = LocalDensity.current
    val inset = maxOf(WindowInsets.navigationBars.getBottom(density), WindowInsets.ime.getBottom(density))
    val bottom = minOf(frame.bottom, frame.windowHeight - inset)
    OfflinePillCenter.Placement(
        bottomY = bottom - with(density) { OfflinePillMetrics.restingGap.toPx() },
        isForm = isForm,
        savedDate = savedDate,
        showsWhenOnline = showsWhenOnline,
        isHidden = false,
    )
}

/**
 * Moves the offline pill down to `OfflinePillMetrics.nearBottomGap` above the screen's
 * bottom edge (or the keyboard) while this element is on display, keeping what the
 * screen below says, for things that cover a screen without a place of their own
 * for it, like popups and detail sheets. Apply it to an element filling the screen.
 *
 * For something that animates away, apply it to an element that stays composed, with
 * `isActive` saying whether it's open, rather than inside what closes: the pill then
 * moves back as soon as it starts closing instead of once it's gone. `DimmedPopup`
 * does this itself.
 */
fun Modifier.offlinePillNearBottom(isActive: Boolean = true): Modifier = offlinePillPlacement(isActive) { frame ->
    val density = LocalDensity.current
    val bottom = minOf(frame.bottom, frame.windowHeight - WindowInsets.ime.getBottom(density))
    OfflinePillCenter.Placement(
        bottomY = bottom - with(density) { offlinePillNearBottomGap().toPx() },
        isForm = false,
        savedDate = null,
        showsWhenOnline = false,
        isHidden = false,
        keepsScreenBelow = true,
    )
}

/**
 * How far above the screen's bottom edge (or the keyboard) `offlinePillNearBottom`
 * puts the pill: `OfflinePillMetrics.nearBottomGap`, or just clear of the navigation
 * bar where that's taller, as with three-button navigation. Anything keeping room
 * for the pill there measures with this.
 */
@Composable
fun offlinePillNearBottomGap(): Dp {
    val density = LocalDensity.current
    val navigationBar = WindowInsets.navigationBars.getBottom(density)
    val keyboard = WindowInsets.ime.getBottom(density)
    val aboveNavigationBar = with(density) { (navigationBar - keyboard).toDp() } + OfflinePillMetrics.restingGap
    return max(OfflinePillMetrics.nearBottomGap, aboveNavigationBar)
}

/**
 * Disables this while the server can't be reached, for actions that save straight
 * away; forms instead stay editable and only block saving. `dimmed` fades it too,
 * for controls whose style doesn't show being disabled.
 *
 * It stops touches from reaching what's inside and marks it disabled for
 * accessibility. A control that draws itself differently when disabled (like
 * `ProminentButton`) should also get `enabled = Connectivity.shared.isConnected`.
 */
fun Modifier.requiresConnection(dimmed: Boolean = true): Modifier = composed {
    if (Connectivity.shared.isConnected) {
        this
    } else {
        this
            .alpha(if (dimmed) 0.4f else 1f)
            .semantics { disabled() }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            }
    }
}

/** Where an element placing the pill is, in pixels within the window. */
private data class AnchorFrame(val top: Float, val bottom: Float, val windowHeight: Float)

/**
 * Tells `OfflinePillCenter` where this element puts the pill, as `placement` works
 * out from its frame, for as long as it's in the composition and `isActive`.
 */
private fun Modifier.offlinePillPlacement(
    isActive: Boolean,
    placement: @Composable (AnchorFrame) -> OfflinePillCenter.Placement,
): Modifier = composed {
    val id = remember { UUID.randomUUID() }
    var frame by remember { mutableStateOf<AnchorFrame?>(null) }
    val current = frame?.let { placement(it) }

    DisposableEffect(id) {
        onDispose { OfflinePillCenter.shared.remove(id) }
    }
    DisposableEffect(current, isActive) {
        if (!isActive) {
            OfflinePillCenter.shared.remove(id)
        } else if (current != null) {
            OfflinePillCenter.shared.set(current, id)
        }
        onDispose {}
    }

    // A screen can be laid out once and then moved or scaled into place in a graphics
    // layer (a trip zooming out of its tile), which changes where this is on screen
    // without laying anything out again. So after each layout, its position is read
    // again every frame until it has stopped changing for a moment.
    val coordinates = remember { Ref<LayoutCoordinates>() }
    val positioned = remember { Channel<Unit>(Channel.CONFLATED) }
    LaunchedEffect(Unit) {
        for (signal in positioned) {
            var settledSince = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val measured = coordinates.value?.takeIf { it.isAttached }?.let(::anchorFrame) ?: break
                if (measured != frame || positioned.tryReceive().isSuccess) {
                    frame = measured
                    settledSince = now
                } else if (now - settledSince > settleNanos) {
                    break
                }
            }
        }
    }

    onGloballyPositioned {
        coordinates.value = it
        frame = anchorFrame(it)
        positioned.trySend(Unit)
    }
}

/** How long an anchor's position has to hold still before it's taken to have settled. */
private const val settleNanos = 300_000_000L

private fun anchorFrame(coordinates: LayoutCoordinates): AnchorFrame {
    val top = coordinates.positionInWindow().y
    return AnchorFrame(
        top = top,
        bottom = top + coordinates.size.height,
        windowHeight = coordinates.findRootCoordinates().size.height.toFloat(),
    )
}

/**
 * Draws the offline pill where the screen on top placed it. It's the same pill
 * throughout: moving between screens slides it to the new spot and changes its words.
 *
 * `MainActivity` composes it last, over the screens and everything they present, and
 * touches pass straight through it.
 */
@Composable
fun OfflinePillOverlay() {
    val center = OfflinePillCenter.shared
    val connectivity = Connectivity.shared
    val density = LocalDensity.current

    /**
     * The placement drawn. Holds on to the last one for a moment when none is left,
     * since a screen leaving can drop its placement just before the next one adds
     * its own, and the pill should move across rather than leave and come back.
     */
    var placement by remember { mutableStateOf<OfflinePillCenter.Placement?>(null) }
    /**
     * The words drawn after "Offline". Changed once the pill has stopped moving:
     * words fading in or out mid-move are drawn where the pill was or will be,
     * outside it.
     */
    var detail by remember { mutableStateOf<OfflinePillDetail?>(null) }
    var screenHeight by remember { mutableIntStateOf(0) }
    /** The navigation bar's strip at the bottom of the screen. */
    val bottomInset = WindowInsets.navigationBars.getBottom(density)

    /** The lowest the pill rests anywhere, from the screen's bottom edge. */
    val lowestGap = bottomInset + with(density) { OfflinePillMetrics.restingGap.toPx() }

    // Where the pill should be. A screen whose spot is lower than any the pill rests
    // in is on its way out, e.g. a sheet that has started sliding off the bottom,
    // so the pill heads for the screen below right away instead of once it's gone.
    val leavingSlack = with(density) { 1.dp.toPx() }
    val target = if (screenHeight > 0) {
        center.current { screenHeight - it.bottomY < lowestGap - leavingSlack }
    } else {
        center.current
    }

    LaunchedEffect(target) {
        if (target != null) {
            // A pill not on display yet shows up with its words in place.
            if (placement == null) detail = detail(target)
            placement = target
        } else {
            delay(400)
            placement = null
        }
    }

    // The gap under the pill, eased to each new spot once it has a first one.
    val gapTarget = placement?.takeIf { screenHeight > 0 }?.let { maxOf(screenHeight - it.bottomY, lowestGap) }
    val gap = remember { Animatable(0f) }
    var isPlaced by remember { mutableStateOf(false) }
    LaunchedEffect(gapTarget) {
        when {
            gapTarget == null -> isPlaced = false
            isPlaced -> gap.animateTo(gapTarget, AppAnimation.snappy())
            else -> {
                gap.snapTo(gapTarget)
                isPlaced = true
            }
        }
    }

    // Fades the words to what the current placement says, unless the pill is on its
    // way to a new spot, in which case this runs again once it gets there.
    val isMoving = isPlaced && (gap.isRunning || (gapTarget != null && gap.targetValue != gapTarget))
    LaunchedEffect(placement, isMoving) {
        val shown = placement
        if (!isMoving && shown != null) detail = detail(shown)
    }

    val isShowing = placement?.let { !it.isHidden && (!connectivity.isConnected || it.showsWhenOnline) } ?: false
    Box(
        modifier = Modifier.fillMaxSize().onSizeChanged { screenHeight = it.height },
        contentAlignment = Alignment.BottomCenter,
    ) {
        // Slides the whole way off the bottom of the screen, gap included.
        AnimatedVisibility(
            visible = isShowing,
            enter = slideInVertically(AppAnimation.snappy()) { it } + fadeIn(AppAnimation.snappy()),
            exit = slideOutVertically(AppAnimation.snappy()) { it } + fadeOut(AppAnimation.snappy()),
        ) {
            // Straight at its spot when it first shows, before the eased gap has caught up.
            val shownGap = if (isPlaced) gap.value else gapTarget ?: gap.value
            OfflinePill(
                detail = detail,
                modifier = Modifier.padding(bottom = with(density) { shownGap.coerceAtLeast(0f).toDp() }),
            )
        }
    }
}

private fun detail(placement: OfflinePillCenter.Placement): OfflinePillDetail? =
    if (placement.isForm) OfflinePillDetail.CantSave else placement.savedDate?.let { OfflinePillDetail.Saved(it) }

@Preview
@Composable
private fun OfflinePillPreview() {
    AppTheme {
        Column(
            modifier = Modifier.fillMaxSize().background(Color.Black).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OfflinePill(detail = OfflinePillDetail.Saved(Instant.now().minusSeconds(3 * 60 * 60)))
            OfflinePill(detail = OfflinePillDetail.CantSave)
            OfflinePill()
        }
    }
}
