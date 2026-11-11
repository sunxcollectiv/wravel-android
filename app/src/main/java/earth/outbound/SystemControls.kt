//
//  SystemControls.kt
//  outbound
//
//  The controls iOS gets from the system and the iOS app uses as they come: buttons,
//  spinners, text fields, sheets, confirmations and alerts. Drawn here to look and
//  behave like those, rather than as their Material counterparts, so every screen
//  matches the iOS app.
//

package earth.outbound

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// MARK: - Animation

/** The curves SwiftUI animates with, so motion matches the iOS app. */
object AppAnimation {
    /**
     * SwiftUI's `.snappy`: a spring with a touch of bounce that settles in about
     * `duration` seconds.
     */
    fun <T> snappy(duration: Double = 0.5): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.85f, stiffness = stiffness(duration))

    /** SwiftUI's `.smooth`: a spring with no bounce. */
    fun <T> smooth(duration: Double = 0.5): FiniteAnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = stiffness(duration))

    /** SwiftUI's default animation, as `withAnimation { }` runs: a spring with no bounce. */
    fun <T> default(): FiniteAnimationSpec<T> = smooth(0.55)

    /** SwiftUI's `.easeInOut`, which eases more evenly at both ends than Compose's default curve. */
    fun <T> easeInOut(duration: Double = 0.35): FiniteAnimationSpec<T> =
        tween((duration * 1000).toInt(), easing = easeInOutCurve)

    private val easeInOutCurve = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    /** A spring's stiffness for the response time SwiftUI describes it by. */
    private fun stiffness(duration: Double): Float = ((2 * PI / duration) * (2 * PI / duration)).toFloat()
}

// MARK: - Buttons

/**
 * Makes this tappable the way an iOS button is: no ripple, fading while pressed.
 * For SwiftUI's `Button` with the plain or default style, and `.onTapGesture`.
 */
fun Modifier.plainClickable(
    enabled: Boolean = true,
    dimsWhenPressed: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = Role.Button,
    onClick: () -> Unit,
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val alpha by animateFloatAsState(
        targetValue = if (isPressed && dimsWhenPressed && enabled) 0.5f else 1f,
        animationSpec = tween(if (isPressed) 0 else 250),
        label = "press",
    )
    this
        .graphicsLayer { this.alpha = alpha }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onClick = onClick,
        )
}

/** Tappable with no feedback at all, like `.onTapGesture` on a backdrop. */
fun Modifier.tapGesture(enabled: Boolean = true, onTap: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        enabled = enabled,
        onClick = onTap,
    )
}

/**
 * SwiftUI's `.borderedProminent` button: filled with the tint, its label in white,
 * greyed out when disabled.
 */
@Composable
fun ProminentButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = LocalTint.current,
    shape: Shape = RoundedCornerShape(appFieldCornerRadius),
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            // First, so a press fades the fill along with the label.
            .plainClickable(enabled = enabled, onClick = onClick)
            .clip(shape)
            .background(if (enabled) tint else Color.White.copy(alpha = 0.12f))
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (enabled) Color.White else Color.White.copy(alpha = 0.3f),
            LocalTextStyle provides AppFont.body,
        ) {
            content()
        }
    }
}

/**
 * The full-width prominent button under the app's forms and popups: its title in
 * bold, or a spinner while `isWorking`.
 */
@Composable
fun ProminentFormButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isWorking: Boolean = false,
) {
    ProminentButton(onClick = onClick, modifier = modifier.fillMaxWidth(), enabled = enabled) {
        Box(Modifier.defaultMinSize(minHeight = 32.dp), contentAlignment = Alignment.Center) {
            if (isWorking) {
                ProgressView()
            } else {
                Text(title, style = AppFont.body.bold)
            }
        }
    }
}

// MARK: - Progress

/** How big a `ProgressView` spinner is, like SwiftUI's `.controlSize`. */
enum class ControlSize(val diameter: Dp) {
    Small(14.dp), Regular(20.dp), Large(32.dp)
}

