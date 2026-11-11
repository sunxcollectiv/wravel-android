//
//  NotificationsAPI.kt
//  outbound
//

@file:UseSerializers(InstantSerializer::class)

package earth.outbound

import earth.outbound.auth.User
import earth.outbound.networking.Endpoint
import earth.outbound.networking.HTTPMethod
import earth.outbound.networking.InstantSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

/**
 * `NotificationCategory`: the coarse kind of a notification. Unknown values (a
 * category the app doesn't know yet) decode as `Other` so the inbox still loads.
 */
@Serializable(with = NotificationCategorySerializer::class)
enum class NotificationCategory(val rawValue: String) {
    /** Service notices from us. */
    Announcement("announcement"),

    /** Marketing, opt-in. */
    Promotion("promotion"),

    /** Something another member did on a shared trip. */
    TripActivity("trip_activity"),

    /** About the user's own travel: flight details, reminders. */
    TravelAlert("travel_alert"),
    Other("other");

    companion object {
        fun fromRawValue(raw: String): NotificationCategory? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** Reads a category by its raw value, falling back to `Other` for one it doesn't know. */
private object NotificationCategorySerializer : KSerializer<NotificationCategory> {
    override val descriptor = PrimitiveSerialDescriptor("NotificationCategory", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: NotificationCategory) = encoder.encodeString(value.rawValue)

    override fun deserialize(decoder: Decoder): NotificationCategory =
        NotificationCategory.fromRawValue(decoder.decodeString()) ?: NotificationCategory.Other
}

/**
 * `NotificationRead`: one entry in the user's inbox. The title and body are
 * written by the server when it's sent; `tripId` and `tripItemId` go null if what
 * it was about is deleted. `type` says exactly what happened (e.g.
 * "trip_item.updated"). The server's free-form `data` isn't decoded yet.
 */
@Serializable
data class AppNotification(
    val id: String,
    val category: NotificationCategory,
    val type: String,
    val title: String,
    val body: String? = null,
    /** Who caused it, when it's someone's doing. */
    val actor: User? = null,
    val tripId: String? = null,
    val tripItemId: String? = null,
    /** When it arrived in the inbox; the inbox is ordered by this. */
    val visibleAt: Instant,
    val readAt: Instant? = null,
) {
    val isRead: Boolean get() = readAt != null
}

/**
 * Request shapes for the notifications endpoints.
 *
 *     GET    /notifications?limit&before   -> NotificationPage
 *     GET    /notifications/unread-count   -> UnreadCount
 *     POST   /notifications/read-all       -> UnreadCount
 *     POST   /notifications/{id}/read      -> NotificationRead
 *     DELETE /notifications/{id}/read      -> NotificationRead
 *     DELETE /notifications/{id}           -> 204 (dismisses it)
 */
object NotificationsAPI {
    /**
     * `NotificationPage`: newest first. Pass `nextCursor` as `before` for the next
     * page; null when there's no more.
     */
    @Serializable
    data class Page(
        val items: List<AppNotification>,
        val nextCursor: String? = null,
    )

    @Serializable
    data class UnreadCount(val count: Int)

    fun list(before: String? = null, limit: Int = 25): Endpoint {
        val query = mutableListOf<Pair<String, String?>>("limit" to limit.toString())
        if (before != null) {
            query.add("before" to before)
        }
        return Endpoint(path = "/notifications", query = query)
    }

    val unreadCount = Endpoint(path = "/notifications/unread-count")

    val markAllRead = Endpoint(path = "/notifications/read-all", method = HTTPMethod.POST)

    fun markRead(id: String) = Endpoint(path = "/notifications/$id/read", method = HTTPMethod.POST)

    fun markUnread(id: String) = Endpoint(path = "/notifications/$id/read", method = HTTPMethod.DELETE)

    fun dismiss(id: String) = Endpoint(path = "/notifications/$id", method = HTTPMethod.DELETE)
}
