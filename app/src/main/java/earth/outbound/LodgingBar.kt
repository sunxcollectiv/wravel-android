//
//  LodgingBar.kt
//  outbound
//

package earth.outbound

import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.roundToInt

/**
 * The day's lodging, pinned under the trip header: one band split between the day's
 * stays and hatched floor where there's none. Square on top, where it meets the header.
 *
 * - No lodging: all floor, inviting a stay to be added.
 * - A lone check-in or check-out: the stay takes 75% and the rest is floor, on the side
 *   with no lodging (before arriving, or after leaving).
 * - Otherwise the stays share the width equally, so a changeover day shows its
 *   check-out and check-in side by side, split by a faint line.
 *
 * Changing day slides the band along as if it were one long timeline: a stay that
 * carries over keeps its place and resizes, one that has ended slides off the side the
 * days are moving toward, and one that's starting slides in from the other. The floor
 * never moves; stays slide over it. With animations turned off in the system settings,
 * the band swaps instead.
 *
 * `name` is what to call a stay. `onOpen` is a stay tapped, `onEdit` one long-pressed,
 * and `onAdd` the empty bar of a day with no lodging tapped.
 */
@Composable
fun LodgingBar(
    dayNumber: Int,
    lodging: List<LodgingStay>,
    name: (TripItem) -> String,
    onOpen: (TripItem) -> Unit,
    onEdit: (TripItem) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val state = remember { LodgingBarState(scope, dayNumber, lodging) }
    val reduceMotion = rememberReduceMotion()
    // Another day, or the same day after its lodging was added to, edited or removed.
    LaunchedEffect(dayNumber, lodging) { state.show(dayNumber, lodging, reduceMotion) }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp))
            // Plain translucent fill, dark enough that cards scrolling underneath don't
            // compete with the lodging names.
            .background(Color.white(0.12f, 0.8f)),
    ) {
        val barWidth = constraints.maxWidth
        // An invisible row sets the height (a line of the stays' font, or the arrow
        // badge if that's taller), so the bar is the same height on every day and the
        // segments can be laid out against its measured width.
        Text(
            " ",
            style = AppFont.subheadline.semibold,
            modifier = Modifier
                .padding(vertical = 10.dp)
                .heightIn(min = LodgingBarMetrics.arrowSize)
                .alpha(0f)
                .clearAndSetSemantics {},
        )

        val segments = state.segments
        val looks = segments.map { state.look(it) }
        // Where each segment starts and ends along the bar, in pixels. Rounded from the
        // running total rather than each width, so the segments always fill the bar.
        val edges = looks.runningFold(0f) { total, look -> total + look.fraction }.map { (barWidth * it).roundToInt() }

        Layout(
            modifier = Modifier.matchParentSize(),
            content = {
                segments.forEachIndexed { index, segment ->
                    key(segment.id) {
                        SegmentWindow(
                            segment = segment,
                            look = looks[index],
                            barWidth = barWidth,
                            leading = edges[index],
                            isSliding = state.isSliding,
                            name = name,
                            onOpen = onOpen,
                            onEdit = onEdit,
                            onAdd = onAdd,
                        )
                    }
                }
            },
        ) { measurables, constraints ->
            val placeables = measurables.mapIndexed { index, measurable ->
                val width = (edges[index + 1] - edges[index]).coerceAtLeast(0)
                measurable.measure(Constraints.fixed(width, constraints.maxHeight))
            }
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeables.forEachIndexed { index, placeable -> placeable.place(edges[index], 0) }
            }
        }
    }
}

private object LodgingBarMetrics {
    val arrowSize: Dp = 16.dp

    /** The room between a phase arrow and the rest of its row. */
    val arrowSpacing: Dp = 15.dp

    /** How long the segments take to slide: the same steep ease a card's details open on. */
    const val slideDuration: Double = 0.3
}

// MARK: - Changing day

/**
 * What the bar is drawing and how far through a slide it is. The segments are always
 * where they're heading; `look(_:)` gives where each one is on the way there.
 */
@Stable
private class LodgingBarState(private val scope: CoroutineScope, dayNumber: Int, lodging: List<LodgingStay>) {
    /** What's drawn: the day's segments, plus any still sliding off from the day before. */
    var segments by mutableStateOf(LodgingBarSegment.resting(lodging))
        private set

