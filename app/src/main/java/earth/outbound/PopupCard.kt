//
//  PopupCard.kt
//  outbound
//

package earth.outbound

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The popup cards' measurements and animations. They live here rather than on
 * `PopupCard`, which is a composable function and so can't hold them itself.
 */
object PopupCardMetrics {
    /** The widest a card gets, on larger screens. */
    val maxWidth: Dp = 400.dp

    /** The gap between a card and the screen edge on phones. */
    val sideMargin: Dp = 16.dp
    val cornerRadius: Dp = 20.dp

    /** The tap area of the arrow buttons outside the card that jump to its top or bottom. */
    val jumpButtonSize: Dp = 32.dp

    /** The gap between a jump button and the card's edge. */
    val jumpButtonGap: Dp = 8.dp

    /** The room a jump button takes above the card, for a popup to keep clear. */
    val jumpButtonRoom: Dp get() = jumpButtonSize + jumpButtonGap

    /**
     * How the card eases to a new height; content changing size inside it should
     * match, e.g. `animateContentSize(PopupCardMetrics.resizeAnimation())`.
     */
    fun <T> resizeAnimation(): FiniteAnimationSpec<T> = AppAnimation.snappy(0.3)

    /**
     * For content added inside a card (`AnimatedVisibility(enter = contentEnter,
     * exit = contentExit)`): fades in once the card has grown part of the way, so it
     * shows up into room already made for it.
     */
    val contentEnter: EnterTransition = fadeIn(tween(durationMillis = 250, delayMillis = 120, easing = EaseOut))

    /** Fades removed content straight out, so the card isn't left waiting to shrink. */
    val contentExit: ExitTransition = fadeOut(tween(durationMillis = 120, easing = EaseIn))
}

/**
 * Shared look for the centered popup cards (members, invites, lodging, dates): the
 * card's size, shape, border and shadow, so every popup matches. A card hugs its
 * content, and scrolls inside its border once that's taller than the room it's given.
 *
 * `onClose` backs the accessibility dismiss action.
 */
