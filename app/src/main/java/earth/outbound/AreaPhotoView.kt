//
//  AreaPhotoView.kt
//  outbound
//

package earth.outbound

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import earth.outbound.networking.PlaceImageCache

/**
 * An area's Unsplash photo filling its frame, with the "Photo by … on Unsplash"
 * credit Unsplash requires as a chip in the top-leading corner. Tapping the chip asks
 * before opening the photographer's profile in the browser, when the server gives one
 * (the chip sits on a tappable card, so a stray tap shouldn't leave the app).
 *
 * Shows `placeholder` while the image loads, fades the photo in over it once it
 * arrives, and keeps showing the placeholder if loading fails.
 *
 * Images come through `PlaceImageCache.areaPhotos`, so one already loaded this
 * session shows straight away with no placeholder or fade, and one loaded before
 * still shows offline.
 */
@Composable
fun AreaPhotoView(
    photo: AreaPhoto,
    /**
     * Whether to show the credit chip. Off where the photo is only a dimmed backdrop
     * and the chip would sit under other UI.
     */
    showsAttribution: Boolean = true,
    modifier: Modifier = Modifier,
    placeholder: @Composable () -> Unit,
) {
    var image by remember { mutableStateOf(PlaceImageCache.areaPhotos.cachedImage(photo.url)) }
    val opacity = remember { Animatable(if (image == null) 0f else 1f) }

    LaunchedEffect(photo.url) {
        val cached = PlaceImageCache.areaPhotos.cachedImage(photo.url)
        if (cached != null) {
            image = cached
            opacity.snapTo(1f)
            return@LaunchedEffect
        }
        image = null
        opacity.snapTo(0f)
        image = PlaceImageCache.areaPhotos.image(photo.url)
        opacity.animateTo(1f, tween(durationMillis = 250, easing = EaseOut))
    }

    Box(modifier.fillMaxSize()) {
        placeholder()
        image?.let { loaded ->
            // Fills whatever frame the caller gives, so the credit sits in the
            // visible corner rather than off the edge of the scaled-to-fill image.
            BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer { alpha = opacity.value }.clipToBounds()) {
                Image(
                    bitmap = loaded,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // Caps the chip at 75% of the photo's width; a shorter credit
                // keeps its own size.
                if (showsAttribution) {
                    Attribution(photo, Modifier.padding(8.dp).widthIn(max = maxWidth * 0.75f))
                }
            }
        }
    }
}

@Composable
private fun Attribution(photo: AreaPhoto, modifier: Modifier = Modifier) {
    val name = photo.attribution ?: return
    val profile = photo.attributionUrl
    if (profile == null) {
        Chip(name, modifier)
        return
    }

    val uriHandler = LocalUriHandler.current
    var confirmingProfile by remember { mutableStateOf(false) }

    // Its own tap, which wins over the card's tap and long press underneath.
    Chip(
        name,
        modifier
            .clip(CircleShape)
            .tapGesture { confirmingProfile = true }
            .semantics {
                role = Role.Button
                onClick(label = "Opens the photographer's profile in your browser") {
                    confirmingProfile = true
                    true
                }
            },
    )
    AppAlert(
        title = "Open in browser?",
        isPresented = confirmingProfile,
        onDismiss = { confirmingProfile = false },
        message = "View $name's profile on Unsplash.",
        actions = listOf(
            DialogAction("Cancel", isCancel = true),
            DialogAction("Open") {
                // Nothing to open it with: stay put.
                runCatching { uriHandler.openUri(profile) }
            },
        ),
    )
}

/**
 * A capsule chip reading "Photo by Jane Doe on Unsplash" that grows with the text
 * up to the width it's given, then cuts the text off at the end.
 */
@Composable
private fun Chip(name: String, modifier: Modifier = Modifier) {
    Text(
        "Photo by $name on Unsplash",
        style = AppFont.system(8f, FontWeight.Medium),
        color = Color.White.copy(alpha = 0.9f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.3f), CircleShape)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}