    /** How each segment looked when the slide under way began, by its id. None at rest. */
    private var origins by mutableStateOf<Map<UUID, SegmentLook>>(emptyMap())

    /** While segments are on the move; see `StayName`. */
    val isSliding: Boolean get() = origins.isNotEmpty()

    /** How far the slide under way has run, 0 to 1. */
    private var progress by mutableFloatStateOf(1f)

    private var dayNumber = dayNumber
    private var slide: Job? = null

    /** How a segment looks right now: at rest, or part way through a slide. */
    fun look(segment: LodgingBarSegment): SegmentLook {
        val settled = SegmentLook(segment)
        val origin = origins[segment.id] ?: return settled
        return origin.toward(settled, progress)
    }

    fun show(dayNumber: Int, lodging: List<LodgingStay>, reduceMotion: Boolean) {
        val forward = dayNumber >= this.dayNumber
        this.dayNumber = dayNumber

        if (reduceMotion) {
            slide?.cancel()
            origins = emptyMap()
            segments = LodgingBarSegment.resting(lodging, keepingIdentityOf = segments)
            return
        }

        val transition = LodgingBarSegment.transition(from = segments, to = lodging, forward = forward)
        // Nothing to move: the same lodging as the bar is already showing, or heading for.
        if (transition.settled == segments) return

        // Segments already on the bar set off from wherever they've got to; arriving
        // ones go in at no width, unseen, so that they have somewhere to slide from.
        val showing = segments.associate { it.id to look(it) }
        origins = transition.staged.associate { it.id to (showing[it.id] ?: SegmentLook(it)) }
        progress = 0f
        segments = transition.settled

        // A change that's overtaken by the next doesn't finish over it.
        slide?.cancel()
        slide = scope.launch {
            animate(0f, 1f, animationSpec = AppAnimation.steepEase(LodgingBarMetrics.slideDuration)) { value, _ ->
                progress = value
            }
            // Drop what slid off.
            segments = segments.filter { !it.isLeaving }
            origins = emptyMap()
        }
    }
}

/**
 * Everything about a segment that moves when the day changes, as amounts that can sit
 * part way between one day's and the next.
 */
private data class SegmentLook(
    /** The share of the bar's width the segment takes. */
    val fraction: Float,
    val leadingRule: Float,
    val trailingRule: Float,
    val invitesAdd: Float,
    /** How far a stay's check-in arrow, "Staying" label and check-out arrow are shown. */
    val checkIn: Float,
    val staying: Float,
    val checkOut: Float,
) {
    constructor(segment: LodgingBarSegment) : this(
        fraction = segment.fraction,
        leadingRule = if (segment.leadingRule) 1f else 0f,
        trailingRule = if (segment.trailingRule) 1f else 0f,
        invitesAdd = if (segment.invitesAdd) 1f else 0f,
        checkIn = if (segment.phase == LodgingPhase.CheckIn) 1f else 0f,
        staying = if (segment.phase == LodgingPhase.Staying) 1f else 0f,
        checkOut = if (segment.phase == LodgingPhase.CheckOut) 1f else 0f,
    )

    /** This look, `amount` of the way (0 to 1) to another. */
    fun toward(to: SegmentLook, amount: Float) = SegmentLook(
        fraction = lerp(fraction, to.fraction, amount),
        leadingRule = lerp(leadingRule, to.leadingRule, amount),
        trailingRule = lerp(trailingRule, to.trailingRule, amount),
        invitesAdd = lerp(invitesAdd, to.invitesAdd, amount),
        checkIn = lerp(checkIn, to.checkIn, amount),
        staying = lerp(staying, to.staying, amount),
        checkOut = lerp(checkOut, to.checkOut, amount),
    )
}

private val LodgingBarSegment.phase: LodgingPhase?
    get() = (content as? LodgingBarSegment.Content.Stay)?.stay?.phase

// MARK: - Segments

/**
 * A segment as a window onto its content, clipping what it shows. The bar sizes it to
 * the segment's share of the width; `leading` is how far along the bar it starts.
 */
