//
//  DaySelectorBar.kt
//  outbound
//

package earth.outbound

import android.os.Build
import android.view.RoundedCorner
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The leading or trailing side of something laid out horizontally. */
enum class HorizontalEdge { Leading, Trailing }

/**
 * The day picker pinned to the bottom of the itinerary: a dark glassy bar with a
 * circular button per day (weekday label above, day-of-month inside the circle).
 * The selected day is a filled cream disc; tapping a day scrubs the pager and the
 * bar stays in sync with the current `selection`.
 *
 * Days scroll horizontally — about five fit across the width at once — and the row
 * can be slid freely to any day. Whenever `selection` changes (a tap here, or a
 * swipe of the itinerary itself), the selected day animates back to center.
 *
 * The bar keeps its row of days clear of the navigation bar itself (unless whoever
 * places it already has), and draws its backdrop and the trip lines on down to the
 * screen's bottom edge.
 */
@Composable
fun DaySelectorBar(
    days: List<ItineraryDay>,
    selection: Int,
    onSelectionChange: (Int) -> Unit,
    /**
     * Live itinerary-swipe progress in day-units (-1…1); pulls the row along as the
     * pages are dragged, before `selection` settles on the final day.
     */
    dragProgress: Float = 0f,
    /**
     * Tapped from the plus button at either end of the row, to add a segment:
     * `Leading` for days before the trip, `Trailing` for days after.
     */
    onAddSegment: (HorizontalEdge) -> Unit = {},
    /**
     * The ends that show the plus button; an end left out keeps its empty cell, so the
     * row's layout doesn't change.
     */
    segmentEdges: Set<HorizontalEdge> = setOf(HorizontalEdge.Leading, HorizontalEdge.Trailing),
    /** The trip the screen is for: its days' line is solid, its extensions' hatched. */
    primaryTripId: String? = null,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    /** The bar's height with and without the room it keeps for the navigation bar, in pixels. */
    var height by remember { mutableIntStateOf(0) }
    var rowHeight by remember { mutableIntStateOf(0) }
    /** The room below the row down to the screen's bottom edge, where the trip lines sit, in pixels. */
    val bottomInset = max(height - rowHeight, 0).toFloat()
    val cells = remember(days) { cells(days) }
    val runs = remember(cells) { tripRuns(cells) }
    val screenCornerRadius = screenCornerRadius()

    Box(modifier.fillMaxWidth().onSizeChanged { height = it.height }.windowInsetsPadding(WindowInsets.navigationBars)) {
        Box(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { rowHeight = it.height }
                .drawBehind {
                    // Down past the row's own bounds, to the screen's bottom edge.
                    val height = size.height + bottomInset
                    drawRect(
                        brush = Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                            startY = 0f,
                            endY = height,
                        ),
                        size = Size(size.width, height),
                    )
                }
                .padding(top = DaySelectorBarMetrics.fadeHeight)
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth().height(circleSize)) {
                val width = constraints.maxWidth.toFloat()
                // A fade band at each edge doubles as the side margin: five cells fill the
                // space between the two bands and stay fully opaque, while days scrolling
                // past fade out through the band (see edgeFadeMask).
                val fadeZone = with(density) { 36.dp.toPx() }
                val circle = with(density) { circleSize.toPx() }
                // Five circles fit between the fade bands; widen the gap between them by 20%.
                val baseGap = (width - fadeZone * 2) / 5 - circle
                // In whole pixels, so the trip lines stay in step with the cells as laid out.
                val cellWidth = max(circle + baseGap * 1.2f, 1f).roundToInt()
                val sideInset = (width - cellWidth) / 2
                // The add-segment buttons sit in the side insets, so the first and last
                // days still scroll to center.
                val leadingPadding = max(sideInset - cellWidth, 0f).roundToInt()
                val cellWidthDp = with(density) { cellWidth.toDp() }

                /** How far the row scrolls to put a day in the center; null for a day without a circle. */
                fun centeredOffset(dayNumber: Int): Int? {
                    val index = cells.indexOfFirst { it is Cell.Day && it.day.dayNumber == dayNumber }
                    if (index < 0) return null
                    // The plus cell comes first.
                    val cellStart = leadingPadding + (index + 1) * cellWidth
                    return max((cellStart - sideInset).roundToInt(), 0)
                }

                val scrollState = rememberScrollState(initial = centeredOffset(selection) ?: 0)
                var laidOutWidth by remember { mutableIntStateOf(constraints.maxWidth) }
                LaunchedEffect(selection, constraints.maxWidth) {
                    val offset = centeredOffset(selection) ?: return@LaunchedEffect
                    if (laidOutWidth != constraints.maxWidth) {
                        // A new width (a rotation) moves every day: jump, don't slide.
                        laidOutWidth = constraints.maxWidth
                        scrollState.scrollTo(offset)
                    } else {
                        scrollState.animateScrollTo(offset, tween(280, easing = EaseInOut))
                    }
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        // A static edge gradient fades the content near both sides, so days
                        // ease in/out instead of being hard-cut at the edge.
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(edgeFadeMask(fadeZone = fadeZone, width = size.width), blendMode = BlendMode.DstIn)
                        }
                        .horizontalScroll(scrollState)
                ) {
                    Row(
                        Modifier
                            .fillMaxHeight()
                            .padding(horizontal = with(density) { leadingPadding.toDp() })
                            // Track the itinerary swipe: one day-width of travel per full page drag.
                            .graphicsLayer { translationX = dragProgress * cellWidth }
                    ) {
                        val cellModifier = Modifier.width(cellWidthDp).fillMaxHeight()
                        SegmentEnd(HorizontalEdge.Leading, segmentEdges, onAddSegment, cellModifier)
                        for (cell in cells) {
                            when (cell) {
                                is Cell.Day -> DayCell(
                                    cell.day,
                                    isSelected = cell.day.dayNumber == selection,
                                    onClick = { onSelectionChange(cell.day.dayNumber) },
                                    modifier = cellModifier,
                                )
                                is Cell.Gap -> GapCell(cell.days, cellModifier)
                            }
                        }
                        SegmentEnd(HorizontalEdge.Trailing, segmentEdges, onAddSegment, cellModifier)
                    }
                }

                // Outside the scrolling row, so the edge fade doesn't reach the trip lines,
                // but drawn in step with the days.
                Canvas(Modifier.matchParentSize()) {
                    drawTripLines(
                        runs = runs,
                        cellCount = cells.size,
                        primaryTripId = primaryTripId,
                        cellWidth = cellWidth.toFloat(),
                        // Where the first cell starts on screen.
                        origin = leadingPadding - scrollState.value + dragProgress * cellWidth,
                        // Down past the navigation bar, so the line sits on the screen's bottom edge.
                        bottom = size.height + bottomInset,
                        screenCornerRadius = screenCornerRadius,
                    )
                }
            }
        }
    }
}

