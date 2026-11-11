//
//  LeadingDrawer.kt
//  outbound
//

package earth.outbound

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Wraps a screen with a drawer that slides in over it from the leading edge.
 * It opens through `isOpen` or by swiping right from the screen's left edge, and
 * closes by tapping the dimmed screen, swiping it back to the left, or going back.
 */
@Composable
fun LeadingDrawer(
    isOpen: Boolean,
    onIsOpenChange: (Boolean) -> Unit,
    /**
     * Whether a swipe from the left edge opens the drawer. Turned off while another
     * screen is pushed, so the swipe stays the system back gesture there.
     */
    edgeSwipeEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
    drawer: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    /**
     * The horizontal distance the current drag has moved the drawer, in pixels. Plain
     * state set as each touch arrives, so the drawer stays under the finger.
     */
    var dragTranslation by remember { mutableFloatStateOf(0f) }
    /** The drag's distance easing back to nothing once it's let go. */
    var settling by remember { mutableStateOf<Job?>(null) }
    /** How open the drawer is at rest, easing between closed (0) and open (1). */
    val openFraction by animateFloatAsState(if (isOpen) 1f else 0f, AppAnimation.snappy(0.3), label = "drawer")

    // What's left of the drag eases away as the drawer eases to its new rest, on the
    // same curve, so together they carry it from where it was let go.
    fun setOpen(open: Boolean) {
        onIsOpenChange(open)
        settling?.cancel()
        settling = scope.launch {
            animate(initialValue = dragTranslation, targetValue = 0f, animationSpec = AppAnimation.snappy(0.3)) { value, _ ->
                dragTranslation = value
            }
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val width = min(maxWidth * 0.8f, 320.dp)
        val widthPx = with(LocalDensity.current) { width.toPx() }
        val edgeWidthPx = with(LocalDensity.current) { edgeWidth.toPx() }
        // Read where they're drawn rather than here, so a drag redraws without recomposing.
        val revealed = { (openFraction * widthPx + dragTranslation).coerceIn(0f, widthPx) }

        val currentIsOpen by rememberUpdatedState(isOpen)
        val currentWidth by rememberUpdatedState(widthPx)
        val currentSetOpen by rememberUpdatedState(::setOpen)
        val canDrag = isOpen || edgeSwipeEnabled

        Box(
            Modifier
                .fillMaxSize()
                // Lets a swipe from the edge reach the drawer instead of going back,
                // as far up the edge as the system allows an app to claim.
                .then(
                    if (edgeSwipeEnabled && !isOpen) {
                        Modifier.systemGestureExclusion { Rect(0f, 0f, edgeWidthPx, it.size.height.toFloat()) }
                    } else {
                        Modifier
                    }
                )
                .then(
                    if (canDrag) {
                        Modifier.pointerInput(Unit) {
                            trackDrag(
                                startsTracking = { start -> currentIsOpen || start.x <= edgeWidthPx },
                                onChanged = { translation ->
                                    settling?.cancel()
                                    dragTranslation = translation
                                },
                                onEnded = { predictedEndTranslation ->
                                    val projected = (if (currentIsOpen) currentWidth else 0f) + predictedEndTranslation
                                    currentSetOpen(projected > currentWidth / 2)
                                },
                            )
                        }
                    } else {
                        Modifier
                    }
                )
        ) {
            Box(Modifier.fillMaxSize().then(if (isOpen) Modifier.clearAndSetSemantics {} else Modifier)) {
                content()
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind { drawRect(Color.Black, alpha = 0.5f * revealed() / widthPx) }
                    .then(
                        if (isOpen) {
                            Modifier
                                .tapGesture { setOpen(false) }
                                .semantics {
                                    contentDescription = "Close Menu"
                                    role = Role.Button
                                    onClick { setOpen(false); true }
                                }
                        } else {
                            Modifier
                        }
                    )
            )

            Box(
                Modifier
                    .width(width)
                    .fillMaxHeight()
                    .graphicsLayer { translationX = revealed() - widthPx }
                    // Taps on the drawer's own background stop here, not at the dimmed screen behind.
                    .pointerInput(Unit) {}
                    .then(if (isOpen) Modifier.semantics { paneTitle = "Menu" } else Modifier.clearAndSetSemantics {})
            ) {
                drawer()
            }
        }
    }

    // After the screen, so going back closes an open drawer before anything the
    // screen's own handlers would do.
    BackHandler(enabled = isOpen) { setOpen(false) }
}

/** How close to the left edge a swipe must start to open the drawer. */
private val edgeWidth = 28.dp

/** How far a drag has to go before it counts as one, and its direction is read. */
private val minimumDistance = 10.dp

/**
 * How many seconds of travel at its final speed a drag is taken to still have in it
 * when let go, for telling where it was headed.
 */
private const val projectionTime = 0.25f

/**
 * Follows each drag alongside whatever is under it, without taking it over: whether
 * a drag moves the drawer is decided once it has gone `minimumDistance`, so a
 * vertical scroll, or a swipe `startsTracking` turns down for where it started,
 * leaves the drawer alone and carries on to the screen. One that does move the
 * drawer is kept from the screen from then on.
 *
 * `onChanged` gets the horizontal distance dragged so far, and `onEnded` that
 * distance carried on by the speed the drag was let go at.
 */
private suspend fun PointerInputScope.trackDrag(
    startsTracking: (start: Offset) -> Boolean,
    onChanged: (translation: Float) -> Unit,
    onEnded: (predictedEndTranslation: Float) -> Unit,
) {
    awaitEachGesture {
        // On the way down to the screen's own controls, so they can't take it first.
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val velocityTracker = VelocityTracker()
        velocityTracker.addPosition(down.uptimeMillis, down.position)
        var isTracking: Boolean? = null
        var translation = Offset.Zero

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            velocityTracker.addPosition(change.uptimeMillis, change.position)
            if (!change.pressed) break
            translation = change.position - down.position

            if (isTracking == null && translation.getDistance() >= minimumDistance.toPx()) {
                val horizontal = abs(translation.x) > abs(translation.y)
                isTracking = horizontal && startsTracking(down.position)
            }
            if (isTracking == true) {
                change.consume()
                onChanged(translation.x)
            }
        }

        if (isTracking == true) {
            onEnded(translation.x + velocityTracker.calculateVelocity().x * projectionTime)
        }
    }
}
