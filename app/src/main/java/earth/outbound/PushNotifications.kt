//
//  PushNotifications.kt
//  outbound
//

package earth.outbound

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import earth.outbound.auth.AuthStore
import earth.outbound.networking.APIClient
import earth.outbound.networking.Endpoint
import earth.outbound.networking.HTTPMethod
import earth.outbound.networking.Keychain
import earth.outbound.networking.jsonBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * `PushDeviceCreate` / `PushDeviceRead` for `/notifications/devices`.
 *
 *     POST   /notifications/devices  PushDeviceCreate -> PushDeviceRead
 *     DELETE /notifications/devices/{id}              -> 204
 */
object PushDevicesAPI {
    @Serializable
    data class RegisterRequest(
        @EncodeDefault val platform: String = "android",
        /** The device's Firebase Cloud Messaging registration token. */
        val token: String,
        val appVersion: String?,
        val locale: String?,
        val timezone: String?,
    )

    @Serializable
    data class Device(val id: String)

    fun register(body: RegisterRequest) =
        Endpoint(path = "/notifications/devices", method = HTTPMethod.POST, body = jsonBody(body))

    fun unregister(id: String) =
        Endpoint(path = "/notifications/devices/$id", method = HTTPMethod.DELETE)
}

/**
 * Asks for permission to notify, registers this device's FCM token with the
 * server, and routes pushes the user taps to the screen they're about.
 *
 * A push is an FCM data message carrying what to show and the inbox entry it mirrors:
 *
 *     { "title": "…", "body": "…",
 *       "notification_id": "…", "trip_id": "…", "trip_item_id": "…" }
 *
 * All three ids are optional. A push about a trip also carries `trip_title`, and
 * `"category": "TRIP_ACTIVITY"` to get the expanded card and the Mark as Read /
 * Dismiss actions. `PushMessagingService` posts it; a push sent with a `notification`
 * payload instead is posted by the system while the app is in the background, and
 * opens the app with the same ids.
 */
class PushNotifications(private val api: APIClient = APIClient.shared) {
    /** The trip a tapped push is about, waiting for the trips screen to open it. */
    var pendingTripId by mutableStateOf<String?>(null)

    /** Bumped whenever a push arrives, so the inbox and badge can refresh. */
    var receivedCount by mutableIntStateOf(0)
        private set

    private val deviceIdKey = "push.deviceId"
    private val permissionRequestedKey = "push.permissionRequested"

    /**
     * Asks for permission the first time (Android 13 and later, through
     * `requestPermission`: see `rememberNotificationPermissionRequest`), then registers
     * with FCM. Call on every launch once signed in: the token can change, and the
     * server wants to hear from each device regularly. Does nothing if the user said
     * no, or in a build with no Firebase project set up.
     */
    suspend fun registerIfAllowed(requestPermission: suspend () -> Boolean) {
        val context = OutboundApp.context
        val notifications = NotificationManagerCompat.from(context)
        val neverAsked = !OutboundApp.defaults.getBoolean(permissionRequestedKey, false)
        if (!notifications.areNotificationsEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && neverAsked) {
            OutboundApp.defaults.edit().putBoolean(permissionRequestedKey, true).apply()
            requestPermission()
        }
        if (!notifications.areNotificationsEnabled() || !isFirebaseConfigured(context)) return
        val token = try {
            FirebaseMessaging.getInstance().token.await()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Usually no Google Play services, or no network. The next launch tries again.
            Log.w(logTag, "Push registration failed: ${error.message}")
            return
        }
        didRegister(token)
    }

    /**
     * Sends a token from FCM to the server and remembers the device id it gets,
     * for `unregister`. Best-effort: the next launch tries again.
     */
    suspend fun didRegister(token: String) {
        if (api.token == null) return
        val body = PushDevicesAPI.RegisterRequest(
            token = token,
            appVersion = BuildConfig.VERSION_NAME,
            locale = Locale.getDefault().toString(),
            timezone = ZoneId.systemDefault().id,
        )
        try {
            val device = api.send<PushDevicesAPI.Device>(PushDevicesAPI.register(body))
            OutboundApp.defaults.edit().putString(deviceIdKey, device.id).apply()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        }
    }

