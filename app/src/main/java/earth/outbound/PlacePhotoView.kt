//
//  PlacePhotoView.kt
//  outbound
//

package earth.outbound

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import earth.outbound.networking.PlaceImageCache

/**
 * A place's cover photo filling its frame, with the photographer credit Google
 * requires in the bottom-trailing corner (each name linked to its profile when the
 * server gives one). Shows `placeholder` while the image loads, fades the photo in
 * over it once it arrives, and keeps showing the placeholder if loading fails: a
 * cover `url` can 403 once its reference expires.
 *
 * Images come through `PlaceImageCache`, so one already loaded this session shows
 * straight away with no placeholder or fade.
 */
@Composable
fun PlacePhotoView(
    photo: CoverPhoto,
    modifier: Modifier = Modifier,
    placeholder: @Composable () -> Unit,
) {
    var image by remember { mutableStateOf(PlaceImageCache.shared.cachedImage(photo.url)) }
    val opacity = remember { Animatable(if (image == null) 0f else 1f) }

    LaunchedEffect(photo.url) {
        val cached = PlaceImageCache.shared.cachedImage(photo.url)
        if (cached != null) {
            image = cached
            opacity.snapTo(1f)
            return@LaunchedEffect
        }
        image = null
        opacity.snapTo(0f)
        image = PlaceImageCache.shared.image(photo.url)
        opacity.animateTo(1f, tween(durationMillis = 250, easing = EaseOut))
    }

    // The placeholder stays underneath and the photo fades in over it, rather
    // than cutting from one to the other the moment it arrives.
    Box(modifier.fillMaxSize()) {
        placeholder()
        image?.let { loaded ->
            // Fills whatever frame the caller gives, so the credit sits in the
            // visible corner rather than off the edge of the scaled-to-fill image.
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = opacity.value }.clipToBounds()) {
                Image(
                    bitmap = loaded,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Attribution(photo, Modifier.align(Alignment.BottomEnd))
            }
        }
    }
}

@Composable
private fun Attribution(photo: CoverPhoto, modifier: Modifier = Modifier) {
    val credits = credits(photo)
    if (credits.isEmpty()) return
    val shadowRadius = with(LocalDensity.current) { 2.dp.toPx() }
    Text(
        credit(credits),
        style = AppFont.system(9f, FontWeight.Medium).copy(
            shadow = Shadow(color = Color.Black.copy(alpha = 0.6f), offset = Offset.Zero, blurRadius = shadowRadius),
        ),
        color = Color.White.copy(alpha = 0.85f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

/** A credit that has a name to show. */
private data class Credit(val name: String, val uri: String?)

/** The credits that have a name to show. */
private fun credits(photo: CoverPhoto): List<Credit> =
    photo.attributions.mapNotNull { author ->
        author.displayName?.let { Credit(name = it, uri = author.uri) }
    }

/**
 * "Photo: Jane D, Sam K", with each name linked to its `uri` when present. A linked
 * name keeps the color of the rest, and opens in the browser when tapped.
 */
private fun credit(credits: List<Credit>): AnnotatedString = buildAnnotatedString {
    append("Photo: ")
    credits.forEachIndexed { index, author ->
        if (index > 0) append(", ")
        if (author.uri != null) {
            withLink(LinkAnnotation.Url(author.uri)) { append(author.name) }
        } else {
            append(author.name)
        }
    }
}