@Composable
private fun SegmentWindow(
    segment: LodgingBarSegment,
    look: SegmentLook,
    barWidth: Int,
    leading: Int,
    isSliding: Boolean,
    name: (TripItem) -> String,
    onOpen: (TripItem) -> Unit,
    onEdit: (TripItem) -> Unit,
    onAdd: () -> Unit,
) {
    when (val content = segment.content) {
        LodgingBarSegment.Content.Gap -> {
            val isActive = segment.invitesAdd && !segment.isLeaving
            Box(
                Modifier
                    .clipToBounds()
                    .plainClickable(enabled = isActive, onClick = onAdd)
                    .then(
                        if (isActive) Modifier.semantics { contentDescription = "Add lodging" }
                        else Modifier.clearAndSetSemantics {}
                    )
                    // A solid line marking where a stay begins or ends against the floor.
                    .drawWithContent {
                        drawContent()
                        val edge = Color.white(0.35f)
                        val line = Size(1.dp.toPx(), size.height)
                        drawRect(edge, size = line, alpha = look.leadingRule)
                        drawRect(edge, topLeft = Offset(size.width - line.width, 0f), size = line, alpha = look.trailingRule)
                    }
                    // A window onto floor that spans the whole bar and stays put, so the
                    // hatching and the add label hold still while stays slide over them.
                    .window(contentWidth = barWidth) { -leading },
            ) {
                Floor(invitesAdd = look.invitesAdd)
            }
        }

        is LodgingBarSegment.Content.Stay -> {
            Box(
                Modifier
                    .clipToBounds()
                    .then(if (segment.isLeaving) Modifier.clearAndSetSemantics {} else Modifier)
                    // A faint line between two stays side by side.
                    .drawWithContent {
                        drawContent()
                        val inset = 8.dp.toPx()
                        drawRect(
                            Color.White,
                            topLeft = Offset(0f, inset),
                            size = Size(1.dp.toPx(), (size.height - inset * 2).coerceAtLeast(0f)),
                            alpha = 0.15f * look.leadingRule,
                        )
                    }
                    // Laid out at its own width, which a stay sliding on or off keeps while
                    // its window closes over it, holding to the edge that's moving.
                    .window(contentWidth = (barWidth * segment.contentFraction).roundToInt().coerceAtLeast(0)) { overhang ->
                        if (segment.pin == HorizontalEdge.Leading) 0 else -overhang
                    },
            ) {
                StayRow(
                    stay = content.stay,
                    look = look,
                    isLeaving = segment.isLeaving,
                    isSliding = isSliding,
                    name = name(content.stay.item),
                    onOpen = onOpen,
                    onEdit = onEdit,
                )
            }
        }
    }
}

/**
 * Lays the content out `contentWidth` wide whatever the width of the window it's in,
 * at the x that `offset` gives for how much wider than the window that is.
 */
private fun Modifier.window(contentWidth: Int, offset: (overhang: Int) -> Int): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(Constraints.fixed(contentWidth, constraints.maxHeight))
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place(offset(contentWidth - constraints.maxWidth), 0)
        }
    }

/**
 * The bar with no stay on it: hatching, and the invitation to add one on a day that
 * has none at all.
 */
@Composable
private fun Floor(invitesAdd: Float) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        DiagonalHatch(Modifier.matchParentSize())
        ForegroundStyle(Color.appForeground.copy(alpha = 0.7f)) {
            Row(
                modifier = Modifier.graphicsLayer { alpha = invitesAdd },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                Text("add lodging", style = AppFont.caption.semibold)
            }
        }
    }
}

