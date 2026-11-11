//
//  AuthView.kt
//  outbound
//

package earth.outbound

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import earth.outbound.auth.LocalAuthStore
import earth.outbound.auth.SignInView

/** Root of the app: shows the main screen when signed in, otherwise sign in / sign up. */
@Composable
fun AuthView(modifier: Modifier = Modifier) {
    val auth = LocalAuthStore.current

    AppTheme(modifier) {
        AnimatedContent(
            targetState = auth.isSignedIn,
            transitionSpec = { fadeIn(AppAnimation.default()) togetherWith fadeOut(AppAnimation.default()) },
            label = "auth",
        ) { isSignedIn ->
            if (isSignedIn) {
                HomeView()
            } else {
                SignInView()
            }
        }
    }

    LaunchedEffect(Unit) {
        auth.refreshUser()
    }
}