@Composable
fun PopupCard(
    background: Color = Color.white(0.15f),
    border: Color = Color.White.copy(alpha = 0.08f),
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val scroll = remember { PopupCardScroll() }

    /** The content's natural height, padding included, in pixels. */
    var contentHeight by remember { mutableIntStateOf(0) }
    /** The height the card is drawn at, easing to the content's when that changes. */
    val shownHeight = remember { Animatable(0f) }
    /** Whether the card has had its first height, which just sizes it. */
    var isSized by remember { mutableStateOf(false) }
    /** The most height the card is given room for, in pixels. */
    var roomHeight by remember { mutableIntStateOf(Constraints.Infinity) }

    // Grows or shrinks the card to its content's new height, animated, so content
    // added or removed inside (an expanding section, a new row) eases the card along
    // instead of snapping it. The first measurement just sizes it.
    LaunchedEffect(contentHeight) {
        if (contentHeight == 0) return@LaunchedEffect
        if (isSized) {
            shownHeight.animateTo(contentHeight.toFloat(), PopupCardMetrics.resizeAnimation())
        } else {
            shownHeight.snapTo(contentHeight.toFloat())
            isSized = true
        }
    }

    // Whether the card is still animating to a new content height, during which its
    // content stays clipped so what's being added doesn't spill out past the border.
    val isResizing = isSized && (shownHeight.isRunning || shownHeight.targetValue != contentHeight.toFloat())

    // Whether the content fits without scrolling. Only then may it draw past the
    // card's edge, like a suggestion list; scrolled content has to stay inside.
    val fits = contentHeight > 0 && roomHeight >= contentHeight

    // Whether the jump buttons may show: only once the content scrolls, and not mid
    // resize, when growing content that will still fit would flash them.
    val showsJumpButtons = !fits && !isResizing
    val edgeSlack = with(LocalDensity.current) { 1.dp.toPx() }
    val atTop = scroll.offset <= edgeSlack
    val atBottom = scroll.offset >= scroll.maxOffset - edgeSlack

    val shape = RoundedCornerShape(PopupCardMetrics.cornerRadius)
    val overscrollEffect = ScrollableDefaults.overscrollEffect()
    val indicatorOpacity by animateFloatAsState(
        targetValue = if (scroll.scrollable.isScrollInProgress) 1f else 0f,
        animationSpec = if (scroll.scrollable.isScrollInProgress) tween(100) else tween(300, delayMillis = 600),
        label = "indicator",
    )

    CompositionLocalProvider(LocalContentColor provides Color.appForeground) {
        Box(
            modifier
                .padding(horizontal = PopupCardMetrics.sideMargin)
                .semantics {
                    isTraversalGroup = true
                    paneTitle = "Popup"
                    dismiss { onClose(); true }
                }
        ) {
            Box(
                Modifier
                    .widthIn(max = PopupCardMetrics.maxWidth)
                    .fillMaxWidth()
                    .shadow(24.dp, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
                    // The border sits behind the content (not over it), so anything floating out
                    // past the card's edge, like a suggestion list, covers it.
                    .drawBehind {
                        val radius = CornerRadius(PopupCardMetrics.cornerRadius.toPx())
                        val stroke = 1.dp.toPx()
                        drawRoundRect(background, cornerRadius = radius)
                        drawRoundRect(
                            border,
                            topLeft = Offset(stroke / 2, stroke / 2),
                            size = Size(size.width - stroke, size.height - stroke),
                            cornerRadius = CornerRadius(radius.x - stroke / 2),
                            style = Stroke(stroke),
                        )
                    }
                    .drawWithContent {
                        drawContent()
                        drawScrollIndicator(scroll, indicatorOpacity)
                    }
                    // Taps on the card's own background stop here, not at the dimmed screen behind.
                    .pointerInput(Unit) {}
                    // The card's height: its content's, eased, within the room there is.
                    .layout { measurable, constraints ->
                        roomHeight = constraints.maxHeight
                        val shown = if (isSized) shownHeight.value.roundToInt().coerceIn(0, constraints.maxHeight) else null
                        val placeable = measurable.measure(
                            constraints.copy(minHeight = 0, maxHeight = shown ?: constraints.maxHeight)
                        )
                        layout(placeable.width, shown ?: placeable.height) { placeable.place(0, 0) }
                    }
                    .graphicsLayer { clip = !fits || isResizing }
                    .overscroll(overscrollEffect)
                    // Always scrollable, rather than only when needed, so the content keeps
                    // its place in the layout, and a focused field its focus, as the room
                    // changes (e.g. when the keyboard comes up).
                    .scrollable(
                        state = scroll.scrollable,
                        orientation = Orientation.Vertical,
                        overscrollEffect = overscrollEffect,
                        enabled = !fits,
                        reverseDirection = true,
                    )
                    .semantics {
                        verticalScrollAxisRange = ScrollAxisRange(
                            value = { scroll.offset },
                            maxValue = { scroll.maxOffset.toFloat() },
                        )
                        scrollBy { _, y ->
                            scope.launch { scroll.scrollable.animateScrollBy(y) }
                            true
                        }
                    }
                    // The content at its natural height, moved up by how far it's scrolled.
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                        val height = placeable.height.coerceAtMost(constraints.maxHeight)
                        scroll.updateMaxOffset(placeable.height - height)
                        layout(placeable.width, height) {
                            placeable.place(0, -scroll.offset.roundToInt())
                        }
                    }
            ) {
                Box(Modifier.onSizeChanged { contentHeight = it.height }.padding(20.dp)) {
                    content()
                }
            }

            // Just outside the card's top and bottom right corners, each showing only
            // while there's more to scroll that way.
            JumpButton(edge = JumpEdge.Top, isShowing = showsJumpButtons && !atTop) {
                scope.launch { scroll.scrollable.animateScrollBy(-scroll.offset, AppAnimation.snappy(0.3)) }
            }
            JumpButton(edge = JumpEdge.Bottom, isShowing = showsJumpButtons && !atBottom) {
                scope.launch {
                    scroll.scrollable.animateScrollBy(scroll.maxOffset - scroll.offset, AppAnimation.snappy(0.3))
                }
            }
        }
    }
}

/** Where a card's content is scrolled to, for the jump buttons to move. */
@Stable
private class PopupCardScroll {
    /** How far the content is scrolled up, in pixels. */
    var offset by mutableFloatStateOf(0f)
        private set

    /** The furthest it scrolls: how much taller the content is than the card. */
    var maxOffset by mutableIntStateOf(0)
        private set

    val scrollable = ScrollableState { delta ->
        val target = (offset + delta).coerceIn(0f, maxOffset.toFloat())
        val consumed = target - offset
        offset = target
        consumed
    }