/**
 * The iOS activity spinner: eight spokes fading around a circle. Draws in the
 * content color unless `tint` is given.
 */
@Composable
fun ProgressView(
    modifier: Modifier = Modifier,
    controlSize: ControlSize = ControlSize.Regular,
    tint: Color = LocalContentColor.current,
) {
    val spokes = 8
    val transition = rememberInfiniteTransition(label = "spinner")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = spokes.toFloat(),
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    Canvas(modifier.size(controlSize.diameter)) {
        val radius = size.minDimension / 2
        val step = phase.toInt()
        for (index in 0 until spokes) {
            val angle = 2 * PI * index / spokes - PI / 2
            // The brightest spoke leads, and those behind it trail off.
            val age = ((step - index) % spokes + spokes) % spokes
            val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
            drawLine(
                color = tint.copy(alpha = tint.alpha * (1f - 0.75f * age / (spokes - 1))),
                start = center + direction * (radius * 0.45f),
                end = center + direction * (radius * 0.92f),
                strokeWidth = radius * 0.24f,
                cap = StrokeCap.Round,
            )
        }
    }
}

// MARK: - Text fields

/**
 * A single- or multi-line text field with no decoration of its own: the placeholder
 * shows while it's empty, and the cursor draws in the tint. Style the box around it
 * with `modifier` (e.g. `Modifier.appField()`), like a SwiftUI `TextField`.
 */
@Composable
fun PlainTextField(
    text: String,
    onTextChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = AppFont.body,
    color: Color = LocalContentColor.current,
    placeholderColor: Color = color.copy(alpha = 0.35f),
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    interactionSource: MutableInteractionSource? = null,
) {
    BasicTextField(
        value = text,
        onValueChange = onTextChange,
        modifier = modifier,
        enabled = enabled,
        textStyle = textStyle.copy(color = color),
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        interactionSource = interactionSource,
        cursorBrush = SolidColor(LocalTint.current),
        decorationBox = { field ->
            // The placeholder sits where the first character will: on the first line of
            // a field several lines tall, and centered when the text is.
            val isCentered = textStyle.textAlign == TextAlign.Center
            val alignment = when {
                singleLine -> if (isCentered) Alignment.Center else Alignment.CenterStart
                else -> if (isCentered) Alignment.TopCenter else Alignment.TopStart
            }
            Box(contentAlignment = alignment) {
                if (text.isEmpty()) {
                    Text(placeholder, style = textStyle, color = placeholderColor, maxLines = maxLines)
                }
                field()
            }
        },
    )
}

/**
 * The white, rounded input field used across forms (auth and trip creation):
 * `PlainTextField` in the `appField` box, typing in near-black.
 */
@Composable
fun AppTextField(
    text: String,
    onTextChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = AppFont.body,
    cornerRadius: Dp = appFieldCornerRadius,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    interactionSource: MutableInteractionSource? = null,
) {
    PlainTextField(
        text = text,
        onTextChange = onTextChange,
        placeholder = placeholder,
        modifier = modifier.fillMaxWidth().appField(cornerRadius),
        textStyle = textStyle,
        color = appFieldTextColor,
        placeholderColor = Color.Black.copy(alpha = 0.3f),
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        enabled = enabled,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        interactionSource = interactionSource,
    )
}

// MARK: - Sheets

/**
 * SwiftUI's `.sheet(isPresented:)`: a card that slides up over the screen, leaving a
 * strip of it dimmed above, and is dismissed by dragging it down, tapping the strip,
 * or going back.
 *
 * Declare it anywhere and leave it in the composition while closed; it's drawn over
 * the whole screen (see `Overlay`).
 */
@Composable
fun AppSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    background: Color = Color.sheetBackground,
    interactiveDismissDisabled: Boolean = false,
    content: @Composable () -> Unit,
) {
    AppSheet(
        item = if (isPresented) Unit else null,
        onDismiss = onDismiss,
        background = background,
        interactiveDismissDisabled = interactiveDismissDisabled,
    ) { content() }
}

