//
//  OutboundApp.kt
//  outbound
//

package earth.outbound

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import earth.outbound.networking.Connectivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The app's process: what has to be in place before any screen or push is handled. */
class OutboundApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Connectivity.shared.startMonitoring(this)
        // Before the first push can arrive.
        PushNotifications.registerCategories(this)
    }

    companion object {
        private lateinit var instance: OutboundApp

        /** The application context, for the stores and caches that outlive any screen. */
        val context: Context get() = instance

        /** Small values kept between launches, like iOS's `UserDefaults.standard`. */
        val defaults: SharedPreferences by lazy {
            instance.getSharedPreferences("defaults", Context.MODE_PRIVATE)
        }

        /** For work that should finish whatever screen started it, e.g. signing out. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