object DaySelectorBarMetrics {
    /**
     * The fade above the row of day circles. The itinerary reads it to know where
     * the circles start, the bottom of the band it centers an opened card in.
     */
    val fadeHeight: Dp = 40.dp
}

/** A day's circle, and the height of the row. */
private val circleSize = 56.dp

/** The trip line's thickness, and the break between trips. */
private val tripLineHeight = 5.dp
private val tripLineBreak = 8.dp

/**
 * The radius of the screen's bottom corners in pixels, zero where the system doesn't
 * say. The trip lines fade out across the corners: from where the curve begins, to
 * where it has swallowed the line's height.
 */
@Composable
private fun screenCornerRadius(): Float {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 0f
    val insets = LocalView.current.rootWindowInsets ?: return 0f
    return listOf(RoundedCorner.POSITION_BOTTOM_LEFT, RoundedCorner.POSITION_BOTTOM_RIGHT)
        .maxOf { insets.getRoundedCorner(it)?.radius ?: 0 }
        .toFloat()
}

/**
 * A full-height horizontal gradient: transparent at both edges, opaque across the
 * middle. Used as a mask so day cells fade as they scroll into the edge bands.
 * The ramp is compressed to the inner part of each band, so days drop to
 * transparent sharply once they leave the primary five.
 */
private fun edgeFadeMask(fadeZone: Float, width: Float): Brush {
    val f = (fadeZone / width).coerceIn(0f, 0.5f)
    val ramp = f * 0.7f   // only the inner ~70% of the band ramps; the rest is clear
    return Brush.horizontalGradient(
        0f to Color.Transparent,
        f - ramp to Color.Transparent,
        f to Color.White,
        1 - f to Color.White,
        1 - (f - ramp) to Color.Transparent,
        1f to Color.Transparent,
        startX = 0f,
        endX = width,
    )
}

/** A run of cells sharing a trip, by cell index (the plus cell is 0). */
private data class TripRun(val trip: String?, val first: Int, val last: Int)

/**
 * Each run of day cells on one trip. A day no trip covers (a flight before the
 * trip, say) joins the run it sits beside; gap days belong to none.
 */
private fun tripRuns(cells: List<Cell>): List<TripRun> {
    val runs = mutableListOf<TripRun>()
    cells.forEachIndexed { offset, cell ->
        val index = offset + 1
        val day = (cell as? Cell.Day)?.day ?: return@forEachIndexed
        val last = runs.lastOrNull()
        if (last != null && last.last == index - 1 && (day.tripId == null || last.trip == null || last.trip == day.tripId)) {
            runs[runs.size - 1] = TripRun(last.trip ?: day.tripId, last.first, index)
        } else {
            runs.add(TripRun(day.tripId, index, index))
        }
    }
    return runs
}