/**
 * SwiftUI's `.sheet(item:)`: presented while `item` isn't null, handing it to
 * `content`. The sheet keeps showing the last item while it slides away, and a new
 * item while it's open is handed to the content already there.
 *
 * The sheet stays above the keyboard, so its content only has to keep clear of
 * `LocalSheetBottomInset`. `interactiveDismissDisabled` turns off dragging it down,
 * tapping outside and going back, leaving closing to the content's own buttons.
 */
@Composable
fun <T : Any> AppSheet(
    item: T?,
    onDismiss: () -> Unit,
    background: Color = Color.sheetBackground,
    interactiveDismissDisabled: Boolean = false,
    content: @Composable (T) -> Unit,
) {
    val shownItem = lastPresented(item)

    Overlay(
        isPresented = item != null,
        // Handled even when it does nothing, so going back doesn't close the screen underneath.
        onBack = { if (!interactiveDismissDisabled) onDismiss() },
    ) {
        // Each part below brings its own way in and out.
        AnimatedVisibility(
            visible = item != null,
            modifier = Modifier.fillMaxSize(),
            enter = EnterTransition.None,
            exit = ExitTransition.None,
        ) {
            val currentOnDismiss by rememberUpdatedState(onDismiss)
            val isDismissDisabled by rememberUpdatedState(interactiveDismissDisabled)
            Box(
                Modifier
                    .fillMaxSize()
                    .animateEnterExit(enter = fadeIn(tween(250)), exit = fadeOut(tween(250)))
                    .background(scrimColor)
                    .pointerInput(Unit) { detectTapGestures { if (!isDismissDisabled) currentOnDismiss() } }
            )
            SheetCard(
                background = background,
                interactiveDismissDisabled = interactiveDismissDisabled,
                onDismiss = onDismiss,
                modifier = Modifier.animateEnterExit(
                    enter = slideInVertically(AppAnimation.smooth(0.4)) { it },
                    exit = slideOutVertically(AppAnimation.smooth(0.35)) { it },
                ),
            ) {
                shownItem?.let { content(it) }
            }
        }
    }
}

/**
 * The sheet itself: under the status bar, down to the screen's bottom edge, following
 * a drag down on it or on scrolled content inside that's at its top.
 */
@Composable
private fun SheetCard(
    background: Color,
    interactiveDismissDisabled: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val drag = remember { SheetDrag() }
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val isDismissDisabled by rememberUpdatedState(interactiveDismissDisabled)
    val shape = RoundedCornerShape(topStart = sheetCornerRadius, topEnd = sheetCornerRadius)

    /** Drags the sheet, against some resistance when it can't be dismissed that way. */
    fun dragBy(delta: Float): Float {
        val moved = drag.dragBy(if (isDismissDisabled && delta > 0) delta / 4 else delta)
        return if (delta > 0) delta else moved
    }

    /** Lets go of the sheet: dismissed when dragged or flicked far enough, else back up. */
    fun release(velocity: Float) {
        val isFarEnough = drag.offset > with(density) { dismissDistance.toPx() } ||
            velocity > with(density) { dismissVelocity.toPx() }
        if (drag.offset > 0f && isFarEnough && !isDismissDisabled) {
            // Left where it is, for the sheet to slide away from there.
            currentOnDismiss()
        } else {
            drag.settle(scope)
        }
    }

    // Pulling down at the top of the sheet's scrolled content drags the sheet.
    val pullToDismiss = remember(drag, density) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Scrolling back up first puts a dragged sheet back.
                if (drag.offset <= 0f || available.y >= 0f) return Offset.Zero
                return Offset(0f, dragBy(available.y))
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // Only under a finger: a fling running into the top doesn't throw the sheet.
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                return Offset(0f, dragBy(available.y))
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (drag.offset <= 0f) return Velocity.Zero
                release(available.y)
                return available
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 10.dp)
            // An offset rather than a layer translation, so what's inside (a bottom
            // bar's offline pill anchor) hears about its new position as it's dragged.
            .offset { IntOffset(0, drag.offset.roundToInt()) }
            .shadow(24.dp, shape)
            .clip(shape)
            .background(background)
            // Taps on the sheet's own background stop here, not at the dimmed strip behind.
            .pointerInput(Unit) {}
            .nestedScroll(pullToDismiss)
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta -> dragBy(delta) },
                onDragStopped = { velocity -> release(velocity) },
            )
            .semantics { paneTitle = "Sheet" }
            // Up above the keyboard. The navigation bar's share of that is left to the
            // content, which keeps clear of it with or without a keyboard.
            .windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars)),
    ) {
        CompositionLocalProvider(LocalSheetBottomInset provides sheetBottomInset(), content = content)
    }
}

