//
//  Navigation.kt
//  outbound
//
//  What iOS gets from `NavigationStack`, drawn here for the screens that use it as it
//  comes: a screen pushed over another with its bar and back button, and the bar
//  across the top of a sheet.
//

package earth.outbound

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// MARK: - Pushed screens

/** How a pushed screen slides in and out, like a navigation push on iOS. */
private fun <T> pushAnimation(): FiniteAnimationSpec<T> = AppAnimation.smooth(0.4)

/** The height of a screen's top bar, under the status bar. */
internal val navigationBarHeight = 44.dp

/** The gap between a bar's last button and the screen edge, which puts its glyph on the 16dp margin. */
internal val barButtonInset = 5.dp

/**
 * A screen pushed over the one that declares it, like SwiftUI's
 * `navigationDestination(isPresented:)`: it slides in from the right over the trips
 * backdrop and back out again, and going back closes it. Declare it after the screen
 * it covers, in a box they both fill, and give that screen `coveredByPushedScreen`.
 */
@Composable
internal fun PushedScreen(isPresented: Boolean, onBack: () -> Unit, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = isPresented,
        enter = slideInHorizontally(pushAnimation()) { it },
        exit = slideOutHorizontally(pushAnimation()) { it },
    ) {
        BackHandler(enabled = isPresented, onBack = onBack)
        Box(
            Modifier
                .fillMaxSize()
                .shadow(16.dp, ambientColor = Color.Black, spotColor = Color.Black)
                .background(Brush.tripsBackground)
                // Touches stop here, not at the screen underneath.
                .pointerInput(Unit) {}
        ) {
            content()
        }
    }
}

/**
 * Moves this screen part of the way off to the left while another is pushed over
 * it, and hides it from accessibility meanwhile.
 */
internal fun Modifier.coveredByPushedScreen(isCovered: Boolean): Modifier = composed {
    val covered by animateFloatAsState(if (isCovered) 1f else 0f, pushAnimation(), label = "covered")
    graphicsLayer { translationX = -size.width * 0.3f * covered.coerceIn(0f, 1f) }
        .then(if (isCovered) Modifier.clearAndSetSemantics {} else Modifier)
}

/**
 * The bar across the top of a pushed screen: the back button, the title in the
 * middle, and `trailing` buttons (each a `BarButton`) on the right.
 */
@Composable
internal fun PushedScreenBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    ForegroundStyle(Color.White) {
        Box(modifier.fillMaxWidth().statusBarsPadding().height(navigationBarHeight)) {
            BarButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).padding(start = barButtonInset)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBackIos, contentDescription = "Back", modifier = Modifier.size(20.dp))
            }
            Text(
                title,
                style = AppFont.headline,
                maxLines = 1,
                modifier = Modifier.align(Alignment.Center).semantics { heading() },
            )
            Row(Modifier.align(Alignment.CenterEnd).padding(end = barButtonInset)) {
                trailing()
            }
        }
    }
}

/** A glyph button on a screen's top bar, with a tap area as tall as the bar. */
@Composable
internal fun BarButton(onClick: () -> Unit, modifier: Modifier = Modifier, label: @Composable () -> Unit) {
    Box(modifier.size(navigationBarHeight).plainClickable(onClick = onClick), contentAlignment = Alignment.Center) {
        label()
    }
}

// MARK: - Sheets

/**
 * The bar across the top of a sheet, like a sheet's navigation bar on iOS: the title
 * in the middle, between what the sheet puts at its `leading` and `trailing` ends
 * (a `SheetBarButton` or a `SheetBarCloseButton`). The title keeps clear of the
 * wider of the two on both sides, so it stays centered.
 */
@Composable
internal fun SheetBar(
    title: String,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    val titleText: @Composable () -> Unit = {
        Text(
            title,
            style = AppFont.headline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
    }
    Layout(
        contents = listOf(leading, titleText, trailing),
        modifier = modifier.fillMaxWidth().height(navigationBarHeight),
    ) { (leadingMeasurables, titleMeasurables, trailingMeasurables), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val leadingPlaceables = leadingMeasurables.map { it.measure(loose) }
        val trailingPlaceables = trailingMeasurables.map { it.measure(loose) }
        val sideWidth = maxOf(leadingPlaceables.sumOf { it.width }, trailingPlaceables.sumOf { it.width })
        val titleWidth = constraints.maxWidth - 2 * (sideWidth + sheetBarTitleGap.roundToPx())
        val titlePlaceable = titleMeasurables.first().measure(loose.copy(maxWidth = titleWidth.coerceAtLeast(0)))

        val width = constraints.maxWidth
        val height = constraints.maxHeight
        layout(width, height) {
            var x = 0
            for (placeable in leadingPlaceables) {
                placeable.placeRelative(x, (height - placeable.height) / 2)
                x += placeable.width
            }
            x = width - trailingPlaceables.sumOf { it.width }
            for (placeable in trailingPlaceables) {
                placeable.placeRelative(x, (height - placeable.height) / 2)
                x += placeable.width
            }
            titlePlaceable.placeRelative((width - titlePlaceable.width) / 2, (height - titlePlaceable.height) / 2)
        }
    }
}

/** The gap kept between a sheet bar's title and the buttons at its ends. */
private val sheetBarTitleGap = 8.dp

/** A worded button at an end of a `SheetBar`, e.g. "Cancel", with a tap area as tall as the bar. */
@Composable
internal fun SheetBarButton(title: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.height(navigationBarHeight).plainClickable(onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, style = AppFont.body, maxLines = 1)
    }
}

/** The close button at the trailing end of a `SheetBar`. */
@Composable
internal fun SheetBarCloseButton(onClose: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(end = 8.dp)
            .size(navigationBarHeight)
            .plainClickable(onClick = onClose)
            .semantics { contentDescription = "Close" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(scaledSymbolSize(AppFont.body)))
    }
}
