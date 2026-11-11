//
//  TripCard.kt
//  outbound
//

package earth.outbound

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/**
 * A tall, "story"-style tile summarizing a trip in the grid. A featured card (the next
 * trip) is a full-width square with larger text instead.
 */
@Composable
fun TripCard(trip: Trip, isFeatured: Boolean = false, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .aspectRatio(if (isFeatured) 1f else 3f / 4f)
            .shadow(8.dp, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape),
        contentAlignment = Alignment.BottomStart,
    ) {
        TripCover(trip = trip)
        Scrim()
        Info(trip, isFeatured)
    }
}

/**
 * A bottom-up gradient so the text stays legible over any image. It starts 20%
 * down the card and darkens steadily to nearly opaque at the bottom. Purely visual:
 * taps go through to the photo credit chip underneath.
 */
@Composable
private fun Scrim() {
    Box(
        Modifier.fillMaxSize().drawBehind {
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.55f to Color.Black.copy(alpha = 0.45f),
                    1f to Color.Black.copy(alpha = 0.85f),
                    startY = size.height * 0.2f,
                    endY = size.height,
                )
            )
        }
    )
}

@Composable
private fun Info(trip: Trip, isFeatured: Boolean) {
    ForegroundStyle(Color.appForeground) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(if (isFeatured) 20.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (isFeatured) 6.dp else 4.dp),
        ) {
            Text(
                trip.title,
                style = if (isFeatured) AppFont.title.bold else AppFont.headline,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            AreaTags(trip, isFeatured)
            Text(
                trip.dateRangeText,
                style = if (isFeatured) AppFont.subheadline else AppFont.caption,
                color = Color.appForeground.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The trip's areas as small tags. Shows every tag when they fit on one line,
 * otherwise the first one plus a "+N" count.
 */
@Composable
private fun AreaTags(trip: Trip, isFeatured: Boolean) {
    val areas = trip.areas ?: emptyList()
    val first = areas.firstOrNull() ?: return
    Layout(
        content = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (area in areas) {
                    AreaTag(area.name, isFeatured)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AreaTag(first.name, isFeatured, Modifier.weight(1f, fill = false))
                if (areas.size > 1) AreaTag("+${areas.size - 1}", isFeatured)
            }
        },
    ) { measurables, constraints ->
        val (all, shortened) = measurables
        val fits = all.maxIntrinsicWidth(Constraints.Infinity) <= constraints.maxWidth
        val placeable = (if (fits) all else shortened).measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}

@Composable
private fun AreaTag(text: String, isFeatured: Boolean, modifier: Modifier = Modifier) {
    Text(
        text,
        style = (if (isFeatured) AppFont.footnote else AppFont.caption2).semibold,
        color = Color.Black.copy(alpha = 0.85f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(Color.White, RoundedCornerShape(4.dp))
            .padding(horizontal = if (isFeatured) 8.dp else 6.dp, vertical = 2.dp),
    )
}

@Preview
@Composable
private fun TripCardPreview() {
    AppTheme {
        TripCard(trip = Trip.sample, modifier = Modifier.padding(16.dp).width(180.dp))
    }
}
