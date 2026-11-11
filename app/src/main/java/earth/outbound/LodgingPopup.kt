//
//  LodgingPopup.kt
//  outbound
//

package earth.outbound

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KingBed
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.net.URLEncoder

/**
 * A centered card with what you need to get to a stay: its name and address, room
 * for the address in the local language, and links to it and directions in Google
 * Maps. Shown over the itinerary when its lodging bar is tapped.
 */
@Composable
fun LodgingPopup(
    name: String,
    /** Names the header ("Hotel") in place of "Lodging" when known. */
    propertyType: LodgingType? = null,
    address: String?,
    googlePlaceId: String?,
    onEdit: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    /** Opens a link, in the Google Maps app when it's installed; nothing happens when nothing can. */
    fun openURL(url: String) {
        try {
            uriHandler.openUri(url)
        } catch (_: Exception) {
            // No app to open it with.
        }
    }

    PopupCard(onClose = onClose, modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                PopupHeaderLabel(propertyType?.label ?: "Lodging", propertyType?.systemImage ?: Icons.Rounded.KingBed)
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .plainClickable(onClick = onEdit)
                        .semantics { contentDescription = "Edit Lodging" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Edit,
                        contentDescription = null,
                        tint = Color.appForeground.copy(alpha = 0.6f),
                        modifier = Modifier.size(scaledSymbolSize(AppFont.caption)),
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(name, style = AppFont.title3.bold)
                if (address != null) {
                    SelectionContainer {
                        Text(address, style = AppFont.subheadline, color = Color.appForeground.copy(alpha = 0.75f))
                    }
                }
            }

            LocalAddressPlaceholder()

            // Both buttons as tall as the taller one.
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PopupOptionButton(
                    title = "View in Google Maps",
                    icon = Icons.Rounded.Map,
                    foreground = Color.appForeground,
                    background = Color.appForeground.copy(alpha = 0.12f),
                    onClick = { openURL(LodgingPopupMetrics.viewURL(name = name, address = address, googlePlaceId = googlePlaceId)) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                PopupOptionButton(
                    title = "Directions",
                    icon = Icons.Rounded.Directions,
                    foreground = Color.white(0.15f),
                    background = Color.appForeground,
                    onClick = {
                        openURL(LodgingPopupMetrics.directionsURL(name = name, address = address, googlePlaceId = googlePlaceId))
                    },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

/**
 * One of a popup's side-by-side buttons: icon over title, so a long title fits a
 * half-width button. Shared with the add-lodging popup, whose buttons match.
 */
@Composable
internal fun PopupOptionButton(
    title: String,
    icon: ImageVector,
    foreground: Color,
    background: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .plainClickable(onClick = onClick)
            .background(background, RoundedCornerShape(10.dp))
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(scaledSymbolSize(AppFont.body)))
        Text(title, style = AppFont.footnote.semibold, color = foreground, textAlign = TextAlign.Center)
    }
}

/** Where the address in the local language will go, for showing a driver. */
@Composable
private fun LocalAddressPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .dashedOutline(Color.appForeground.copy(alpha = 0.3f), cornerRadius = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("Address in local language", style = AppFont.caption, color = Color.appForeground.copy(alpha = 0.5f))
    }
}

/**
 * Google Maps links to a place, by its Google place id when there is one (so the exact
 * listing opens) with the name and address as the search text. They open the Google
 * Maps app when it's installed, the website otherwise.
 */
object LodgingPopupMetrics {
    /** The place's listing. */
    fun viewURL(name: String, address: String?, googlePlaceId: String?): String =
        mapsURL(path = "search/", place = "query", placeId = "query_place_id", name = name, address = address, googlePlaceId = googlePlaceId)

    /**
     * Directions to the place. With no origin given, Maps starts from your current
     * location.
     */
    fun directionsURL(name: String, address: String?, googlePlaceId: String?): String =
        mapsURL(path = "dir/", place = "destination", placeId = "destination_place_id", name = name, address = address, googlePlaceId = googlePlaceId)

    private fun mapsURL(path: String, place: String, placeId: String, name: String, address: String?, googlePlaceId: String?): String {
        val items = mutableListOf(
            "api" to "1",
            place to listOfNotNull(name, address).joinToString(", "),
        )
        if (googlePlaceId != null) {
            items.add(placeId to googlePlaceId)
        }
        val query = items.joinToString("&") { (key, value) -> "$key=${percentEncoded(value)}" }
        return "https://www.google.com/maps/$path?$query"
    }

    /** A query value with everything but plain characters escaped, spaces as "%20". */
    private fun percentEncoded(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

@Preview
@Composable
private fun LodgingPopupPreview() {
    AppTheme {
        Box(Modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            LodgingPopup(
                name = "W Melbourne",
                address = "408 Flinders Ln, Melbourne VIC 3000, Australia",
                googlePlaceId = null,
                onEdit = {},
                onClose = {},
            )
        }
    }
}
