//
//  Connectivity.kt
//  outbound
//

package earth.outbound.networking

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Whether the app can talk to the server, so screens can say they're offline, hold
 * off saving, and refresh once the connection comes back.
 *
 * A network path being up doesn't mean the server answers (a weak signal can still
 * time out, or the server may be down), so requests that can't reach it count as
 * offline too, until the server answers again.
 */
class Connectivity private constructor() {
    /** Whether the device has a network path at all. */
    var isOnline by mutableStateOf(true)
        private set

    /** Whether the last request got an answer from the server. */
    var canReachServer by mutableStateOf(true)
        private set

    /** Whether requests can be expected to go through. */
    val isConnected: Boolean get() = isOnline && canReachServer

    // Requests finish on background threads and the system reports the network on its
    // own, while the screens read the state above as they draw. So everything that
    // changes it runs here, on the main thread, in the order it was reported.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var probeTask: Job? = null
    private var isMonitoring = false

    /**
     * What requests last reported about the server, kept apart from `canReachServer`
     * so that the usual case, one more answer from a server already known to be
     * there, is settled on the request's own thread without troubling the main one.
     */
    private var reportedReachable = true
    private val reportLock = Any()

    /** Starts watching the device's network path. Called once, as the app starts. */
    fun startMonitoring(context: Context) {
        if (isMonitoring) return
        isMonitoring = true
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        // No callback comes while there's no network at all, so start from what there is now.
        isOnline = manager.getNetworkCapabilities(manager.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch { setOnline(true) }
            }

            override fun onLost(network: Network) {
                scope.launch { setOnline(false) }
            }
        })
    }

    /** The server answered a request, whatever the status. Safe to call from any thread. */
    fun serverResponded() = report(reachable = true)

    /**
     * A request couldn't reach the server. Checks back every few seconds, since
     * screens that can't save won't send anything that would notice it's back.
     * Safe to call from any thread.
     */
    fun serverUnreachable() = report(reachable = false)

    /**
     * Passes a change in what requests say on to the main thread. Held under the lock
     * until it's queued, so two requests finishing at once on different threads are
     * applied in the order they got here.
     */
    private fun report(reachable: Boolean) {
        synchronized(reportLock) {
            if (reportedReachable == reachable) return
            reportedReachable = reachable
            scope.launch { if (reachable) setReachable() else setUnreachable() }
        }
    }

    private fun setReachable() {
        canReachServer = true
        probeTask?.cancel()
        probeTask = null
    }

    private fun setUnreachable() {
        canReachServer = false
        if (probeTask != null) return
        probeTask = scope.launch {
            while (isActive) {
                delay(10_000)
                if (isOnline && serverAnswers()) serverResponded()
            }
        }
    }

    private suspend fun setOnline(isOnline: Boolean) {
        this.isOnline = isOnline
        // A new path (e.g. Wi-Fi back) is worth checking right away.
        if (isOnline && !canReachServer && serverAnswers()) serverResponded()
    }

    companion object {
        val shared = Connectivity()

        /** Statuses a proxy or load balancer sends when the server behind it is down. */
        val gatewayStatuses = 502..504

        /** The app's session with a short wait, for checking whether the server is back. */
        private val probeSession by lazy {
            APIClient.sharedSession.newBuilder().callTimeout(5, TimeUnit.SECONDS).build()
        }

        /**
         * Whether the server itself answers; even a 404 means it's there, but not a
         * gateway saying it's down.
         */
        private suspend fun serverAnswers(): Boolean {
            val request = Request.Builder().url(APIConfig.baseURL).head().build()
            return try {
                probeSession.newCall(request).await().use { it.code !in gatewayStatuses }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
        }
    }
}
