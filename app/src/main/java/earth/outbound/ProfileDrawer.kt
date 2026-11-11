//
//  ProfileDrawer.kt
//  outbound
//

package earth.outbound

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import earth.outbound.auth.LocalAuthStore
import earth.outbound.auth.User

/**
 * The account drawer on the trips screen: who's signed in, their credits, and
 * signing out.
 */
@Composable
fun ProfileDrawer(
    /** Called before signing out, so the drawer can close first. */
    onSignOut: () -> Unit = {},
    /** Opens the credits page; the drawer closes as it does. */
    onOpenCredits: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val auth = LocalAuthStore.current
    val credits = LocalCreditStore.current

    var confirmingSignOut by remember { mutableStateOf(false) }

    ForegroundStyle(Color.appForeground) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(Color.drawerBackground)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(24.dp),
        ) {
            auth.currentUser?.let { user ->
                AvatarView(user = user, size = 64.dp, modifier = Modifier.padding(bottom = 16.dp))
                Text(user.displayName, style = AppFont.title3.bold)
                Text(
                    user.email,
                    style = AppFont.subheadline,
                    color = Color.appForeground.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 32.dp),
                )
            }

            Row(
                modifier = Modifier
                    .padding(bottom = 24.dp)
                    .fillMaxWidth()
                    .plainClickable(onClick = onOpenCredits),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DrawerLabel("Credits", Icons.Rounded.CreditCard)
                Spacer(Modifier.weight(1f))
                val balance = credits.balanceCents
                if (balance != null) {
                    Text(
                        formatCredits(cents = balance),
                        style = AppFont.subheadline.monospacedDigit,
                        color = Color.appForeground.copy(alpha = 0.7f),
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = Color.appForeground.copy(alpha = 0.5f),
                    modifier = Modifier.size(scaledSymbolSize(AppFont.footnote, scale = 1.4f)),
                )
            }

            DrawerLabel(
                "Sign Out",
                Icons.AutoMirrored.Rounded.Logout,
                modifier = Modifier.plainClickable { confirmingSignOut = true },
            )
        }
    }

    // Asked whatever the connection: signing out clears the trips saved for
    // offline, and they can't be brought back without signing in again.
    ConfirmationDialog(
        title = "Sign out?",
        isPresented = confirmingSignOut,
        onDismiss = { confirmingSignOut = false },
        message = "Your trips saved for offline viewing will be removed from this device. You will not have access to your itineraries or receive trip update notifications until you sign in again",
        actions = listOf(
            DialogAction("Confirm sign out", isDestructive = true) {
                onSignOut()
                auth.signOut()
            }
        ),
    )
}

/** A drawer row's icon and title, in the body font's semibold. */
@Composable
private fun DrawerLabel(title: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(scaledSymbolSize(AppFont.body, scale = 1.2f)))
        Text(title, style = AppFont.body.semibold)
    }
}

/**
 * A circular profile picture: the user's avatar image, or their initials when
 * there's no image (or while it loads).
 */
@Composable
fun AvatarView(user: User, size: Dp = 32.dp, modifier: Modifier = Modifier) {
    val initials: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().background(Color.appBackground), contentAlignment = Alignment.Center) {
            Text(
                avatarInitials(user.displayName),
                // Sized to the circle, whatever the system's font scale.
                style = TextStyle(
                    fontSize = with(LocalDensity.current) { (size * 0.4f).toSp() },
                    fontWeight = FontWeight.SemiBold,
                ),
                color = Color.appForeground,
                maxLines = 1,
            )
        }
    }

    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .border(1.dp, Color.appForeground.copy(alpha = 0.25f), CircleShape)
    ) {
        val url = user.avatarUrl
        if (url != null) {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                loading = { initials() },
                error = { initials() },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            initials()
        }
    }
}

/**
 * The first letters of the first and last words of a name, e.g. "Sun Chen" → "SC".
 * A top-level function, as `AvatarView` is a composable function and can't hold it.
 */
fun avatarInitials(name: String): String {
    val words = name.split(" ").filter { it.isNotEmpty() }
    val letters = listOfNotNull(words.firstOrNull(), words.lastOrNull().takeIf { words.size > 1 })
        .joinToString("") { it.substring(0, it.offsetByCodePoints(0, 1)) }
    return if (letters.isEmpty()) "?" else letters.uppercase()
}
