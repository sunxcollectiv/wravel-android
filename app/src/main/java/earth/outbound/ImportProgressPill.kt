//
//  ImportProgressPill.kt
//  outbound
//

package earth.outbound

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/** The steps of a screenshot import, as `ImportProgressPill` shows them. */
enum class ImportProgressPillStep {
    Uploading, Extracting, Added, Failed;

    val title: String
        get() = when (this) {
            Uploading -> "Uploading image"
            Extracting -> "Extracting details"
            Added -> "Added to itinerary"
            Failed -> "Image process failed"
        }

    /** How much of the pill is filled by the end of this step. */
    val progress: Double
        get() = when (this) {
            Uploading -> 1.0 / 3
            Extracting -> 2.0 / 3
            Added, Failed -> 1.0
        }

    /**
     * How much is filled as this step starts: where the step before it ends. A
     * failure fills straight from wherever the fill had got to.
     */
    val startProgress: Double
        get() = when (this) {
            Uploading, Failed -> 0.0
            Extracting -> Uploading.progress
            Added -> Extracting.progress
        }
}

object ImportProgressPillMetrics {
    /** How long each step takes to fill to its share, in seconds. */
    const val stepDuration: Double = 3.0

    /** How long a lagging fill takes to catch up to the start of the next step. */
    const val catchUpDuration: Double = 0.5

    /** How long a failure takes to fill the pill and turn it red. */
    const val failDuration: Double = 1.0

    /** The fill's green: deep enough that white text stays readable on it. */
    val fill = Color(red = 0.24f, green = 0.55f, blue = 0.36f)

    /** The failed fill: a red with a touch of pink, deep enough for white text too. */
    val failedFill = Color(red = 0.84f, green = 0.27f, blue = 0.33f)
}

/**
 * A pill above the day picker tracking a booking screenshot as the server turns it
 * into itinerary items: one line saying which step it's on, over a green fill that
 * grows from the left until the pill is fully green. It doesn't block the itinerary;
 * you can keep scrolling and editing while it works.
 *
 * Each step fills on to its share of the pill over a few seconds, so it keeps moving
 * while the server works, and the check lands once the last one fills. A step that
 * arrives before the fill has finished the one before first catches the fill up to
 * where the new step starts, quickly, then fills from there.
 *
 * If the import fails at any point, the fill carries on to the full width while
 * gradually turning red, and the circle shows an x.
 */
@Composable
fun ImportProgressPill(step: ImportProgressPillStep, modifier: Modifier = Modifier) {
    /** How much of the pill is filled right now, 0 to 1. */
    val shownProgress = remember { Animatable(0f) }
    /** How far the fill has turned from green to red, 0 to 1, once the import fails. */
    val failedTint = remember { Animatable(0f) }
    /** Set once the last step's fill has finished, to show the check (or the x). */
    var isComplete by remember { mutableStateOf(false) }

    val title = step.title

    // Fills on to `step`'s share over `stepDuration`, first catching up to where the
    // step starts over `catchUpDuration` if the fill is still short of it. A new step
    // cancels the run before it, which carries on from wherever the fill had got to.
    LaunchedEffect(step) {
        if (step == ImportProgressPillStep.Failed) {
            // On to full from wherever the fill is, turning red as it goes: nothing
            // more is coming.
            isComplete = false
            val animation = tween<Float>(millis(ImportProgressPillMetrics.failDuration), easing = EaseInOut)
            coroutineScope {
                launch { failedTint.animateTo(1f, animation) }
                shownProgress.animateTo(1f, animation)
            }
            isComplete = true
        } else {
            val start = step.startProgress.toFloat()
            if (shownProgress.value < start - 0.001f) {
                // Rush to the step's start.
                shownProgress.animateTo(start, tween(millis(ImportProgressPillMetrics.catchUpDuration), easing = EaseOut))
            }
            shownProgress.animateTo(
                step.progress.toFloat(),
                tween(millis(ImportProgressPillMetrics.stepDuration), easing = LinearEasing),
            )
            isComplete = step == ImportProgressPillStep.Added
        }
    }

    Box(
        modifier
            .shadow(14.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(CircleShape)
            // Near-solid dark gray like the trip menu, so cards scrolling behind don't show through.
            .background(Color.white(0.15f).copy(alpha = 0.97f))
            .drawBehind {
                // Red blended over the green, so a failure changes the color in
                // place rather than painting a new bar in from the left.
                val fillSize = Size(size.width * shownProgress.value.coerceIn(0f, 1f), size.height)
                drawRect(ImportProgressPillMetrics.fill, size = fillSize)
                drawRect(ImportProgressPillMetrics.failedFill, size = fillSize, alpha = failedTint.value)
            }
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
            .clearAndSetSemantics {
                contentDescription = title
                stateDescription = "${(step.progress * 100).roundToInt()}%"
            },
        contentAlignment = Alignment.Center,
    ) {
        // Only the text crossfades; the fill tracks progress as it comes.
        Crossfade(
            targetState = title,
            animationSpec = AppAnimation.snappy(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 7.dp)
                // Room on both sides for the status circle, so the text stays centered.
                .padding(horizontal = 28.dp),
            label = "title",
        ) { title ->
            Title(title)
        }
        StatusCircle(
            step = step,
            isComplete = isComplete,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 7.dp),
        )
    }
}

private fun millis(seconds: Double): Int = (seconds * 1000).roundToInt()

/** The step's name on one line, shrinking a little rather than truncating when the pill is narrow. */
@Composable
private fun Title(title: String) {
    ShrinkingText(
        title,
        // Caption 2 is 11.
        style = AppFont.caption2.semibold,
        minimumScaleFactor = 0.85f,
        color = Color.White,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * An empty ring while the import runs, then filled white with a check in the
 * fill's color once done, or an x once failed.
 */
@Composable
private fun StatusCircle(step: ImportProgressPillStep, isComplete: Boolean, modifier: Modifier = Modifier) {
    val isFailed = step == ImportProgressPillStep.Failed
    // A spring that settles in about 0.35s with a little bounce.
    val shown by animateFloatAsState(
        targetValue = if (isComplete) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 322f),
        label = "complete",
    )
    Box(modifier.size(15.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().border(1.5.dp, Color.White.copy(alpha = 0.6f), CircleShape))
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val scale = 0.2f + 0.8f * shown
                    scaleX = scale
                    scaleY = scale
                    alpha = shown.coerceIn(0f, 1f)
                }
                .background(Color.White, CircleShape)
        )
        Icon(
            if (isFailed) Icons.Rounded.Close else Icons.Rounded.Check,
            contentDescription = null,
            tint = if (isFailed) ImportProgressPillMetrics.failedFill else ImportProgressPillMetrics.fill,
            modifier = Modifier
                .size(11.dp)
                .graphicsLayer {
                    val scale = 0.5f + 0.5f * shown
                    scaleX = scale
                    scaleY = scale
                    alpha = shown.coerceIn(0f, 1f)
                },
        )
    }
}

@Preview
@Composable
private fun ImportProgressPillPreview() {
    AppTheme {
        Column(
            modifier = Modifier.fillMaxSize().background(Color.white(0.1f)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            for (step in ImportProgressPillStep.entries) {
                ImportProgressPill(step = step)
            }
        }
    }
}
