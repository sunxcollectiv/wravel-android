//
//  AddLodgingPopup.kt
//  outbound
//

package earth.outbound

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KingBed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * What the popup refers to beyond itself. It lives here rather than on
 * `AddLodgingPopup`, which is a composable function and so can't hold it.
 */
object AddLodgingPopupMetrics {
    /** The inbox that turns forwarded booking emails into trip items. */
    const val tripsEmail = "trips@outbound.earth"
}

/**
 * A centered card offering the ways to add a stay: forward its confirmation email to
 * the trips inbox, upload an image of it, or fill in the form by hand. Shown over the
 * itinerary when a day's "add lodging" strip is tapped.
 */
@Composable
fun AddLodgingPopup(
    onUploadImage: () -> Unit,
    onAddManually: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PopupCard(onClose = onClose, modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PopupHeaderLabel("Add Lodging", Icons.Rounded.KingBed)

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Forward your booking confirmation email to",
                    style = AppFont.subheadline,
                    color = Color.appForeground.copy(alpha = 0.75f),
                )
                EmailChip()
            }

            // Both buttons as tall as the taller one.
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PopupOptionButton(
                    title = "Upload Image",
                    icon = Icons.Rounded.Image,
                    foreground = Color.appForeground,
                    background = Color.appForeground.copy(alpha = 0.12f),
                    onClick = onUploadImage,
                    // Uploading saves straight away, so it waits for a connection.
                    modifier = Modifier.weight(1f).fillMaxHeight().requiresConnection(),
                )
                PopupOptionButton(
                    title = "Add Manually",
                    icon = Icons.Rounded.EditNote,
                    foreground = Color.white(0.15f),
                    background = Color.appForeground,
                    onClick = onAddManually,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

/** The trips address, tapped to copy so it can be pasted into a forwarded email. */
@Composable
private fun EmailChip() {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // First, so a press fades the outline along with the address.
            .plainClickable {
                clipboard.setText(AnnotatedString(AddLodgingPopupMetrics.tripsEmail))
                copied = true
                scope.launch {
                    delay(1.5.seconds)
                    copied = false
                }
            }
            .dashedOutline(Color.appForeground.copy(alpha = 0.3f), cornerRadius = 10.dp)
            .clearAndSetSemantics {
                contentDescription = "Copy ${AddLodgingPopupMetrics.tripsEmail}"
                role = Role.Button
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            AddLodgingPopupMetrics.tripsEmail,
            style = AppFont.body.semibold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Crossfade(copied, animationSpec = AppAnimation.snappy(0.2), label = "copied") { isCopied ->
            SymbolLabel(
                if (isCopied) "Copied" else "Copy",
                icon = if (isCopied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                style = AppFont.caption.semibold,
                color = Color.appForeground.copy(alpha = 0.6f),
            )
        }
    }
}

@Preview
@Composable
private fun AddLodgingPopupPreview() {
    AppTheme {
        Box(Modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            AddLodgingPopup(onUploadImage = {}, onAddManually = {}, onClose = {})
        }
    }
}
