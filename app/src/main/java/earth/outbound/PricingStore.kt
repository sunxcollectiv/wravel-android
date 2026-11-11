//
//  PricingStore.kt
//  outbound
//

package earth.outbound

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import earth.outbound.networking.APIClient
import earth.outbound.networking.Endpoint
import kotlinx.coroutines.CancellationException

/** The rate tables (`/pricing/schedule`). No sign-in needed. */
object PricingAPI {
    /** The schedule new trips are priced under. */
    val current = Endpoint(path = "/pricing/schedule", requiresAuth = false, cachesResponse = true)

    /**
     * One schedule by its key, e.g. the one an older trip is still priced under. 404
     * for a key the server doesn't know.
     */
    fun schedule(key: String) =
        Endpoint(path = "/pricing/schedule", query = listOf("key" to key), requiresAuth = false, cachesResponse = true)
}

/**
 * The pricing schedules, from the server and kept on disk, so prices can be shown and
 * worked out offline.
 *
 * There can be several: rates are versioned, and each trip stays on the schedule it
 * was created under. `current` is the one new trips get; others are loaded by key as
 * they're needed (from a quote's `schedule`, or a trip's `priceSchedule`).
 */
class PricingStore(private val api: APIClient = APIClient.shared) {
    /** Every schedule loaded so far, by key. */
    var schedules by mutableStateOf(mapOf<String, PricingSchedule>())
        private set

    /** The key of the schedule new trips get, once known. */
    var currentKey by mutableStateOf<String?>(null)
        private set

    /** Keys that couldn't be loaded from the server or disk. */
    var unavailableKeys by mutableStateOf(setOf<String>())
        private set

    /** Whether the current schedule couldn't be loaded and none was saved. */
    var currentUnavailable by mutableStateOf(false)
        private set

    init {
        api.cached<PricingSchedule>(PricingAPI.current)?.let { saved ->
            currentKey = saved.key
            schedules = schedules + (saved.key to saved)
        }
    }

    /** The schedule new trips are priced under. */
    val current: PricingSchedule? get() = currentKey?.let { schedules[it] }

    /** The schedule with `key`, or the current one for null. Null until it's loaded. */
    fun schedule(key: String?): PricingSchedule? {
        if (key == null) return current
        return schedules[key]
    }

    /**
     * The schedule `trip` is priced under: the one it names, or null when it doesn't
     * name one (a server that doesn't send `price_schedule`) or that one isn't loaded.
     * Never assumes the current one, which would misprice an older trip.
     */
    fun schedule(trip: Trip): PricingSchedule? = trip.priceSchedule?.let { schedules[it] }

    /** Whether `key` (null: the current schedule) couldn't be loaded. */
    fun isUnavailable(key: String?): Boolean =
        if (key != null) key in unavailableKeys else currentUnavailable

    /** Fetches the current schedule, keeping the saved copy if the server can't be reached. */
    suspend fun loadCurrent() {
        try {
            val loaded = api.send<PricingSchedule>(PricingAPI.current)
            schedules = schedules + (loaded.key to loaded)
            currentKey = loaded.key
            currentUnavailable = false
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            currentUnavailable = current == null
        }
    }

    /**
     * Makes sure the schedule with `key` (null: the current one) is loaded: from disk if
     * it was saved, otherwise from the server. A schedule never changes once
     * published, so a saved one isn't fetched again.
     */
    suspend fun load(key: String?) {
        if (key == null) {
            loadCurrent()
            return
        }
        if (schedules[key] != null) return
        val endpoint = PricingAPI.schedule(key)
        api.cached<PricingSchedule>(endpoint)?.let { saved ->
            schedules = schedules + (key to saved)
            return
        }
        try {
            schedules = schedules + (key to api.send<PricingSchedule>(endpoint))
            unavailableKeys = unavailableKeys - key
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            unavailableKeys = unavailableKeys + key
        }
    }

    /** Loads the schedules `trips` are priced under, for any that aren't loaded yet. */
    suspend fun load(trips: List<Trip>) {
        for (key in trips.mapNotNull { it.priceSchedule }.toSet()) {
            load(key)
        }
    }
}

/** The app's `PricingStore`, provided to the signed-in screens. */
val LocalPricingStore = staticCompositionLocalOf<PricingStore> { error("No PricingStore provided") }