/**
 * A lodging chip: bed icon and name, with an arrow marking the phase, on the left
 * for a check-in (arriving) and on the right for a check-out (leaving). A stay in
 * progress shows "Staying" instead. The markers swap in place when the same stay
 * moves on to its next phase.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StayRow(
    stay: LodgingStay,
    look: SegmentLook,
    isLeaving: Boolean,
    isSliding: Boolean,
    name: String,
    onOpen: (TripItem) -> Unit,
    onEdit: (TripItem) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !isLeaving,
                role = Role.Button,
                onLongClickLabel = "Edit",
                onLongClick = { onEdit(stay.item) },
                onClick = { onOpen(stay.item) },
            )
            .clearAndSetSemantics {
                contentDescription = "$name, ${stay.phase.label}"
                customActions = listOf(
                    CustomAccessibilityAction("Edit") {
                        onEdit(stay.item)
                        true
                    },
                )
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhaseArrow(shown = look.checkIn, spacingAfter = true)
        Icon(
            stay.item.lodging?.propertyType?.systemImage ?: Icons.Rounded.Bed,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
        )
        StayName(name, isSliding, Modifier.padding(start = 8.dp).weight(1f))
        Spacer(Modifier.width(8.dp))
        // Opens out from no width and closes back to it, so the name beside it gains
        // and gives up the room gradually.
        if (look.staying > 0f) {
            Text(
                LodgingPhase.Staying.label,
                style = AppFont.caption,
                color = Color.appForeground.copy(alpha = 0.7f),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .graphicsLayer { alpha = look.staying }
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
                        val spacing = 8.dp.roundToPx()
                        layout(((placeable.width + spacing) * look.staying).roundToInt(), placeable.height) {
                            placeable.place(spacing, 0)
                        }
                    },
            )
        }
        PhaseArrow(shown = look.checkOut, spacingAfter = false)
    }
}

/**
 * A stay's name on one line, truncated to fit.
 *
 * While the bar slides, a copy at its full width shows instead, clipped to the same
 * space, so the name holds still rather than re-truncating on every frame as its room
 * changes. Once the bar has settled the truncated name is back at once and the copy
 * fades off it, so the letters they share hold steady and only the cut-off end gives
 * way to the ellipsis.
 */
@Composable
private fun StayName(name: String, isSliding: Boolean, modifier: Modifier = Modifier) {
    val copy by animateFloatAsState(
        targetValue = if (isSliding) 1f else 0f,
        animationSpec = if (isSliding) snap() else tween(150, easing = EaseOut),
        label = "stayName",
    )
    val style = AppFont.subheadline.semibold
    Box(modifier.clipToBounds()) {
        // Left out while sliding, when it would only be measured again on every frame.
        if (!isSliding) Text(name, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            name,
            style = style,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .graphicsLayer { alpha = copy },
        )
    }
}

/**
 * The phase arrow, with room on the side facing the name (after it for a check-in's,
 * before it for a check-out's). Always in the row, at no size when not `shown`, so
 * that it grows and shrinks where it sits as the row moves; one put in or taken out
 * would hang where the row was or will be.
 */
@Composable
private fun PhaseArrow(shown: Float, spacingAfter: Boolean) {
    Box(
        modifier = Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                val badge = LodgingBarMetrics.arrowSize.toPx() * shown
                val spacing = LodgingBarMetrics.arrowSpacing.toPx() * shown
                // The badge stays centered in its own shrinking share of the width.
                val center = (if (spacingAfter) 0f else spacing) + badge / 2
                layout((badge + spacing).roundToInt(), placeable.height) {
                    placeable.place((center - placeable.width / 2f).roundToInt(), 0)
                }
            }
            .graphicsLayer {
                scaleX = shown.coerceAtLeast(0.01f)
                scaleY = shown.coerceAtLeast(0.01f)
                alpha = shown
            }
            .size(LodgingBarMetrics.arrowSize)
            .background(Color.appForeground, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Rounded.ArrowForward,
            contentDescription = null,
            tint = Color.white(0.15f),
            modifier = Modifier.size(11.dp),
        )
    }
}

// MARK: - Layout