/**
 * How far a sheet has been dragged down. Plain state changed as each touch arrives,
 * so the sheet stays under the finger, and eased back when it's let go.
 */
@Stable
private class SheetDrag {
    /** In pixels, never above where the sheet rests. */
    var offset by mutableFloatStateOf(0f)
        private set

    private var settling: Job? = null

    /** Moves the sheet by `delta`, returning how far it went. */
    fun dragBy(delta: Float): Float {
        settling?.cancel()
        val target = (offset + delta).coerceAtLeast(0f)
        val moved = target - offset
        offset = target
        return moved
    }

    /** Eases the sheet back up to where it rests. */
    fun settle(scope: CoroutineScope) {
        settling?.cancel()
        settling = scope.launch {
            animate(initialValue = offset, targetValue = 0f, animationSpec = AppAnimation.snappy(0.3)) { value, _ ->
                offset = value
            }
        }
    }
}

private val sheetCornerRadius = 16.dp

/** How far down a sheet is dragged before letting go dismisses it. */
private val dismissDistance = 120.dp

/** How fast a downward flick dismisses a sheet, per second. */
private val dismissVelocity = 500.dp

/** The dimming over the screen behind a sheet, a confirmation or an alert. */
private val scrimColor = Color.Black.copy(alpha = 0.45f)

/**
 * The bottom safe area inside a sheet (the navigation bar's strip), for a sheet's
 * bottom bar to sit partway into; zero outside a sheet.
 */
val LocalSheetBottomInset = compositionLocalOf { 0.dp }

@Composable
private fun sheetBottomInset(): Dp =
    with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }

// MARK: - Confirmations and alerts

/** One choice in a `ConfirmationDialog` or `AppAlert`. */
data class DialogAction(
    val title: String,
    val isDestructive: Boolean = false,
    /** The choice that backs out; drawn apart from the others, in bold. */
    val isCancel: Boolean = false,
    val action: () -> Unit = {},
)

/**
 * SwiftUI's `.confirmationDialog`: an action sheet rising from the bottom of the
 * screen with a title, a message, the choices, and Cancel in a card of its own.
 * Tapping outside or going back cancels.
 */
@Composable
fun ConfirmationDialog(
    title: String,
    isPresented: Boolean,
    onDismiss: () -> Unit,
    message: String? = null,
    actions: List<DialogAction>,
) {
    Overlay(isPresented = isPresented, onBack = onDismiss) {
        // Each part below brings its own way in and out.
        AnimatedVisibility(
            visible = isPresented,
            modifier = Modifier.fillMaxSize(),
            enter = EnterTransition.None,
            exit = ExitTransition.None,
        ) {
            val currentOnDismiss by rememberUpdatedState(onDismiss)
            Box(
                Modifier
                    .fillMaxSize()
                    .animateEnterExit(enter = fadeIn(tween(200)), exit = fadeOut(tween(200)))
                    .background(scrimColor)
                    .pointerInput(Unit) { detectTapGestures { currentOnDismiss() } }
            )
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Column(
                    modifier = Modifier
                        .animateEnterExit(
                            enter = slideInVertically(AppAnimation.smooth(0.35)) { it },
                            exit = slideOutVertically(AppAnimation.smooth(0.3)) { it },
                        )
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                        .padding(horizontal = 8.dp)
                        .padding(bottom = 8.dp)
                        .widthIn(max = 420.dp)
                        .semantics { paneTitle = title },
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val shape = RoundedCornerShape(dialogCornerRadius)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(dialogSurface)
                            // Taps between the choices stop here, not at the dimmed screen behind.
                            .pointerInput(Unit) {}
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(title, style = AppFont.footnote.semibold, color = dialogSecondary, textAlign = TextAlign.Center)
                            if (message != null) {
                                Text(message, style = AppFont.footnote, color = dialogSecondary, textAlign = TextAlign.Center)
                            }
                        }
                        for (action in actions) {
                            DialogDivider()
                            DialogButton(action, style = AppFont.title3, height = 57.dp) {
                                onDismiss()
                                action.action()
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().clip(shape).background(dialogSurface)) {
                        DialogButton(DialogAction("Cancel", isCancel = true), style = AppFont.title3, height = 57.dp, onClick = onDismiss)
                    }
                }
            }
        }
    }
}

