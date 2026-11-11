//
//  TripCover.kt
//  outbound
//

package earth.outbound

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.SubcomposeAsyncImage

/**
 * A trip's cover art filling its frame: shown on the trip's tile in the grid and,
 * dimmed, behind its itinerary, so opening a trip reads as the tile growing to fill
 * the screen.
 *
 * The trip's own cover image, else the first area's photo, else a styled
 * placeholder. An area photo fetched after the list loads fades in over the
 * placeholder.
 */
@Composable
fun TripCover(
    trip: Trip,
    /** Whether an area photo shows its Unsplash credit chip. */
    showsAttribution: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val placeholder: @Composable () -> Unit = { Placeholder(trip) }
    val coverImageUrl = trip.coverImageUrl?.takeIf { it.isNotEmpty() }
    val photo = trip.areaCoverPhoto

    if (coverImageUrl != null) {
        SubcomposeAsyncImage(
            model = coverImageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            loading = { placeholder() },
            error = { placeholder() },
            modifier = modifier.fillMaxSize(),
        )
    } else if (photo != null) {
        AreaPhotoView(photo = photo, showsAttribution = showsAttribution, modifier = modifier, placeholder = placeholder)
    } else {
        Box(modifier) { placeholder() }
    }
}

/**
 * A solid color shown when there's no cover art, picked from the trip's id so it
 * looks random across trips but stays the same for each one.
 */
@Composable
private fun Placeholder(trip: Trip) {
    Box(Modifier.fillMaxSize().background(placeholderColors[stableIndex(trip.id, placeholderColors.size)]))
}

/**
 * Deep, muted tones that keep white text legible and sit well on the terracotta
 * background.
 */
private val placeholderColors: List<Color> = listOf(
    Color(red = 0.18f, green = 0.36f, blue = 0.31f), // forest
    Color(red = 0.17f, green = 0.25f, blue = 0.38f), // navy
    Color(red = 0.36f, green = 0.23f, blue = 0.37f), // plum
    Color(red = 0.12f, green = 0.44f, blue = 0.47f), // teal
    Color(red = 0.48f, green = 0.18f, blue = 0.23f), // burgundy
    Color(red = 0.36f, green = 0.42f, blue = 0.18f), // olive
    Color(red = 0.24f, green = 0.23f, blue = 0.48f), // indigo
    Color(red = 0.29f, green = 0.33f, blue = 0.38f), // slate
    Color(red = 0.54f, green = 0.35f, blue = 0.12f), // ochre
)

/**
 * A deterministic index for `id`. `hashCode` would do on the JVM, but this uses
 * FNV-1a so each trip gets the same color here as in the iOS app.
 */
private fun stableIndex(id: String, count: Int): Int {
    var hash = 0xcbf29ce484222325uL
    for (byte in id.toByteArray(Charsets.UTF_8)) {
        hash = hash xor byte.toUByte().toULong()
        hash *= 0x100000001b3uL
    }
    return (hash % count.toULong()).toInt()
}

/**
 * The first area's default photo, for the cover. Only the first area's is used,
 * even when a later one has a photo, so the cover always matches the leading area.
 */
val Trip.areaCoverPhoto: AreaPhoto?
    get() = areas?.firstOrNull()?.coverPhoto
