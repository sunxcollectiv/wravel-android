//
//  Theme.kt
//  outbound
//

package earth.outbound

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Central color palette for the app, matching the warm terracotta travel theme.

/** Warm terracotta/clay used as the primary background. */
val Color.Companion.appBackground get() = Color(red = 0.69f, green = 0.42f, blue = 0.27f)

/** Cream/off-white used for primary text and icons. */
val Color.Companion.appForeground get() = Color(red = 0.95f, green = 0.91f, blue = 0.84f)

/** Accent used for buttons and highlights: a slightly deeper rust. */
val Color.Companion.appAccent get() = Color(red = 0.55f, green = 0.30f, blue = 0.18f)

/** Translucent card surface that sits over the background. */
val Color.Companion.appCard get() = Color(red = 0.62f, green = 0.36f, blue = 0.23f)

/**
 * Slightly lifted dark gray for the profile drawer, so it reads as a layer above
 * the trips grid.
 */
val Color.Companion.drawerBackground get() = Color(red = 0.20f, green = 0.20f, blue = 0.21f)

/** Dark gray behind modal form sheets, such as adding or editing a trip item. */
val Color.Companion.sheetBackground get() = Color(red = 0.20f, green = 0.20f, blue = 0.21f)

/** Slightly pinkish red for error and warning text, readable on the dark surfaces. */
val Color.Companion.appError get() = Color(red = 1.0f, green = 0.42f, blue = 0.47f)

/** Muted slate blue filling the header chip at the top of a trip's itinerary. */
val Color.Companion.tripHeader get() = Color(red = 0.29f, green = 0.39f, blue = 0.51f)

/** The red of `Color.red` on iOS. */
val Color.Companion.systemRed get() = Color(red = 1f, green = 0.23f, blue = 0.19f)

/** The green of `Color.green` on iOS. */
val Color.Companion.systemGreen get() = Color(red = 0.20f, green = 0.78f, blue = 0.35f)

/**
 * The brighter red iOS's own controls draw a destructive action in on dark surfaces
 * (a dialog's destructive choice, a list row's swipe to delete).
 */
val Color.Companion.systemDestructive get() = Color(red = 1.0f, green = 0.27f, blue = 0.23f)

/** A gray of the given brightness, like SwiftUI's `Color(white:)`. */
fun Color.Companion.white(white: Float, opacity: Float = 1f) = Color(white, white, white, opacity)

/**
 * Very dark backdrop behind the trips grid, so the colored trip cards stand out: a
 * subtle fade from a warmer, lighter gray at the top to near-black at the bottom.
 */
val Brush.Companion.tripsBackground
    get() = verticalGradient(
        listOf(
            Color(red = 0.21f, green = 0.19f, blue = 0.18f),
            Color(red = 0.10f, green = 0.10f, blue = 0.11f),
        )
    )

/**
 * The app's type scale, matching the iOS text styles the screens are designed in
 * (`.title3`, `.subheadline`, `.footnote`…) at the default text size. Sizes are in sp,
 * so they follow the system's font scale the way Dynamic Type does.
 */
object AppFont {
    val largeTitle = textStyle(size = 34, lineHeight = 41)
    val title = textStyle(size = 28, lineHeight = 34)
    val title2 = textStyle(size = 22, lineHeight = 28)
    val title3 = textStyle(size = 20, lineHeight = 25)
    val headline = textStyle(size = 17, lineHeight = 22).copy(fontWeight = FontWeight.SemiBold)
    val body = textStyle(size = 17, lineHeight = 22)
    val callout = textStyle(size = 16, lineHeight = 21)
    val subheadline = textStyle(size = 15, lineHeight = 20)
    val footnote = textStyle(size = 13, lineHeight = 18)
    val caption = textStyle(size = 12, lineHeight = 16)
    val caption2 = textStyle(size = 11, lineHeight = 13)

    /**
     * An iOS text style. Every line is its full line height tall, the first and last
     * included (Compose trims those by default), so a line of text takes the room it
     * does on iOS and the paddings around it carry over unchanged.
     */
    private fun textStyle(size: Int, lineHeight: Int) = TextStyle(
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )

    /** A font of an exact size, like SwiftUI's `.system(size:weight:)`. */
    fun system(size: Float, weight: FontWeight = FontWeight.Normal) =
        TextStyle(fontSize = size.sp, fontWeight = weight)
}

val TextStyle.medium get() = copy(fontWeight = FontWeight.Medium)
val TextStyle.semibold get() = copy(fontWeight = FontWeight.SemiBold)
val TextStyle.bold get() = copy(fontWeight = FontWeight.Bold)
val TextStyle.heavy get() = copy(fontWeight = FontWeight.ExtraBold)