/**
 * SwiftUI's `.alert`: a small card in the middle of the screen with a title, a
 * message, and its choices along the bottom (side by side when there are two).
 * Going back dismisses it without choosing; tapping outside does nothing.
 */
@Composable
fun AppAlert(
    title: String,
    isPresented: Boolean,
    onDismiss: () -> Unit,
    message: String? = null,
    actions: List<DialogAction> = listOf(DialogAction("OK", isCancel = true)),
) {
    Overlay(isPresented = isPresented, onBack = onDismiss) {
        AnimatedVisibility(
            visible = isPresented,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            // Taps outside stop here, as an alert waits for one of its choices.
            Box(Modifier.fillMaxSize().background(scrimColor).pointerInput(Unit) {})
            Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
                Column(
                    Modifier
                        .animateEnterExit(
                            enter = scaleIn(AppAnimation.smooth(0.3), initialScale = 1.15f),
                            exit = scaleOut(tween(150), targetScale = 0.95f),
                        )
                        .width(270.dp)
                        .clip(RoundedCornerShape(dialogCornerRadius))
                        .background(dialogSurface)
                        .semantics { paneTitle = title },
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 19.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(title, style = AppFont.headline, color = Color.White, textAlign = TextAlign.Center)
                        if (message != null) {
                            Text(message, style = AppFont.footnote, color = Color.White, textAlign = TextAlign.Center)
                        }
                    }
                    DialogDivider()
                    val choose: (DialogAction) -> Unit = { action ->
                        onDismiss()
                        action.action()
                    }
                    if (actions.size == 2) {
                        Row(Modifier.height(44.dp)) {
                            DialogButton(actions[0], AppFont.body, 44.dp, Modifier.weight(1f)) { choose(actions[0]) }
                            Box(Modifier.width(0.5.dp).height(44.dp).background(dialogSeparator))
                            DialogButton(actions[1], AppFont.body, 44.dp, Modifier.weight(1f)) { choose(actions[1]) }
                        }
                    } else {
                        actions.forEachIndexed { index, action ->
                            if (index > 0) DialogDivider()
                            DialogButton(action, AppFont.body, 44.dp) { choose(action) }
                        }
                    }
                }
            }
        }
    }
}

private val dialogCornerRadius = 14.dp
private val dialogSurface = Color(red = 0.17f, green = 0.17f, blue = 0.18f)
private val dialogSecondary = Color(red = 0.92f, green = 0.92f, blue = 0.96f).copy(alpha = 0.6f)
private val dialogSeparator = Color.White.copy(alpha = 0.16f)
/** The blue iOS draws a dialog's choices in on dark surfaces. */
private val dialogBlue = Color(red = 0.04f, green = 0.52f, blue = 1.0f)

@Composable
private fun DialogDivider() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(dialogSeparator))
}

@Composable
private fun DialogButton(
    action: DialogAction,
    style: TextStyle,
    height: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier.fillMaxWidth().height(height).plainClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            action.title,
            style = if (action.isCancel) style.semibold else style,
            color = if (action.isDestructive) Color.systemDestructive else dialogBlue,
            maxLines = 1,
        )
    }
}

// MARK: - Pull to refresh