/** One stretch of the lodging bar: a stay, or floor where there's none. */
data class LodgingBarSegment(
    val id: UUID = UUID.randomUUID(),
    val content: Content,
    /**
     * The share of the bar's width the segment takes. None once it has slid off, or
     * before it slides on.
     */
    val fraction: Float,
    /**
     * The share of the bar's width its content is laid out at: its own, except while
     * sliding on or off, when the content keeps its width and the segment clips it.
     */
    val contentFraction: Float,
    /** The edge the content holds to while the segment is narrower than it. */
    val pin: HorizontalEdge = HorizontalEdge.Leading,
    /** Sliding off; dropped once it's gone. */
    val isLeaving: Boolean = false,
    /**
     * A line on the leading edge: where floor meets the stay before it, or between two
     * stays side by side.
     */
    val leadingRule: Boolean = false,
    /** A line on the trailing edge, where floor meets the stay after it. */
    val trailingRule: Boolean = false,
    /** Floor that's the whole bar, on a day with no lodging: shows the add label. */
    val invitesAdd: Boolean = false,
) {
    sealed interface Content {
        data class Stay(val stay: LodgingStay) : Content
        data object Gap : Content
    }

    /** What a segment is of, for following it from one day to the next. */
    sealed interface Key {
        data class Stay(val id: String) : Key
        data object Gap : Key
    }

    val key: Key
        get() = when (content) {
            is Content.Stay -> Key.Stay(content.stay.item.id)
            Content.Gap -> Key.Gap
        }

    private val isStay: Boolean get() = content is Content.Stay

    companion object {
        /** The bar at rest on a day with this lodging. */
        fun resting(lodging: List<LodgingStay>): List<LodgingBarSegment> {
            // A changeover day's stays in the order they happen: leaving one, then
            // arriving at the next.
            val stays = lodging.sortedBy { it.phase.dayOrder }

            fun segment(content: Content, fraction: Float) =
                LodgingBarSegment(content = content, fraction = fraction, contentFraction = fraction)

            val lone = stays.singleOrNull()?.takeIf { it.phase != LodgingPhase.Staying }
            val segments = when {
                stays.isEmpty() -> listOf(segment(Content.Gap, 1f))
                lone != null -> {
                    val chip = segment(Content.Stay(lone), 0.75f)
                    val floor = segment(Content.Gap, 0.25f)
                    if (lone.phase == LodgingPhase.CheckIn) listOf(floor, chip) else listOf(chip, floor)
                }
                else -> stays.map { segment(Content.Stay(it), 1f / stays.size) }
            }

            return segments.mapIndexed { index, segment ->
                val followsStay = index > 0 && segments[index - 1].isStay
                val precedesStay = index < segments.size - 1 && segments[index + 1].isStay
                if (segment.isStay) {
                    segment.copy(leadingRule = followsStay)
                } else {
                    segment.copy(leadingRule = followsStay, trailingRule = precedesStay, invitesAdd = segments.size == 1)
                }
            }
        }

        /**
         * The bar at rest, for swapping to without sliding. When it's the same arrangement
         * as `current` (the same stays and floor at the same widths), the segments keep
         * their identity, so only what changed within them updates.
         */
        fun resting(lodging: List<LodgingStay>, keepingIdentityOf: List<LodgingBarSegment>): List<LodgingBarSegment> {
            val segments = resting(lodging)
            val current = keepingIdentityOf.filter { !it.isLeaving }
            if (segments.map { it.key } != current.map { it.key } ||
                segments.map { it.fraction } != current.map { it.fraction }
            ) {
                return segments
            }
            return segments.mapIndexed { index, segment -> segment.copy(id = current[index].id) }
        }

        /**
         * Works out how the bar gets from `from` to a day with the lodging `to`.
         *
         * Segments on both days (the same stay, or floor) carry over and resize in place.
         * The rest slide: the bar reads as a window on one long timeline, so whatever is
         * only on the old day closes up against its neighbor and whatever is only on the
         * new day opens out from it. Where old gives way directly to new (no segment in
         * common between them), `forward` decides which side each is on: moving to a later
         * day, the old slides off toward the leading edge as the new comes in from the
         * trailing edge.
         *
         * `from` may hold segments still sliding off from an earlier change; they keep
         * going, or carry on from where they are if the new day has them after all.
         */
        fun transition(from: List<LodgingBarSegment>, to: List<LodgingStay>, forward: Boolean): LodgingBarTransition {
            val current = from
            val target = resting(to)

            // The segments that carry over must sit in the same order on both days to
            // resize in place. Floor is the usual exception (a one-night stay has it before
            // the stay on arrival and after it on departure), so it's the first to be
            // treated as two different stretches; failing that, nothing carries over.
            var carried = pairs(target, current, includingGap = true)
            if (!isInOrder(carried)) carried = pairs(target, current, includingGap = false)
            if (!isInOrder(carried)) carried = emptyList()

            val staged = mutableListOf<LodgingBarSegment>()
            val settled = mutableListOf<LodgingBarSegment>()

            // Adds the stretch between two carried segments: what's leaving and what's
            // arriving there.
            fun addChanging(old: IntRange, new: IntRange, beforeFirstCarried: Boolean) {
                val hasBoth = !old.isEmpty() && !new.isEmpty()
                // Content holds to the edge that moves: the boundary between old and new
                // where both are here, else the carried segment alongside.
                val alone = if (beforeFirstCarried && carried.isNotEmpty()) HorizontalEdge.Trailing else HorizontalEdge.Leading
                val oldPin = if (hasBoth) (if (forward) HorizontalEdge.Trailing else HorizontalEdge.Leading) else alone
                val newPin = if (hasBoth) (if (forward) HorizontalEdge.Leading else HorizontalEdge.Trailing) else alone

                // One already on its way out keeps going as it was.
                val leavingStaged = current.slice(old).map { if (it.isLeaving) it else it.copy(pin = oldPin) }
                val leavingSettled = leavingStaged.map { it.copy(fraction = 0f, isLeaving = true) }

                val arrivingSettled = target.slice(new).map { it.copy(pin = newPin) }
                val arrivingStaged = arrivingSettled.map { it.copy(fraction = 0f) }

                staged += if (forward) leavingStaged + arrivingStaged else arrivingStaged + leavingStaged
                settled += if (forward) leavingSettled + arrivingSettled else arrivingSettled + leavingSettled
            }

            var oldIndex = 0
            var newIndex = 0
            carried.forEachIndexed { position, (old, new) ->
                addChanging(oldIndex until old, newIndex until new, beforeFirstCarried = position == 0)
                staged += current[old]
                settled += target[new].copy(id = current[old].id, pin = current[old].pin)
                oldIndex = old + 1
                newIndex = new + 1
            }
            addChanging(oldIndex until current.size, newIndex until target.size, beforeFirstCarried = carried.isEmpty())

            return LodgingBarTransition(staged = staged, settled = settled)
        }

        /**
         * Each target segment with the current one it continues, if any, in target order:
         * the current segment's index, then the target's. Where there's a choice, one
         * that's staying beats one that's sliding off.
         */
        private fun pairs(
            target: List<LodgingBarSegment>,
            current: List<LodgingBarSegment>,
            includingGap: Boolean,
        ): List<Pair<Int, Int>> {
            val taken = mutableSetOf<Int>()
            val pairs = mutableListOf<Pair<Int, Int>>()
            target.forEachIndexed { new, segment ->
                if (!includingGap && !segment.isStay) return@forEachIndexed
                val candidates = current.indices.filter { current[it].key == segment.key && it !in taken }
                val old = candidates.firstOrNull { !current[it].isLeaving } ?: candidates.firstOrNull() ?: return@forEachIndexed
                taken += old
                pairs += old to new
            }
            return pairs
        }

        private fun isInOrder(pairs: List<Pair<Int, Int>>): Boolean =
            pairs.zipWithNext().all { (earlier, later) -> earlier.first < later.first }
    }
}

/** A move of the lodging bar from one day's lodging to another's, in two steps. */
data class LodgingBarTransition(
    /**
     * What's on the bar now, with the arriving segments added at no width: where the
     * slide starts from, which looks no different to the bar as it was.
     */
    val staged: List<LodgingBarSegment>,
    /**
     * The same segments at where they end up, the departing ones at no width. Slide
     * to this, then drop the ones marked `isLeaving`.
     */
    val settled: List<LodgingBarSegment>,
)

// MARK: - Pieces

/**
 * Faint diagonal stripes filling the space they're given, marking part of the lodging
 * bar with no stay.
 */
@Composable
private fun DiagonalHatch(modifier: Modifier = Modifier) {
    Canvas(modifier.clearAndSetSemantics {}) {
        val spacing = 7.dp.toPx()
        val color = Color.White.copy(alpha = 0.07f)
        clipRect {
            var x = -size.height
            while (x < size.width) {
                drawLine(color, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 1.dp.toPx())
                x += spacing
            }
        }
    }
}