/**
 * A white line under each run of days on one trip, reaching the screen edges at the
 * ends of the journey, with a short break where one trip meets the next and none
 * under gap days. `origin` is where the leading plus cell starts on screen, and
 * `bottom` where the lines' bottom edge sits.
 */
private fun DrawScope.drawTripLines(
    runs: List<TripRun>,
    cellCount: Int,
    primaryTripId: String?,
    cellWidth: Float,
    origin: Float,
    bottom: Float,
    screenCornerRadius: Float,
) {
    val height = tripLineHeight.toPx()
    val lineBreak = tripLineBreak.toPx()
    val top = bottom - height
    clipRect(left = 0f, top = top, right = size.width, bottom = bottom) {
        runs.forEachIndexed { position, run ->
            val touchesPrevious = position > 0 && runs[position - 1].last == run.first - 1
            val touchesNext = position < runs.size - 1 && runs[position + 1].first == run.last + 1
            // Out to the screen edge at either end of the journey.
            val start = if (position == 0) {
                min(origin, 0f) - 1
            } else {
                origin + run.first * cellWidth + (if (touchesPrevious) lineBreak / 2 else 0f)
            }
            val end = if (position == runs.size - 1) {
                max(origin + (cellCount + 2) * cellWidth, size.width) + 1
            } else {
                origin + (run.last + 1) * cellWidth - (if (touchesNext) lineBreak / 2 else 0f)
            }
            if (end <= start) return@forEachIndexed

            // One trip's stretch of line: solid for the primary trip, hatched for an extension.
            if (run.trip == null || run.trip == primaryTripId) {
                drawRect(
                    brush = cornerFade(Color.White.copy(alpha = 0.6f), width = size.width, screenCornerRadius = screenCornerRadius),
                    topLeft = Offset(start, top),
                    size = Size(end - start, height),
                )
            } else {
                val path = Path()
                val spacing = 5.dp.toPx()
                var x = start - height
                while (x < end) {
                    path.moveTo(x, bottom)
                    path.lineTo(x + height, top)
                    x += spacing
                }
                clipRect(left = start, top = top, right = end, bottom = bottom) {
                    drawPath(
                        path,
                        brush = cornerFade(Color.White.copy(alpha = 0.5f), width = size.width, screenCornerRadius = screenCornerRadius),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
        }
    }
}

/**
 * `color` across the width, fading to clear at each end where the screen's corner
 * curve has cut off the line, and whole from a little inside where the curve starts.
 */
private fun DrawScope.cornerFade(color: Color, width: Float, screenCornerRadius: Float): Brush {
    val r = screenCornerRadius
    if (r <= 0f || width <= 0f) return SolidColor(color)
    // How far in from the edge the curve is at the line's top.
    val inner = max(r - tripLineHeight.toPx(), 0f)
    val lost = r - sqrt(r * r - inner * inner)
    // The fade starts a little inside the curve, so it's well underway by the corner.
    val clear = min(lost / width, 0.5f)
    val opaque = min((r + 15.dp.toPx()) / width, 0.5f)
    val none = color.copy(alpha = 0f)
    return Brush.horizontalGradient(
        0f to none,
        clear to none,
        opaque to color,
        1 - opaque to color,
        1 - clear to none,
        1f to none,
        startX = 0f,
        endX = width,
    )
}

/**
 * The row's cells: each day, with every run of gap days between the user's trips
 * folded into one.
 */
private fun cells(days: List<ItineraryDay>): List<Cell> {
    val cells = mutableListOf<Cell>()
    for (day in days) {
        val last = cells.lastOrNull()
        if (day.isGap && last is Cell.Gap) {
            cells[cells.size - 1] = Cell.Gap(last.days + day)
        } else {
            cells.add(if (day.isGap) Cell.Gap(listOf(day)) else Cell.Day(day))
        }
    }
    return cells
}

private sealed interface Cell {
    data class Day(val day: ItineraryDay) : Cell

    /** A run of gap days, shown as plain dates rather than a circle. */
    data class Gap(val days: List<ItineraryDay>) : Cell
}

/** Text of an exact size that doesn't follow the system's font scale, as the circles don't. */
@Composable
private fun fixedFont(size: Dp, weight: FontWeight): TextStyle =
    TextStyle(fontSize = with(LocalDensity.current) { size.toSp() }, fontWeight = weight)

/**
 * The dates of a gap between the user's trips as plain text, e.g. "Dec 5", or the
 * first and last with a short rule between them. Sized like a day circle, so the
 * row keeps its rhythm across the gap.
 */
@Composable
private fun GapCell(gapDays: List<ItineraryDay>, modifier: Modifier = Modifier) {
    val dates = gapDays.mapNotNull { it.calendarDate }.map { WallClock.monthDay.withZone(ZoneId.systemDefault()).format(it) }
    val first = dates.firstOrNull() ?: ""
    val last = dates.lastOrNull() ?: ""
    val isRange = dates.size > 1
    val color = Color.appForeground.copy(alpha = 0.6f)
    val style = with(LocalDensity.current) {
        fixedFont(12.dp, FontWeight.SemiBold).copy(
            shadow = Shadow(Color.Black.copy(alpha = 0.4f), Offset(0f, 1.dp.toPx()), blurRadius = 3.dp.toPx()),
        )
    }
    Box(
        modifier.clearAndSetSemantics {
            contentDescription = if (isRange) "No trip $first to $last" else "No trip $first"
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.size(circleSize),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            Text(first, style = style, color = color, maxLines = 1, softWrap = false)
            if (isRange) {
                Box(Modifier.size(width = 10.dp, height = 1.5.dp).background(color, CircleShape))
                Text(last, style = style, color = color, maxLines = 1, softWrap = false)
            }
        }
    }
}

/**
 * A small dark plus disc past the first or last day, for adding days on that side.
 * Smaller than the day circles so it reads as an action, not another day.
 */
@Composable
private fun SegmentEnd(
    edge: HorizontalEdge,
    segmentEdges: Set<HorizontalEdge>,
    onAddSegment: (HorizontalEdge) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (edge in segmentEdges) {
        AddSegmentButton(edge, onClick = { onAddSegment(edge) }, modifier = modifier)
    } else {
        Spacer(modifier)
    }
}

@Composable
private fun AddSegmentButton(edge: HorizontalEdge, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .plainClickable(onClick = onClick)
            .semantics {
                contentDescription =
                    if (edge == HorizontalEdge.Leading) "Add a segment before the trip" else "Add a segment after the trip"
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .circleShadow(Color.Black.copy(alpha = 0.25f), radius = 6.dp, y = 3.dp)
                .background(Color.white(0.1f).copy(alpha = 0.9f), CircleShape)
                .border(1.dp, Color.white(0.28f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = null,
                tint = Color.appForeground.copy(alpha = 0.9f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun DayCell(day: ItineraryDay, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val selectionAnimation = tween<Color>(250, easing = EaseInOut)
    val textColor by animateColorAsState(
        if (isSelected) Color.white(0.15f) else Color.appForeground.copy(alpha = 0.9f),
        selectionAnimation,
        label = "dayText",
    )
    // Glass, as near as it comes here: a filled cream disc for the selected day, and
    // a clear one that only catches a little light for the others.
    val fill by animateColorAsState(
        if (isSelected) Color.white(0.9f) else Color.White.copy(alpha = 0.06f),
        selectionAnimation,
        label = "dayFill",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .size(circleSize)
                .circleShadow(Color.Black.copy(alpha = 0.25f), radius = 6.dp, y = 3.dp)
                .clip(CircleShape)
                .plainClickable(onClick = onClick)
                .semantics(mergeDescendants = true) { selected = isSelected }
                .background(fill)
                .border(
                    0.75.dp,
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.4f), Color.White.copy(alpha = 0.08f))),
                    CircleShape,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.5.dp, Alignment.CenterVertically),
        ) {
            Text(
                day.weekdayShort,
                style = fixedFont(7.dp, FontWeight.SemiBold).copy(
                    letterSpacing = with(LocalDensity.current) { 1.dp.toSp() },
                ),
                color = textColor.copy(alpha = textColor.alpha * 0.75f),
                maxLines = 1,
            )
            Text(
                "${day.dayOfMonth}",
                style = fixedFont(18.dp, FontWeight.Bold).monospacedDigit,
                color = textColor,
                maxLines = 1,
            )
        }
    }
}

/** A soft shadow under a circle filling this element, dropped `y` below it. */
private fun Modifier.circleShadow(color: Color, radius: Dp, y: Dp): Modifier = drawBehind {
    val circle = size.minDimension / 2
    val blur = radius.toPx()
    val outer = circle + blur
    val shadowCenter = center + Offset(0f, y.toPx())
    drawCircle(
        brush = Brush.radialGradient(
            0f to color,
            max(circle - blur, 0f) / outer to color,
            1f to Color.Transparent,
            center = shadowCenter,
            radius = outer,
        ),
        radius = outer,
        center = shadowCenter,
    )
}

@Preview
@Composable
private fun DaySelectorBarPreview() {
    AppTheme {
        var selection by remember { mutableIntStateOf(1) }
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.BottomCenter) {
            DaySelectorBar(
                days = ItineraryDay.sampleDays,
                selection = selection,
                onSelectionChange = { selection = it },
                primaryTripId = "sample",
            )
        }
    }
}