/**
 * SwiftUI's `.refreshable`: pulling the scrolling `content` down from its top shows
 * a spinner filling in above it, and pulling far enough runs `onRefresh`, the spinner
 * turning over the content until it's done.
 *
 * `content` has to scroll vertically (a `LazyColumn`, or `verticalScroll`), even if
 * there's too little in it to move.
 */
@Composable
fun Refreshable(
    onRefresh: suspend () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val currentOnRefresh by rememberUpdatedState(onRefresh)
    val triggerDistance = with(LocalDensity.current) { RefreshMetrics.triggerDistance.toPx() }
    val restingDistance = with(LocalDensity.current) { RefreshMetrics.restingDistance.toPx() }
    val indicatorSize = with(LocalDensity.current) { RefreshMetrics.indicatorSize.toPx() }

    val state = remember(scope) { RefreshState(scope) }
    val connection = remember(state, triggerDistance, restingDistance) {
        object : NestedScrollConnection {
            // Scrolling back up closes the gap before the content moves.
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                state.beginDrag()
                if (state.pull <= 0f || available.y >= 0f) return Offset.Zero
                val consumed = available.y.coerceAtLeast(-state.pull)
                state.pull += consumed
                return Offset(0f, consumed)
            }

            // What's left of a drag down once the content is at its top pulls it further.
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                state.pull += available.y * RefreshMetrics.resistance
                if (state.canRefresh && state.pull >= triggerDistance) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    state.refresh { currentOnRefresh() }
                }
                return Offset(0f, available.y)
            }

            // Let go: the content settles under the spinner while it's refreshing, or back at the top.
            override suspend fun onPreFling(available: Velocity): Velocity {
                state.isDragging = false
                if (state.pull <= 0f) return Velocity.Zero
                state.settle(if (state.isRefreshing) restingDistance else 0f)
                return available
            }
        }
    }

    Box(modifier.nestedScroll(connection)) {
        RefreshIndicator(
            progress = { state.pull / triggerDistance },
            isRefreshing = state.isRefreshing,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, ((min(state.pull, restingDistance) - indicatorSize) / 2).roundToInt()) }
                .graphicsLayer { alpha = (state.pull / restingDistance).coerceIn(0f, 1f) },
        )
        Box(Modifier.offset { IntOffset(0, state.pull.roundToInt()) }) {
            content()
        }
    }
}

/** The pull-to-refresh measurements. */
private object RefreshMetrics {
    /** How far the content has to be pulled down to start a refresh. */
    val triggerDistance = 68.dp

    /** How far down the content rests while refreshing, leaving room for the spinner. */
    val restingDistance = 52.dp

    val indicatorSize = 24.dp

    /** How much of the finger's travel the content follows, so the pull feels weighted. */
    const val resistance = 0.55f
}

/** How far a `Refreshable` is pulled, and whether it's refreshing. */
@Stable
private class RefreshState(private val scope: CoroutineScope) {
    /** How far down the content is pulled, in pixels. */
    var pull by mutableFloatStateOf(0f)
    var isRefreshing by mutableStateOf(false)
        private set

    /** Whether a finger is on the content, which then follows it rather than settling. */
    var isDragging = false

    /** Whether this pull has refreshed already; one pull refreshes once, however long it's held. */
    private var hasRefreshed = false
    private var settling: Job? = null

    /** Whether pulling far enough starts a refresh now. */
    val canRefresh: Boolean get() = !isRefreshing && !hasRefreshed

    fun beginDrag() {
        if (!isDragging) hasRefreshed = false
        isDragging = true
        settling?.cancel()
    }

    /** Eases the content to `target` pixels down. */
    fun settle(target: Float) {
        settling?.cancel()
        settling = scope.launch {
            animate(initialValue = pull, targetValue = target, animationSpec = AppAnimation.smooth(0.35)) { value, _ ->
                pull = value
            }
        }
    }

    fun refresh(action: suspend () -> Unit) {
        isRefreshing = true
        hasRefreshed = true
        scope.launch {
            try {
                action()
            } finally {
                isRefreshing = false
                if (!isDragging) settle(0f)
            }
        }
    }
}