    fun updateMaxOffset(max: Int) {
        maxOffset = max
        if (offset > max) offset = max.toFloat()
    }
}

/**
 * The scroll indicator, showing while the content is scrolling. It runs right along
 * the card's edge, kept off the rounded corners at its ends.
 */
private fun DrawScope.drawScrollIndicator(scroll: PopupCardScroll, opacity: Float) {
    if (opacity <= 0f || scroll.maxOffset <= 0) return
    val margin = PopupCardMetrics.cornerRadius.toPx() / 2
    val track = size.height - margin * 2
    val thickness = 3.dp.toPx()
    val length = (track * size.height / (size.height + scroll.maxOffset)).coerceIn(minOf(24.dp.toPx(), track), track)
    val top = margin + (track - length) * (scroll.offset / scroll.maxOffset)
    drawRoundRect(
        color = Color.White.copy(alpha = 0.35f * opacity),
        topLeft = Offset(size.width - thickness - 2.dp.toPx(), top),
        size = Size(thickness, length),
        cornerRadius = CornerRadius(thickness / 2),
    )
}

/** The edge of a card's content a jump button scrolls to. */
private enum class JumpEdge { Top, Bottom }

@Composable
private fun BoxScope.JumpButton(edge: JumpEdge, isShowing: Boolean, onClick: () -> Unit) {
    val shown by animateFloatAsState(if (isShowing) 1f else 0f, AppAnimation.easeInOut(0.2), label = "jump")
    Box(
        modifier = Modifier
            .align(if (edge == JumpEdge.Top) Alignment.TopEnd else Alignment.BottomEnd)
            .offset(y = if (edge == JumpEdge.Top) -PopupCardMetrics.jumpButtonRoom else PopupCardMetrics.jumpButtonRoom)
            .graphicsLayer {
                alpha = shown
                scaleX = 0.8f + 0.2f * shown
                scaleY = 0.8f + 0.2f * shown
            }
            // A bare glyph, with a frame big enough to tap easily.
            .size(PopupCardMetrics.jumpButtonSize)
            .then(
                if (isShowing) {
                    Modifier
                        .plainClickable(onClick = onClick)
                        .semantics { contentDescription = if (edge == JumpEdge.Top) "Scroll to Top" else "Scroll to Bottom" }
                } else {
                    Modifier.clearAndSetSemantics {}
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        JumpToEdgeIcon(edge = edge, color = Color.appForeground.copy(alpha = 0.6f))
    }
}

/**
 * A popup card centered over a dimmed screen, with a round close button just below
 * it; tapping the button or the dimmed area, or going back, closes it. Declare it
 * anywhere and leave it in the composition while closed: it's drawn over the whole
 * screen (see `Overlay`), the backdrop fading and the card scaling in and out as
 * `isPresented` changes, and it moves the offline pill down to meet it while open.
 *
 * The card and close button keep clear of the offline pill at the bottom; a card
 * taller than the room left scrolls (see `PopupCard`).
 */
@Composable
fun DimmedPopup(
    isPresented: Boolean,
    onClose: () -> Unit,
    card: @Composable () -> Unit,
) {
    Overlay(isPresented = isPresented, onBack = onClose) {
        // Outside what animates away, so the pill moves back as soon as the popup
        // starts closing rather than once it's gone.
        Box(Modifier.fillMaxSize().offlinePillNearBottom(isActive = isPresented))

        // Each part below brings its own way in and out.
        AnimatedVisibility(
            visible = isPresented,
            modifier = Modifier.fillMaxSize(),
            enter = EnterTransition.None,
            exit = ExitTransition.None,
        ) {
            val currentOnClose by rememberUpdatedState(onClose)

            Box(
                Modifier
                    .fillMaxSize()
                    .animateEnterExit(enter = fadeIn(AppAnimation.default()), exit = fadeOut(AppAnimation.default()))
                    .background(Color.Black.copy(alpha = 0.8f))
                    .pointerInput(Unit) { detectTapGestures { currentOnClose() } }
            )

            PopupArea(
                onClose = onClose,
                modifier = Modifier.animateEnterExit(
                    enter = scaleIn(AppAnimation.default(), initialScale = 0.94f) + fadeIn(AppAnimation.default()),
                    exit = scaleOut(AppAnimation.default(), targetScale = 0.94f) + fadeOut(AppAnimation.default()),
                ),
                card = card,
            )
        }
    }
}

/** The gap kept between a popup and the top of the screen, and the offline pill. */
private val edgeGap = 12.dp

/** The card over its close button, in the middle of the room the screen leaves them. */
@Composable
private fun PopupArea(onClose: () -> Unit, modifier: Modifier = Modifier, card: @Composable () -> Unit) {
    val density = LocalDensity.current
    /** Height of the visible area, which shrinks while the keyboard is up. */
    var visibleHeight by remember { mutableStateOf(0.dp) }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    // Room kept free at the bottom of the screen (or above the keyboard) for the
    // offline pill, which sits there over a popup.
    val pillClearance = offlinePillNearBottomGap() + OfflinePillMetrics.scaledHeight() + edgeGap

    Box(modifier.fillMaxSize().statusBarsPadding()) {
        // The visible area: under the status bar, over the navigation bar or keyboard.
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom))
                .onGloballyPositioned {
                    coordinates = it
                    visibleHeight = with(density) { it.size.height.toDp() }
                }
        )

        CompositionLocalProvider(
            LocalPopupVisibleHeight provides visibleHeight,
            LocalPopupCoordinates provides coordinates,
        ) {
            // Measured from the screen's bottom edge, like the pill's own spot; the
            // keyboard still pushes it up.
            Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier
                        // Room above for the card's jump-to-top button.
                        .padding(top = edgeGap + PopupCardMetrics.jumpButtonRoom)
                        .padding(bottom = pillClearance),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Above the close button, so anything floating off the card's bottom
                    // edge (like a suggestion list) draws over it. The button gets its room
                    // first, and the card what's left.
                    Box(Modifier.weight(1f, fill = false).zIndex(1f)) {
                        card()
                    }
                    CloseButton(onClose = onClose)
                }
            }
        }
    }
}

