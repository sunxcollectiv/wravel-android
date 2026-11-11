//
//  NotificationStore.kt
//  outbound
//

package earth.outbound

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import earth.outbound.networking.APIClient
import earth.outbound.networking.localizedDescription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Loads and holds the signed-in user's notification inbox and the unread badge.
 *
 * Changes to read state and dismissals show at once and are sent in the
 * background; one that fails is rolled back.
 */
class NotificationStore(private val api: APIClient = APIClient.shared) {
    var notifications by mutableStateOf(listOf<AppNotification>())
        private set
    var unreadCount by mutableIntStateOf(0)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var isLoadingMore by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)

    /** The cursor for the next (older) page, or null when the whole inbox is loaded. */
    private var nextCursor by mutableStateOf<String?>(null)

    /** For the changes sent in the background. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val hasMore: Boolean get() = nextCursor != null

    /** Fetches the newest page, replacing what's loaded, and refreshes the badge. */
    suspend fun load() {
        isLoading = true
        try {
            val loaded = api.send<NotificationsAPI.Page>(NotificationsAPI.list())
            val unread = api.send<NotificationsAPI.UnreadCount>(NotificationsAPI.unreadCount)
            notifications = loaded.items
            nextCursor = loaded.nextCursor
            unreadCount = unread.count
            errorMessage = null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errorMessage = error.localizedDescription
        } finally {
            isLoading = false
        }
    }

    /** Fetches the next older page, if there is one and one isn't already coming. */
    suspend fun loadMore() {
        val cursor = nextCursor
        if (cursor == null || isLoadingMore) return
        isLoadingMore = true
        try {
            val page = api.send<NotificationsAPI.Page>(NotificationsAPI.list(before = cursor))
            // A refresh may have landed meanwhile; skip anything already shown.
            val shown = notifications.map { it.id }.toSet()
            notifications = notifications + page.items.filter { it.id !in shown }
            nextCursor = page.nextCursor
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errorMessage = error.localizedDescription
        } finally {
            isLoadingMore = false
        }
    }

    /** Refreshes just the badge, e.g. when the trips screen appears. Best-effort. */
    suspend fun refreshUnreadCount() {
        try {
            unreadCount = api.send<NotificationsAPI.UnreadCount>(NotificationsAPI.unreadCount).count
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        }
    }

    fun setRead(notification: AppNotification, read: Boolean) {
        val previous = notifications.firstOrNull { it.id == notification.id }
        if (previous == null || previous.isRead == read) return
        replace(previous.copy(readAt = if (read) Instant.now() else null))
        unreadCount = maxOf(0, unreadCount + (if (read) -1 else 1))

        scope.launch {
            try {
                val endpoint = if (read) NotificationsAPI.markRead(previous.id) else NotificationsAPI.markUnread(previous.id)
                val updated = api.send<AppNotification>(endpoint)
                replace(updated)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                replace(previous)
                unreadCount = maxOf(0, unreadCount + (if (read) 1 else -1))
                errorMessage = error.localizedDescription
            }
        }
    }

    fun markAllRead() {
        val previous = notifications
        val previousCount = unreadCount
        val now = Instant.now()
        notifications = notifications.map { if (it.isRead) it else it.copy(readAt = now) }
        unreadCount = 0

        scope.launch {
            try {
                unreadCount = api.send<NotificationsAPI.UnreadCount>(NotificationsAPI.markAllRead).count
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                notifications = previous
                unreadCount = previousCount
                errorMessage = error.localizedDescription
            }
        }
    }

    /**
     * Removes a notification from the inbox. The server keeps it out of sight, so a
     * dismissed announcement doesn't come back.
     */
    fun dismiss(notification: AppNotification) {
        val index = notifications.indexOfFirst { it.id == notification.id }
        if (index < 0) return
        notifications = notifications.filterIndexed { i, _ -> i != index }
        if (!notification.isRead) {
            unreadCount = maxOf(0, unreadCount - 1)
        }

        scope.launch {
            try {
                api.sendIgnoringResponse(NotificationsAPI.dismiss(notification.id))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                notifications = notifications.toMutableList().apply { add(minOf(index, size), notification) }
                if (!notification.isRead) {
                    unreadCount += 1
                }
                errorMessage = error.localizedDescription
            }
        }
    }

    private fun replace(notification: AppNotification) {
        notifications = notifications.map { if (it.id == notification.id) notification else it }
    }
}
