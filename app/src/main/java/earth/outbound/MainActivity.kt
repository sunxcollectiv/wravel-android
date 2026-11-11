//
//  MainActivity.kt
//  outbound
//

package earth.outbound

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import earth.outbound.auth.AuthStore
import earth.outbound.auth.LocalAuthStore

/** The app's one window: every screen, sheet and popup is drawn inside it. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The app draws behind the system bars, which keep light icons over its dark screens.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // Not again when the system recreates the activity with the intent it started with.
        if (savedInstanceState == null) PushNotifications.shared.didOpen(intent)

        setContent {
            val auth = remember { AuthStore() }
            CompositionLocalProvider(LocalAuthStore provides auth) {
                OverlayHost {
                    AuthView()
                }
                // Over everything, sheets and popups included.
                OfflinePillOverlay()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        PushNotifications.shared.didOpen(intent)
    }
}