/** Digits all the same width, so numbers don't shift as they change. */
val TextStyle.monospacedDigit get() = copy(fontFeatureSettings = "tnum")

/** Shared corner radius for input fields and buttons, keeping them visually in sync. */
val appFieldCornerRadius: Dp = 10.dp

/**
 * The tint controls take, like SwiftUI's `.tint`: prominent buttons fill with it, and
 * text fields draw their cursor in it.
 */
val LocalTint = staticCompositionLocalOf { Color.appAccent }

/**
 * Applies the app's warm terracotta color theme: background, foreground, and tint.
 *
 * Wrap the top of a screen hierarchy in this. It's also used in previews so they
 * match what runs on a device (where theming comes from `AuthView`).
 */
@Composable
fun AppTheme(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalContentColor provides Color.appForeground,
        LocalTextStyle provides AppFont.body,
        LocalTint provides Color.appAccent,
        LocalTextSelectionColors provides TextSelectionColors(
            handleColor = Color.appAccent,
            backgroundColor = Color.appAccent.copy(alpha = 0.4f),
        ),
    ) {
        Box(modifier.fillMaxSize().background(Color.appBackground)) { content() }
    }
}

/** Sets the color text and icons inside draw in, like SwiftUI's `.foregroundStyle`. */
@Composable
fun ForegroundStyle(color: Color, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides color, content = content)
}

/**
 * A tappable summary row on the dark item forms (Members, a stay's rooms): padded,
 * on a faint rounded fill, tappable edge to edge.
 */
fun Modifier.formRow(): Modifier =
    background(Color.appForeground.copy(alpha = 0.08f), RoundedCornerShape(appFieldCornerRadius))
        .padding(14.dp)

/** The small grey label above a form field or section, e.g. "Room details". */
val formLabelStyle: TextStyle
    get() = AppFont.subheadline.copy(color = Color.appForeground.copy(alpha = 0.8f))

/** Red text spanning the width, for a delete action under a form or in a popup. */
val inlineDeleteTextStyle: TextStyle
    get() = AppFont.subheadline.semibold.copy(color = Color.appError)

/** The frame `inlineDeleteTextStyle` text sits in: full width, and tall enough to tap. */
fun Modifier.inlineDeleteText(): Modifier = fillMaxWidth().defaultMinSize(minHeight = 44.dp)

/**
 * White, rounded input field used across forms (auth and trip creation). The text
 * inside draws in near-black; see `AppTextField`, which applies this itself.
 */
fun Modifier.appField(cornerRadius: Dp = appFieldCornerRadius): Modifier =
    background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(cornerRadius))
        .padding(14.dp)

/** The color of text typed into an `appField`. */
val appFieldTextColor: Color get() = Color.Black.copy(alpha = 0.85f)

/**
 * The dark, bordered look of small messages floating over a screen, like the
 * itinerary's toasts and the offline pill.
 */
fun Modifier.floatingPillBackground(shape: Shape): Modifier =
    shadow(elevation = 12.dp, shape = shape, ambientColor = Color.Black, spotColor = Color.Black)
        .clip(shape)
        .background(Color.white(0.15f).copy(alpha = 0.95f))
        .border(1.dp, Color.White.copy(alpha = 0.12f), shape)

/**
 * The small capitalized title at the top of a popup card, with its icon: the tracked,
 * uppercase caption `Label` every popup on iOS opens with.
 */
@Composable
fun PopupHeaderLabel(title: String, icon: ImageVector, modifier: Modifier = Modifier) {
    SymbolLabel(
        title.uppercase(),
        icon = icon,
        style = AppFont.caption.semibold.copy(letterSpacing = 1.sp),
        color = Color.appForeground.copy(alpha = 0.6f),
        modifier = modifier,
    )
}

/**
 * A hairline across its container, between rows or parts of a popup, like SwiftUI's
 * `Divider`: the foreground color at `opacity`.
 */
@Composable
fun Hairline(modifier: Modifier = Modifier, opacity: Float = 0.12f) {
    Spacer(modifier.fillMaxWidth().height(0.5.dp).background(Color.appForeground.copy(alpha = opacity)))
}

/**
 * A dashed outline just inside this element's edge, for a slot waiting to be filled:
 * SwiftUI's `strokeBorder(style: StrokeStyle(lineWidth:dash:))` on a rounded rectangle.
 */
fun Modifier.dashedOutline(
    color: Color,
    cornerRadius: Dp,
    lineWidth: Dp = 1.5.dp,
    dash: Dp = 6.dp,
    gap: Dp = 4.dp,
): Modifier = drawBehind {
    val stroke = lineWidth.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius((cornerRadius.toPx() - stroke / 2).coerceAtLeast(0f)),
        style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash.toPx(), gap.toPx()))),
    )
}