@Composable
private fun CloseButton(onClose: () -> Unit) {
    val iconSize = scaledSymbolSize(AppFont.body, scale = 1.3f)
    Box(
        modifier = Modifier
            .size(44.dp)
            // First, so a press fades the whole button rather than just its glyph.
            .plainClickable(onClick = onClose)
            .semantics { contentDescription = "Close" }
            .shadow(12.dp, CircleShape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
            .background(Color.white(0.15f), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Close, contentDescription = null, tint = Color.appForeground, modifier = Modifier.size(iconSize))
    }
}

/**
 * Height of the visible area a `DimmedPopup` fills (above the keyboard when it's
 * up); zero outside a popup.
 */
val LocalPopupVisibleHeight = compositionLocalOf { 0.dp }

/**
 * The visible area a `DimmedPopup` sits in, so a card can tell how much room is left
 * above or below an element of its own: from that element's `onGloballyPositioned`,
 * `LocalPopupCoordinates.current?.localPositionOf(coordinates, Offset.Zero)` is where
 * it is within the area, whose height is `LocalPopupVisibleHeight`. Null outside a popup.
 */
val LocalPopupCoordinates = compositionLocalOf<LayoutCoordinates?> { null }

/**
 * A chevron with a bar across its tip, pointing to the top or bottom edge: like an
 * arrow up to a line without the arrow's shaft.
 */
@Composable
private fun JumpToEdgeIcon(edge: JumpEdge, color: Color) {
    val width = 12.dp * LocalDensity.current.fontScale
    Canvas(Modifier.size(width = width * 1.1f, height = width * 0.9f)) {
        val unit = width.toPx()
        val stroke = unit * 0.14f
        val barY = if (edge == JumpEdge.Top) stroke / 2 else size.height - stroke / 2
        drawLine(color, Offset(stroke / 2, barY), Offset(size.width - stroke / 2, barY), stroke, StrokeCap.Round)

        // The chevron fills the rest, a gap away from the bar, its tip towards it.
        val gap = unit * 0.2f
        val inset = stroke / 2
        val tipY = if (edge == JumpEdge.Top) stroke + gap + inset else size.height - stroke - gap - inset
        val endsY = if (edge == JumpEdge.Top) size.height - inset else inset
        val tip = Offset(size.width / 2, tipY)
        drawLine(color, Offset(inset, endsY), tip, stroke, StrokeCap.Round)
        drawLine(color, tip, Offset(size.width - inset, endsY), stroke, StrokeCap.Round)
    }
}