/**
 * The spinner above pulled content: its spokes fill in one by one as the pull nears
 * the point of refreshing (`progress` reaching 1), and turn while it refreshes.
 */
@Composable
private fun RefreshIndicator(progress: () -> Float, isRefreshing: Boolean, modifier: Modifier = Modifier) {
    val tint = LocalContentColor.current.copy(alpha = 0.7f)
    if (isRefreshing) {
        ProgressView(modifier = modifier.size(RefreshMetrics.indicatorSize), tint = tint)
        return
    }
    Canvas(modifier.size(RefreshMetrics.indicatorSize)) {
        // Drawn to match `ProgressView`, which takes over once refreshing starts.
        val spokes = 8
        val shown = ceil(progress().coerceIn(0f, 1f) * spokes).toInt()
        val radius = size.minDimension / 2
        for (index in 0 until shown) {
            val angle = 2 * PI * index / spokes - PI / 2
            val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
            drawLine(
                color = tint,
                start = center + direction * (radius * 0.45f),
                end = center + direction * (radius * 0.92f),
                strokeWidth = radius * 0.24f,
                cap = StrokeCap.Round,
            )
        }
    }
}

// MARK: - Lists

/** The hairline between a list's rows. */
@Composable
fun ListSeparator(modifier: Modifier = Modifier) {
    Hairline(modifier)
}

// MARK: - Symbols and text

/**
 * How big an icon drawn alongside text in `style` is: its font size times `scale`.
 * An SF Symbol on iOS takes its size from the font it's set in and grows with Dynamic
 * Type; this gives a Material icon the same, following the system's font scale.
 */
@Composable
fun scaledSymbolSize(style: TextStyle, scale: Float = 1.2f): Dp =
    with(LocalDensity.current) { style.fontSize.toDp() * scale }

/** An icon and a line of text in one style and color, like a SwiftUI `Label`. */
@Composable
fun SymbolLabel(
    text: String,
    icon: ImageVector,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    spacing: Dp = 6.dp,
    maxLines: Int = Int.MAX_VALUE,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(scaledSymbolSize(style)))
        Text(text, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * One line of text that shrinks to fit its width, down to `minimumScaleFactor` of
 * its size, before it's cut short: SwiftUI's `.lineLimit(1).minimumScaleFactor(…)`.
 */
@Composable
fun ShrinkingText(
    text: String,
    style: TextStyle,
    minimumScaleFactor: Float,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
) {
    var scale by remember(text, style) { mutableFloatStateOf(1f) }
    /** Whether a size has been settled on; the text stays undrawn while trying them. */
    var isSettled by remember(text, style) { mutableStateOf(false) }
    Text(
        text,
        modifier = modifier.drawWithContent { if (isSettled) drawContent() },
        color = color,
        style = style.copy(fontSize = style.fontSize * scale),
        textAlign = textAlign,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout ->
            if (layout.hasVisualOverflow && scale > minimumScaleFactor) {
                scale = (scale - 0.05f).coerceAtLeast(minimumScaleFactor)
            } else {
                isSettled = true
            }
        },
    )
}

// MARK: - Small helpers

/**
 * Fades this while `disabled`, the look SwiftUI's `.disabled` gives a plain control.
 * Only the look: turn off what makes it tappable too (`plainClickable(enabled = …)`).
 */
fun Modifier.dimmed(disabled: Boolean, opacity: Float = 0.4f): Modifier = if (disabled) alpha(opacity) else this

/**
 * The last non-null `item`, for a sheet or popup presented while it has one: it keeps
 * showing what it was opened on while it animates away, as SwiftUI's `sheet(item:)`
 * does with the item it was presented for.
 */
@Composable
fun <T : Any> lastPresented(item: T?): T? {
    var shown by remember { mutableStateOf(item) }
    if (item != null && shown != item) shown = item
    return item ?: shown
}

/** Turns a chevron (or anything) a quarter turn at a time, e.g. for a disclosure arrow. */
fun Modifier.quarterTurns(turns: Float): Modifier = rotate(90f * turns)