    /**
     * Stops pushes to this device for the signed-in account, e.g. on sign-out.
     * `client` must still carry that account's token.
     */
    suspend fun unregister(client: APIClient) {
        val id = OutboundApp.defaults.getString(deviceIdKey, null) ?: return
        OutboundApp.defaults.edit().remove(deviceIdKey).apply()
        // The token is given up too; the next sign-in registers a fresh one.
        if (isFirebaseConfigured(OutboundApp.context)) {
            FirebaseMessaging.getInstance().deleteToken()
        }
        try {
            client.sendIgnoringResponse(PushDevicesAPI.unregister(id))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        }
        // No badge to clear: Android's launcher dot follows the notifications still showing.
    }

    /** A push arrived while the app was open. */
    fun didReceive() {
        receivedCount += 1
    }

    /**
     * The user picked Mark as Read or Dismiss on an expanded push. Runs without
     * opening the app, which may have been started in the background just for this,
     * so it waits for the request before returning.
     */
    suspend fun didChoose(action: String, data: Map<String, String>) {
        Log.i(logTag, "Push action $action")
        val id = data["notification_id"] ?: return
        // A background start may not have restored the session yet.
        if (api.token == null) {
            api.token = Keychain.string(AuthStore.tokenKey)
        }
        try {
            when (action) {
                Action.markRead -> api.send<AppNotification>(NotificationsAPI.markRead(id))
                Action.dismiss -> api.sendIgnoringResponse(NotificationsAPI.dismiss(id))
                else -> return
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(logTag, "Push action $action failed: $error")
            return
        }
        // No badge count to update here: Android has none to set.
        receivedCount += 1
    }

    /**
     * The user tapped a push, which opened `MainActivity` with `intent`: mark its inbox
     * entry read and open its trip. Does nothing for any other intent, or for one
     * already handled (the activity gets the same intent again when it's recreated).
     */
    fun didOpen(intent: Intent?) {
        if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val id = intent.getStringExtra("notification_id")
        val tripId = intent.getStringExtra("trip_id")
        if (id == null && tripId == null) return
        intent.removeExtra("notification_id")
        intent.removeExtra("trip_id")

        receivedCount += 1
        if (id != null) {
            OutboundApp.scope.launch {
                try {
                    api.sendIgnoringResponse(NotificationsAPI.markRead(id))
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                }
            }
        }
        if (tripId != null) {
            pendingTripId = tripId
        }
    }

    companion object {
        val shared: PushNotifications by lazy { PushNotifications() }

        private const val logTag = "push"

        /** The category trip pushes are sent with. */
        const val tripActivityCategory = "TRIP_ACTIVITY"

        /** The one channel pushes are posted on; the manifest names it as FCM's default too. */
        const val channelId = "outbound"

        internal object Action {
            const val markRead = "MARK_READ"
            const val dismiss = "DISMISS"
        }

        /** The extra telling `PushActionReceiver` which posted notification an action is for. */
        internal const val postedIdKey = "posted_id"

        /**
         * Creates the channel pushes are posted on (a no-op once it exists). Register
         * before the first push can arrive, i.e. at launch. The actions under an expanded
         * trip push need no registering here: each notification carries its own.
         */
        fun registerCategories(context: Context) {
            val channel = NotificationChannelCompat.Builder(channelId, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName("Notifications")
                .setDescription("Updates to your trips, and notices from Outbound.")
                .build()
            NotificationManagerCompat.from(context).createNotificationChannel(channel)
        }

        /** Whether the build has a Firebase project (`google-services.json`); without one there's no push. */
        private fun isFirebaseConfigured(context: Context): Boolean = FirebaseApp.getApps(context).isNotEmpty()

        /**
         * Posts a push's notification: a banner even while the app is open, which opens
         * `MainActivity` with the push's ids when tapped. A trip push expands into its
         * card, with Mark as Read and Dismiss under it.
         */
        internal fun post(context: Context, data: Map<String, String>) {
            val notifications = NotificationManagerCompat.from(context)
            val title = data["title"].orEmpty()
            val body = data["body"].orEmpty()
            if ((title.isEmpty() && body.isEmpty()) || !notifications.areNotificationsEnabled()) return

            val notificationId = data["notification_id"]
            // A push about the same inbox entry replaces the one already showing.
            val postedId = (notificationId ?: "$title\n$body\n${System.nanoTime()}").hashCode()

            val open = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            for (key in listOf("notification_id", "trip_id", "trip_item_id")) {
                data[key]?.let { open.putExtra(key, it) }
            }

            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(context.getColor(R.color.app_accent))
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                .setContentIntent(
                    PendingIntent.getActivity(context, postedId, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                )

            if (data["category"] == tripActivityCategory) {
                builder
                    .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                    .setCustomBigContentView(notificationCard(context, data["trip_title"], title, body))
                if (notificationId != null) {
                    builder
                        .addAction(0, "Mark as Read", actionIntent(context, Action.markRead, notificationId, postedId))
                        .addAction(0, "Dismiss", actionIntent(context, Action.dismiss, notificationId, postedId))
                }
            } else {
                builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
            }

            try {
                notifications.notify(postedId, builder.build())
            } catch (_: SecurityException) {
                // Permission was withdrawn between the check and here.
            }
        }

        /**
         * The expanded view of a trip push: a very dark gray card with a tag for what it's
         * about, an optional title line, then the message.
         */
        private fun notificationCard(context: Context, tripTitle: String?, title: String, message: String): RemoteViews {
            // The tag names the trip for now; fall back to the title, which the server
            // also sets to the trip's name.
            val tag = tripTitle?.takeIf { it.isNotEmpty() } ?: title
            // A title that isn't the trip's name is worth its own line.
            val titleLine = title.takeIf { it != tag }.orEmpty()

            val card = RemoteViews(context.packageName, R.layout.notification_trip_activity)
            card.setTextViewText(R.id.notification_tag, tag)
            card.setViewVisibility(R.id.notification_tag, if (tag.isEmpty()) View.GONE else View.VISIBLE)
            card.setTextViewText(R.id.notification_title, titleLine)
            card.setViewVisibility(R.id.notification_title, if (titleLine.isEmpty()) View.GONE else View.VISIBLE)
            card.setTextViewText(R.id.notification_message, message)
            return card
        }

        /** What an action button under a push sends: a broadcast to `PushActionReceiver`, without opening the app. */
        private fun actionIntent(context: Context, action: String, notificationId: String, postedId: Int): PendingIntent {
            val intent = Intent(context, PushActionReceiver::class.java)
                .setAction(action)
                .putExtra("notification_id", notificationId)
                .putExtra(postedIdKey, postedId)
            return PendingIntent.getBroadcast(context, postedId, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}

/**
 * What `PushNotifications.registerIfAllowed` asks permission with: shows the system's
 * notification prompt and returns whether the user allowed it. Remember it in the
 * screen that registers for pushes.
 */
@Composable
fun rememberNotificationPermissionRequest(): suspend () -> Boolean {
    val answer = remember { AtomicReference<CompletableDeferred<Boolean>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        answer.getAndSet(null)?.complete(granted)
    }
    return remember(launcher) {
        suspend {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                // Earlier versions allow notifications without asking.
                true
            } else {
                val asked = CompletableDeferred<Boolean>()
                answer.set(asked)
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                asked.await()
            }
        }
    }
}

/**
 * Receives the FCM token and the pushes themselves, which only come through a
 * service, and hands them to `PushNotifications`.
 */
class PushMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        OutboundApp.scope.launch { PushNotifications.shared.didRegister(token) }
    }

    /** Shows pushes as banners even while the app is open. */
    override fun onMessageReceived(message: RemoteMessage) {
        // Messages come in on a background thread; the screens watch the count on the main one.
        OutboundApp.scope.launch { PushNotifications.shared.didReceive() }
        // What to show is in the data; a `notification` payload fills in for a push sent with one.
        val data = message.data.toMutableMap()
        message.notification?.title?.let { data.putIfAbsent("title", it) }
        message.notification?.body?.let { data.putIfAbsent("body", it) }
        PushNotifications.post(this, data)
    }
}

/**
 * Receives Mark as Read and Dismiss from under an expanded push, and has
 * `PushNotifications` carry it out without opening the app.
 */
class PushActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val id = intent.getStringExtra("notification_id") ?: return
        // Either way the push is dealt with: take it off the shade.
        NotificationManagerCompat.from(context).cancel(intent.getIntExtra(PushNotifications.postedIdKey, 0))

        // Keeps the process alive until the request is done.
        val pending = goAsync()
        OutboundApp.scope.launch {
            try {
                PushNotifications.shared.didChoose(action, mapOf("notification_id" to id))
            } finally {
                pending.finish()
            }
        }
    }
}
